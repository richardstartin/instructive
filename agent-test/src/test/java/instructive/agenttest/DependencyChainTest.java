package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.DependencyChain;
import instructive.analyse.DependencyGraph;
import instructive.analyse.LongDependencyChainRule;
import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Finds the serial chain h = 31 * h + a[i] in Arrays.hashCode(int[]) as compiled by C2. */
class DependencyChainTest {

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the decoder is for aarch64");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void flagsArraysHashCodesDataDependency() {
        InstructionProfile hottest = profileArraysHashCode();
        byte[] code = hottest.machineCode().orElseThrow();
        List<Instruction> instructions = new Aarch64Disassembler().decodeAll(code, hottest.address());

        List<DependencyChain> chains = new LongDependencyChainRule().apply(
                instructions, new Aarch64RegisterModel(), instruction -> hottest.count((int) (instruction.address() - hottest.address())));
        String report = describe(hottest, chains);
        assertFalse(chains.isEmpty(), report);

        DependencyChain hottestChain = chains.get(0);
        // h = 31 * h + x carried from one iteration of the unrolled loop to the next,
        // computed as lsl #5, sub, add for each element.
        assertEquals(DependencyChain.Kind.LOOP_CARRIED, hottestChain.kind(), report);
        assertTrue(hottestChain.carriedRegister().isPresent(), report);
        assertTrue(hottestChain.criticalPathLength() >= LongDependencyChainRule.DEFAULT_MINIMUM_RECURRENCE_LENGTH, report);
        long arithmetic = hottestChain.criticalPath().stream()
                .filter(instruction -> Set.of("lsl", "sub", "add").contains(instruction.mnemonic()))
                .count();
        assertEquals(hottestChain.criticalPathLength(), arithmetic, report);
        // The loads feeding it are part of it, and the profile intersects it. How many of
        // the samples land on its own instructions depends on the layout: when its last
        // instruction is just before the loop's compare, skid puts most of them there.
        assertTrue(hottestChain.instructions().stream().anyMatch(instruction -> instruction.mnemonic().equals("ldr")), report);
        assertTrue(hottestChain.samples() > 0, report);
        // The loop it runs round is where the method spends its time.
        assertTrue(hottestChain.spanSamples() * 10 >= hottest.sampled() * 9, report);
    }

    /**
     * Delta decoding: each value is the previous decoded value plus a delta, so every
     * element waits for the one before. The running value stays in a register, so the
     * dependency is carried from one iteration of the unrolled loop to the next.
     */
    @Test
    void flagsDeltaDecodingsDataDependency() {
        InstructionProfile hottest = profileDeltaDecoding();
        assertTrue(hottest.name().contains("decodeDeltas"), hottest::toString);
        List<Instruction> instructions =
                new Aarch64Disassembler().decodeAll(hottest.machineCode().orElseThrow(), hottest.address());
        // A recurrence of one add per element is short per iteration: flag it from 4, so
        // the test doesn't depend on how far C2 unrolls the loop.
        List<DependencyChain> chains = new LongDependencyChainRule(LongDependencyChainRule.DEFAULT_MINIMUM_CHAIN_LENGTH, 4)
                .apply(instructions, new Aarch64RegisterModel(),
                        instruction -> hottest.count((int) (instruction.address() - hottest.address())));
        String report = describe(hottest, chains);
        assertFalse(chains.isEmpty(), report);
        DependencyChain chain = chains.get(0);
        assertEquals(DependencyChain.Kind.LOOP_CARRIED, chain.kind(), report);

        // The critical path is the running value, one add per element: each adds a delta to
        // the previous element's result.
        Aarch64RegisterModel registers = new Aarch64RegisterModel();
        List<Instruction> path = chain.criticalPath();
        assertTrue(path.size() >= 4, report);
        for (int i = 0; i < path.size(); i++) {
            Instruction add = path.get(i);
            assertEquals("add", add.mnemonic(), report);
            if (i > 0) {
                Set<String> previous = registers.usage(path.get(i - 1)).writes();
                assertTrue(registers.usage(add).reads().stream().anyMatch(previous::contains), report);
            }
        }
        assertEquals(chain.carriedRegister().orElseThrow(),
                registers.usage(path.get(path.size() - 1)).writes().iterator().next(), report);
        // The deltas are loaded into it and the decoded values stored from it, one of each
        // per element; the loop's compare and branch aren't part of it.
        long loads = chain.instructions().stream().filter(instruction -> instruction.mnemonic().equals("ldr")).count();
        long stores = chain.instructions().stream().filter(instruction -> instruction.mnemonic().equals("str")).count();
        assertEquals(path.size(), loads, report);
        assertEquals(path.size(), stores, report);
        assertTrue(chain.instructions().stream().noneMatch(instruction -> instruction.mnemonic().equals("cmp")
                || instruction.mnemonic().startsWith("b")), report);
        // And the profile intersects it.
        assertTrue(chain.samples() > 0, report);
    }

    /**
     * Delta encoding, the inverse: each output is the difference of two inputs, so no
     * output waits for another. The previous input is carried from one iteration to the
     * next, but it comes straight from a load, so nothing forms a cycle.
     */
    @Test
    void findsNoChainInDeltaEncoding() {
        InstructionProfile hottest = profile("encodeDeltas", DependencyChainTest::encodeDeltas);
        List<Instruction> instructions =
                new Aarch64Disassembler().decodeAll(hottest.machineCode().orElseThrow(), hottest.address());
        Aarch64RegisterModel registers = new Aarch64RegisterModel();
        List<DependencyChain> chains = new LongDependencyChainRule().apply(instructions, registers,
                instruction -> hottest.count((int) (instruction.address() - hottest.address())));
        String report = describe(hottest, chains);
        assertTrue(chains.isEmpty(), report);

        // The previous value is carried: a sub reads a register a load wrote on the
        // previous iteration.
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        boolean carriesALoad = false;
        for (int i = 0; i < graph.size(); i++) {
            if (graph.instruction(i).mnemonic().equals("sub")) {
                for (int writer : graph.loopCarriedDependencies(i)) {
                    carriesALoad |= graph.instruction(writer).mnemonic().startsWith("ld");
                }
            }
        }
        assertTrue(carriesALoad, report);
        // But it's not a recurrence: the only cycles are loop counters, one add of a
        // constant each, which even the lowest threshold finds alone.
        for (DependencyGraph.Recurrence recurrence : graph.recurrences()) {
            assertEquals(1, recurrence.length(), report);
            assertTrue(graph.instruction(recurrence.cycle().get(0)).text().matches("add (w|x)\\d+, (w|x)\\d+, #0x\\w+"), report);
        }
        assertTrue(new LongDependencyChainRule(Integer.MAX_VALUE, 2).apply(instructions, registers).isEmpty(), report);
    }

    static void encodeDeltas(int[] data) {
        int previous = data[0];
        for (int i = 1; i < data.length; i++) {
            int value = data[i];
            data[i] = value - previous;
            previous = value;
        }
    }

    static void decodeDeltas(int[] data) {
        int previous = data[0];
        for (int i = 1; i < data.length; i++) {
            previous += data[i];
            data[i] = previous;
        }
    }

    private static InstructionProfile profileDeltaDecoding() {
        return profile("decodeDeltas", DependencyChainTest::decodeDeltas);
    }

    // Profiles `kernel` for a second, each time on a fresh copy of the same deltas, and
    // returns the profile of the method named `name`.
    private static InstructionProfile profile(String name, java.util.function.Consumer<int[]> kernel) {
        int[] deltas = new int[1 << 16];
        for (int i = 0; i < deltas.length; i++) {
            deltas[i] = (i * 0x9E3779B9) >>> 25;
        }
        int[] data = new int[deltas.length];
        for (int i = 0; i < 2000; i++) {
            System.arraycopy(deltas, 0, data, 0, data.length);
            kernel.accept(data); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                System.arraycopy(deltas, 0, data, 0, data.length);
                kernel.accept(data);
            }
        } finally {
            Sampler.stop();
        }
        return CompiledCopies.hottestC2(name);
    }

    private static InstructionProfile profileArraysHashCode() {
        int[] data = new int[1 << 16];
        for (int i = 0; i < data.length; i++) {
            data[i] = i * 0x9E3779B9;
        }
        long sink = 0;
        for (int i = 0; i < 2000; i++) {
            sink += Arrays.hashCode(data); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += Arrays.hashCode(data);
            }
        } finally {
            Sampler.stop();
        }
        assertTrue(sink != 42);
        // java.util.Arrays.hashCode([I)I, or the ArraysSupport.hashCode it calls on newer JDKs.
        return CompiledCopies.hottestC2(".hashCode([I");
    }

    private static String describe(InstructionProfile profile, List<DependencyChain> chains) {
        return profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n"
                + chains.stream()
                        .map(chain -> "  " + chain.kind() + " " + chain.carriedRegister().orElse("") + " " + chain.length()
                                + " instructions, critical path " + chain.criticalPathLength() + ", " + chain.samples()
                                + " samples, " + chain.spanSamples() + " in span: "
                                + chain.instructions().stream().map(Instruction::text).collect(Collectors.joining("; ")))
                        .collect(Collectors.joining("\n"))
                + "\n" + listing(profile);
    }

    private static String listing(InstructionProfile profile) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
