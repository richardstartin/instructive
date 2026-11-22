package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.FailedCheck;
import instructive.analyse.FailedCheckRule;
import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Finds the explicit checks C2 compiles where a null check or a range check has failed,
 * and none where they haven't. Each case has two copies of the same method, one of them
 * made to fail after it has been compiled: a range check once, a null check five times.
 */
class FailedCheckTest {
    private static final int LENGTH = 1 << 12;

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the register model is for aarch64");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    static final class Box {
        final int value;

        Box(int value) {
            this.value = value;
        }
    }

    @Test
    void findsTheNullCheckAfterANull() {
        Box[] boxes = boxes();
        Box[] withNull = boxes.clone();
        withNull[LENGTH / 2] = null;
        InstructionProfile profile = profile("sumBoxesAfterNull",
                () -> sumBoxesAfterNull(boxes),
                () -> {
                    // A null in compiled code is handled by the implicit check, without
                    // recompiling, until it has happened a few times (three, on JDK 17 to 25).
                    for (int i = 0; i < 5; i++) {
                        assertThrows(NullPointerException.class, () -> sumBoxesAfterNull(withNull));
                    }
                });
        List<FailedCheck> checks = apply(profile);
        String report = describe(profile, checks);
        assertEquals(1, checks.size(), report);
        FailedCheck check = checks.get(0);
        assertEquals(FailedCheck.Kind.NULL_CHECK, check.kind(), report);
        assertEquals(FailedCheck.Failure.THROW, check.failure(), report);
        // In the hot loop, once per element of each unrolled iteration.
        assertTrue(check.inLoop(), report);
        assertTrue(check.checks().size() > 1, report);
        assertTrue(check.samples() * 10 >= profile.sampled() * 9, report);
    }

    @Test
    void findsNoNullCheckWhenNoneHasFailed() {
        Box[] boxes = boxes();
        InstructionProfile profile = profile("sumBoxes", () -> sumBoxes(boxes), () -> {});
        List<FailedCheck> checks = apply(profile);
        assertTrue(checks.isEmpty(), describe(profile, checks));
    }

    @Test
    void findsTheRangeCheckAfterAnOverflow() {
        int[] values = values();
        InstructionProfile profile = profile("sumRangeAfterOverflow",
                () -> sumRangeAfterOverflow(values, LENGTH),
                () -> assertThrows(ArrayIndexOutOfBoundsException.class, () -> sumRangeAfterOverflow(values, LENGTH + 1)));
        List<FailedCheck> checks = apply(profile);
        String report = describe(profile, checks);
        assertEquals(1, checks.size(), report);
        assertEquals(FailedCheck.Kind.RANGE_CHECK, checks.get(0).kind(), report);
        assertEquals(FailedCheck.Failure.THROW, checks.get(0).failure(), report);
    }

    @Test
    void findsNoRangeCheckWhenNoneHasFailed() {
        int[] values = values();
        InstructionProfile profile = profile("sumRange", () -> sumRange(values, LENGTH), () -> {});
        List<FailedCheck> checks = apply(profile);
        assertTrue(checks.isEmpty(), describe(profile, checks));
    }

    static int sumBoxes(Box[] boxes) {
        int sum = 0;
        for (int i = 0; i < boxes.length; i++) {
            sum += boxes[i].value;
        }
        return sum;
    }

    static int sumBoxesAfterNull(Box[] boxes) {
        int sum = 0;
        for (int i = 0; i < boxes.length; i++) {
            sum += boxes[i].value;
        }
        return sum;
    }

    static int sumRange(int[] values, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += values[i];
        }
        return sum;
    }

    static int sumRangeAfterOverflow(int[] values, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += values[i];
        }
        return sum;
    }

    private static Box[] boxes() {
        Box[] boxes = new Box[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            boxes[i] = new Box(i);
        }
        return boxes;
    }

    private static int[] values() {
        int[] values = new int[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            values[i] = i;
        }
        return values;
    }

    private static List<FailedCheck> apply(InstructionProfile profile) {
        List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
        return new FailedCheckRule().apply(code, new Aarch64RegisterModel(),
                instruction -> profile.count((int) (instruction.address() - profile.address())));
    }

    // Compiles `kernel`, makes it fail with `failure`, runs it for a second while it
    // is recompiled, then profiles it for a second and returns the profile of the method named `name`.
    private static InstructionProfile profile(String name, IntSupplier kernel, Runnable failure) {
        int sink = 0;
        for (int i = 0; i < 20000; i++) {
            sink += kernel.getAsInt();
        }
        failure.run();
        // Long enough for the method to be recompiled with what the failure taught it.
        long recompiled = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < recompiled) {
            sink += kernel.getAsInt();
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += kernel.getAsInt();
            }
        } finally {
            Sampler.stop();
        }
        String method = "FailedCheckTest." + name + "(";
        assertTrue(sink != 42);
        return CompiledCopies.hottestC2(method);
    }

    private static String describe(InstructionProfile profile, List<FailedCheck> checks) {
        StringBuilder report = new StringBuilder(profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n");
        for (FailedCheck check : checks) {
            report.append(String.format("  %s %s -> +0x%x, %d samples: %s%n", check.kind(), check.failure(),
                    check.failurePath() - profile.address(), check.samples(),
                    check.checks().stream().map(i -> String.format("+0x%x %s", i.address() - profile.address(), i.text())).toList()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return report + bytes.toString(StandardCharsets.UTF_8);
    }
}
