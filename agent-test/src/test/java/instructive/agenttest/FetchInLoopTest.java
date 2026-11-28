package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.FetchInLoop;
import instructive.analyse.FetchInLoopRule;
import instructive.analyse.MemoryOrderingModel;
import instructive.analyse.MemoryOrderingModel.Ordering;
import instructive.parse.Disassembler;
import instructive.parse.Instruction;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Finds loops updating an {@code AtomicLong}, {@code AtomicInteger}, {@code AtomicBoolean}
 * or {@code LongAdder} on every iteration, and leaves alone a plain counter and an atomic
 * updated once after the loop.
 */
class FetchInLoopTest {

    private static final String ARCHITECTURE = System.getProperty("os.arch");
    private static final boolean X86 = ARCHITECTURE.equals("amd64") || ARCHITECTURE.equals("x86_64");
    private static final int LENGTH = 1 << 12;

    @BeforeAll
    static void onlyOnASupportedArchitecture() {
        assumeTrue(MemoryOrderingModel.forArchitecture(ARCHITECTURE).isPresent(), "no model for " + ARCHITECTURE);
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void findsAtomicLongAddAndGet() {
        AtomicLong counter = new AtomicLong();
        assertUpdatedInLoop(profile("atomicLongAddAndGet", data -> atomicLongAddAndGet(counter, data)));
    }

    @Test
    void findsAtomicIntegerGetAndAdd() {
        AtomicInteger counter = new AtomicInteger();
        assertUpdatedInLoop(profile("atomicIntegerGetAndAdd", data -> atomicIntegerGetAndAdd(counter, data)));
    }

    @Test
    void findsAtomicIntegerCompareAndSet() {
        AtomicInteger counter = new AtomicInteger();
        assertUpdatedInLoop(profile("atomicIntegerCompareAndSet", data -> atomicIntegerCompareAndSet(counter, data)));
    }

    @Test
    void findsAtomicBooleanCompareAndSet() {
        AtomicBoolean flag = new AtomicBoolean();
        assertUpdatedInLoop(profile("atomicBooleanCompareAndSet", data -> atomicBooleanCompareAndSet(flag, data)));
    }

    @Test
    void findsLongAdderAdd() {
        LongAdder adder = new LongAdder();
        // Uncontended, add CASes the base: ldar of cells and base, dmb ish, casl on
        // AArch64; lock cmpxchg on x86.
        assertUpdatedInLoop(profile("longAdderAdd", data -> longAdderAdd(adder, data)));
    }

    /**
     * A volatile read per element: {@code ldar} on AArch64. On x86 volatile reads are
     * ordinary loads, so there is nothing to find.
     */
    @Test
    void findsAtomicIntegerGetOnAarch64() {
        AtomicInteger value = new AtomicInteger(42);
        InstructionProfile profile = profile("atomicIntegerGet", data -> atomicIntegerGet(value, data));
        List<FetchInLoop> findings = apply(profile);
        String report = describe(profile, findings);
        if (X86) {
            assertEquals(List.of(), findings, report);
            return;
        }
        FetchInLoop loop = hottest(profile, findings, report);
        assertEquals(Set.of(Ordering.ACQUIRE_LOAD), orderings(loop), report);
    }

    /**
     * A volatile write per element: {@code stlr} on AArch64. On x86 the stores are plain
     * moves, but C2 keeps a fence ({@code lock add dword ptr [rsp - 0x40], 0x0}) in each
     * iteration of the unrolled loop.
     */
    @Test
    void findsAtomicBooleanSet() {
        AtomicBoolean flag = new AtomicBoolean();
        InstructionProfile profile = profile("atomicBooleanSet", data -> atomicBooleanSet(flag, data));
        List<FetchInLoop> findings = apply(profile);
        String report = describe(profile, findings);
        FetchInLoop loop = hottest(profile, findings, report);
        assertEquals(Set.of(X86 ? Ordering.FENCE : Ordering.RELEASE_STORE), orderings(loop), report);
    }

    @Test
    void leavesAPlainCounterAlone() {
        long[] box = new long[1];
        InstructionProfile profile = profile("plainCounter", data -> plainCounter(box, data));
        List<FetchInLoop> findings = apply(profile);
        assertEquals(List.of(), findings, describe(profile, findings));
    }

    @Test
    void leavesAnAtomicUpdatedAfterTheLoopAlone() {
        AtomicLong total = new AtomicLong();
        InstructionProfile profile = profile("atomicAddAfterLoop", data -> atomicAddAfterLoop(total, data));
        List<FetchInLoop> findings = apply(profile);
        assertEquals(List.of(), findings, describe(profile, findings));
    }

    static long atomicLongAddAndGet(AtomicLong counter, int[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += counter.addAndGet(data[i]);
        }
        return sum;
    }

    static int atomicIntegerGetAndAdd(AtomicInteger counter, int[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += counter.getAndAdd(data[i]);
        }
        return sum;
    }

    static int atomicIntegerCompareAndSet(AtomicInteger counter, int[] data) {
        int successes = 0;
        for (int i = 0; i < data.length; i++) {
            if (counter.compareAndSet(i - 1, i)) {
                successes++;
            }
        }
        return successes;
    }

    static int atomicBooleanCompareAndSet(AtomicBoolean flag, int[] data) {
        int successes = 0;
        for (int i = 0; i < data.length; i++) {
            if (flag.compareAndSet((i & 1) == 0, (i & 1) != 0)) {
                successes++;
            }
        }
        return successes;
    }

    static long longAdderAdd(LongAdder adder, int[] data) {
        for (int i = 0; i < data.length; i++) {
            adder.add(data[i]);
        }
        return adder.sum();
    }

    static int atomicIntegerGet(AtomicInteger value, int[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += value.get() ^ data[i];
        }
        return sum;
    }

    static int atomicBooleanSet(AtomicBoolean flag, int[] data) {
        int sum = 0;
        for (int i = 0; i < data.length; i++) {
            flag.set((data[i] & 1) == 0);
            sum += data[i];
        }
        return sum;
    }

    static long plainCounter(long[] box, int[] data) {
        for (int i = 0; i < data.length; i++) {
            box[0] += data[i];
        }
        return box[0];
    }

    static long atomicAddAfterLoop(AtomicLong total, int[] data) {
        long sum = 0;
        for (int i = 0; i < data.length; i++) {
            sum += data[i];
        }
        return total.addAndGet(sum);
    }

    // The hottest finding is the loop the method spends its time in, and it updates
    // memory atomically, with samples on the updates.
    private static void assertUpdatedInLoop(InstructionProfile profile) {
        List<FetchInLoop> findings = apply(profile);
        String report = describe(profile, findings);
        FetchInLoop loop = hottest(profile, findings, report);
        assertFalse(loop.accesses(Ordering.ATOMIC_UPDATE).isEmpty(), report);
        assertTrue(loop.accessSamples() > 0, report);
    }

    private static FetchInLoop hottest(InstructionProfile profile, List<FetchInLoop> findings, String report) {
        assertFalse(findings.isEmpty(), report);
        FetchInLoop loop = findings.get(0);
        assertTrue(loop.samples() * 4 >= profile.sampled() * 3, report);
        return loop;
    }

    private static Set<Ordering> orderings(FetchInLoop loop) {
        return loop.accesses().stream().map(FetchInLoop.Access::ordering).collect(Collectors.toSet());
    }

    private static List<FetchInLoop> apply(InstructionProfile profile) {
        Disassembler disassembler = InstructionProfileFormatter.forArchitecture(ARCHITECTURE).orElseThrow();
        List<Instruction> code = disassembler.decodeAll(profile.machineCode().orElseThrow(), profile.address());
        return new FetchInLoopRule(MemoryOrderingModel.forArchitecture(ARCHITECTURE).orElseThrow())
                .apply(code, instruction -> profile.count((int) (instruction.address() - profile.address())));
    }

    // Profiles `kernel` for a second and returns the profile of the method named `name`.
    private static InstructionProfile profile(String name, ToLongFunction<int[]> kernel) {
        int[] data = new int[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            data[i] = i * 7;
        }
        long sink = 0;
        for (int i = 0; i < 20_000; i++) {
            sink += kernel.applyAsLong(data); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += kernel.applyAsLong(data);
            }
        } finally {
            Sampler.stop();
        }
        String method = "FetchInLoopTest." + name + "(";
        InstructionProfile[] hottest = new InstructionProfile[1];
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (hottest[0] == null && profile.name().contains(method)) {
                hottest[0] = profile.snapshot();
            }
        });
        assertNotNull(hottest[0], "no samples of " + name + " (" + sink + ")");
        return hottest[0];
    }

    private static String describe(InstructionProfile profile, List<FetchInLoop> findings) {
        StringBuilder report = new StringBuilder(profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n");
        for (FetchInLoop loop : findings) {
            report.append(String.format("  loop at +0x%x: %d samples, %d on accesses: %s%n", loop.header() - profile.address(),
                    loop.samples(), loop.accessSamples(), loop.accesses().stream()
                            .map(access -> access.instruction().text() + " " + access.ordering()).collect(Collectors.joining("; "))));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return report + bytes.toString(StandardCharsets.UTF_8);
    }
}
