package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.NotUnrolledLoop;
import instructive.analyse.NotUnrolledLoopRule;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Finds loops C2 didn't unroll, and leaves alone the ones it did. */
class NotUnrolledLoopTest {

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the register model is for aarch64");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void findsALoopWhoseConditionCallsAMethod() {
        InstructionProfile profile = profile("sumWithCallInCondition", NotUnrolledLoopTest::sumWithCallInCondition);
        List<NotUnrolledLoop> loops = apply(profile);
        String report = describe(profile, loops);
        assertEquals(1, loops.size(), report);
        NotUnrolledLoop loop = loops.get(0);
        // One element per iteration, by an index stepped by one.
        assertEquals(1, loop.step(), report);
        assertFalse(loop.wide(), report);
        // The call that keeps C2 from treating it as counted.
        assertEquals(1, loop.calls().size(), report);
        // And it's where the method spends its time.
        assertTrue(loop.samples() * 10 >= profile.sampled() * 9, report);
    }

    @Test
    void leavesAnUnrolledLoopAlone() {
        InstructionProfile profile = profile("sum", NotUnrolledLoopTest::sum);
        List<NotUnrolledLoop> loops = apply(profile);
        assertTrue(loops.isEmpty(), describe(profile, loops));
    }

    // A long loop variable doesn't keep C2 from unrolling, from JDK 17 on: it splits the
    // loop into an outer loop around an inner loop counted by an int, and unrolls that.
    @Test
    void leavesALoopCountedByALongAlone() {
        InstructionProfile profile = profile("sumByLong", NotUnrolledLoopTest::sumByLong);
        List<NotUnrolledLoop> loops = apply(profile);
        assertTrue(loops.isEmpty(), describe(profile, loops));
    }

    /**
     * The same operation on every element, nothing carried between them: C2 vectorises
     * it. Vectorising works on an unrolled loop, combining the unrolled copies into
     * vector instructions.
     */
    @Test
    void leavesAVectorisedLoopAlone() {
        InstructionProfile profile = profile("add", NotUnrolledLoopTest::add);
        List<NotUnrolledLoop> loops = apply(profile);
        String report = describe(profile, loops);
        assertTrue(loops.isEmpty(), report);
        assertTrue(simd(instructions(profile)) > 0, report);
    }

    /**
     * The same kernel, but with a call in the condition, C2 can't unroll the loop, so it
     * can't vectorise it either.
     */
    @Test
    void findsALoopThatCouldNotBeVectorised() {
        InstructionProfile profile = profile("addWithCallInCondition", NotUnrolledLoopTest::addWithCallInCondition);
        List<NotUnrolledLoop> loops = apply(profile);
        String report = describe(profile, loops);
        assertEquals(1, loops.size(), report);
        NotUnrolledLoop loop = loops.get(0);
        assertEquals(1, loop.step(), report);
        assertEquals(1, loop.calls().size(), report);
        assertEquals(0, simd(loop.instructions()), report);
        assertTrue(loop.samples() * 10 >= profile.sampled() * 9, report);
    }

    static void add(int[] a, int[] b, int[] c) {
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i] + b[i];
        }
    }

    static void addWithCallInCondition(int[] a, int[] b, int[] c) {
        for (int i = 0; i < limit(a); i++) {
            c[i] = a[i] + b[i];
        }
    }

    private interface TriConsumer {
        void accept(int[] a, int[] b, int[] c);
    }

    private static InstructionProfile profile(String name, TriConsumer kernel) {
        int[] a = new int[1 << 12];
        int[] b = new int[a.length];
        int[] c = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = i;
            b[i] = 3 * i;
        }
        return profile(name, data -> {
            kernel.accept(a, b, c);
            return c[1];
        });
    }

    private static long simd(List<Instruction> instructions) {
        return instructions.stream().filter(instruction -> instruction.kind() == InstructionKind.SIMD).count();
    }

    static int sum(int[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += data[i];
        }
        return sum;
    }

    static int sumByLong(int[] data) {
        int sum = 0;
        for (long i = 0; i < data.length; i++) {
            sum += data[(int) i];
        }
        return sum;
    }

    static int sumWithCallInCondition(int[] data) {
        int sum = 0;
        for (int i = 0; i < limit(data); i++) {
            sum += data[i];
        }
        return sum;
    }

    static int limit(int[] data) {
        return data.length;
    }

    private static List<Instruction> instructions(InstructionProfile profile) {
        return new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
    }

    private static List<NotUnrolledLoop> apply(InstructionProfile profile) {
        List<Instruction> code = instructions(profile);
        return new NotUnrolledLoopRule().apply(code, new Aarch64RegisterModel(),
                instruction -> profile.count((int) (instruction.address() - profile.address())));
    }

    // Profiles `kernel` for a second and returns the profile of the method named `name`.
    private static InstructionProfile profile(String name, ToIntFunction<int[]> kernel) {
        int[] data = new int[1 << 16];
        for (int i = 0; i < data.length; i++) {
            data[i] = i * 31;
        }
        int sink = 0;
        for (int i = 0; i < 2000; i++) {
            sink += kernel.applyAsInt(data); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += kernel.applyAsInt(data);
            }
        } finally {
            Sampler.stop();
        }
        String method = "NotUnrolledLoopTest." + name + "(";
        assertTrue(sink != 42);
        return CompiledCopies.hottestC2(method);
    }

    private static String describe(InstructionProfile profile, List<NotUnrolledLoop> loops) {
        StringBuilder report = new StringBuilder(profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n");
        for (NotUnrolledLoop loop : loops) {
            report.append(String.format("  loop at +0x%x: %d instructions, %d samples, %s steps by %d%s, %d calls%n",
                    loop.header() - profile.address(), loop.length(), loop.samples(), loop.inductionVariable(), loop.step(),
                    loop.wide() ? " (64-bit)" : "", loop.calls().size()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return report + bytes.toString(StandardCharsets.UTF_8);
    }
}
