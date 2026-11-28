plugins {
    java
}

// Just the agent jar, for -javaagent; its dependencies come through testImplementation.
val agentJar = configurations.create("agentJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    agentJar(project(path = ":agent", configuration = "agentJar"))
    testImplementation(project(path = ":agent", configuration = "agentJar"))
    testImplementation(project(":parse"))
    testImplementation(project(":analyse"))

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Every test JVM runs with the agent, and the compile commands the tests rely on.
tasks.withType<Test>().configureEach {
    // The agent's native unit tests run wherever its integration tests do, CI included.
    dependsOn(":agent:nativeTest")
    inputs.files(agentJar)
    // The JIT's log: what was compiled, at which tier and with which compile id, what was
    // made not entrant, and (JDK 17's sweeper) flushed. One file per test JVM, with the
    // test results, so CI's uploaded reports carry it when a job fails.
    val jitLogs = reports.junitXml.outputLocation.dir("jit")
    doFirst {
        // Only this run's logs: they're named by process id.
        jitLogs.get().asFile.deleteRecursively()
        jitLogs.get().asFile.mkdirs()
    }
    // The sweeper that flushed cold and replaced code went in JDK 20, and its log tags
    // with it. The tests run on the JDK running Gradle.
    val sweeper = if (JavaVersion.current() < JavaVersion.VERSION_20) ",codecache+sweep=debug" else ""
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Xlog:jit+compilation=debug,codecache=debug$sweeper:file=${jitLogs.get().asFile.absolutePath}/jit-%p.log:uptime,tid:filecount=0")
    })
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-javaagent:${agentJar.singleFile.absolutePath}",
            "--enable-native-access=ALL-UNNAMED",
            "-XX:CompileCommand=quiet",
            // Arrays.hashCode(int[]) as a scalar loop rather than the SIMD stub, for
            // DependencyChainTest. The flag doesn't exist on every JDK.
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:-UseVectorizedHashCodeIntrinsic",
            "-XX:CompileCommand=exclude,instructive.agenttest.CodeCacheTest::interpretedLoop",
            // DependencyChainTest profiles these as methods of their own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.DependencyChainTest::encodeDeltas",
            "-XX:CompileCommand=dontinline,instructive.agenttest.DependencyChainTest::decodeDeltas",
            // NotUnrolledLoopTest: limit() stays a call in the loop's condition, and each
            // loop is profiled as a method of its own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.NotUnrolledLoopTest::*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.PointerChasingTest::sum*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.FailedCheckTest::sum*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.UnpredictableBranchTest::dispatchRandom",
            "-XX:CompileCommand=dontinline,instructive.agenttest.UnpredictableBranchTest::chainRandom",
            "-XX:CompileCommand=dontinline,instructive.agenttest.UnpredictableBranchTest::select",
            // MegamorphicCallTest's loops, each a method of its own, and the one receiver
            // type's method kept out of line, so its loop has a call to look at.
            "-XX:CompileCommand=dontinline,instructive.agenttest.MegamorphicCallTest::sum*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.MegamorphicCallTest\$Square::area",
            // MissedVectorisationTest's kernels, each profiled as a method of its own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.MissedVectorisationTest::daxpy*",
            "-XX:CompileCommand=dontinline,instructive.agenttest.MissedVectorisationTest::intersect",
            // FetchInLoopTest's loops, each profiled as a method of its own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.FetchInLoopTest::*",
            // DispatchAttributionTest profiles the loop as a method of its own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.DispatchAttributionTest::sumAreas",
            // MethodNamesTest looks for these as compiled methods of their own.
            "-XX:CompileCommand=dontinline,instructive.agenttest.MethodNamesTest::twice",
            "-XX:CompileCommand=dontinline,instructive.agenttest.MethodNamesTest::thrice",
            "-XX:CompileCommand=dontinline,instructive.agenttest.MethodNamesTest::half",
        )
    })
}

// Tests tagged "small-counter-storage" need a JVM of their own, whose counter storage
// is too small for every sampled method.
val smallCounterStorageTest = tasks.register<Test>("smallCounterStorageTest") {
    description = "Runs the tests that need the per-PC counter storage to fill up."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("small-counter-storage")
    }
    systemProperty("instructive.counterCapacity", "1024")
}

// Tests tagged "no-superword" need a JVM of their own, in which C2 doesn't vectorise.
val noSuperWordTest = tasks.register<Test>("noSuperWordTest") {
    description = "Runs the tests that need C2's superword pass, its vectoriser, turned off."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("no-superword")
    }
    jvmArgs("-XX:-UseSuperWord")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("small-counter-storage", "no-superword")
    }
    // So that running the tests (as CI does) runs these too.
    dependsOn(smallCounterStorageTest, noSuperWordTest)
}
