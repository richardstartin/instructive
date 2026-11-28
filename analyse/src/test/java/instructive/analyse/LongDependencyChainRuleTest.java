package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LongDependencyChainRuleTest {
    // C2's 8x unrolled loop for ArraysSupport.hashCode(int[]), h = 31 * h + a[i], on
    // AArch64, as bytes in memory order. Each element is lsl #5, sub, add on h.
    static final String[] HASH_LOOP = {
        "41 c9 22 8b", // add x1, x10, w2, sxtw #2
        "8b 68 1b 53", // lsl w11, w4, #5
        "2c 10 40 b9", // ldr w12, [x1, #0x10]
        "6b 01 04 4b", // sub w11, w11, w4
        "6c 01 0c 0b", // add w12, w11, w12
        "8e 69 1b 53", // lsl w14, w12, #5
        "2b 14 40 b9", // ldr w11, [x1, #0x14]
        "cc 01 0c 4b", // sub w12, w14, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8e 69 1b 53", // lsl w14, w12, #5
        "2b 18 40 b9", // ldr w11, [x1, #0x18]
        "cc 01 0c 4b", // sub w12, w14, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8b 69 1b 53", // lsl w11, w12, #5
        "2e 1c 40 b9", // ldr w14, [x1, #0x1c]
        "6c 01 0c 4b", // sub w12, w11, w12
        "8b 01 0e 0b", // add w11, w12, w14
        "6f 69 1b 53", // lsl w15, w11, #5
        "2c 20 40 b9", // ldr w12, [x1, #0x20]
        "eb 01 0b 4b", // sub w11, w15, w11
        "6c 01 0c 0b", // add w12, w11, w12
        "8f 69 1b 53", // lsl w15, w12, #5
        "2b 24 40 b9", // ldr w11, [x1, #0x24]
        "ec 01 0c 4b", // sub w12, w15, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8f 69 1b 53", // lsl w15, w12, #5
        "2b 28 40 b9", // ldr w11, [x1, #0x28]
        "ec 01 0c 4b", // sub w12, w15, w12
        "8c 01 0b 0b", // add w12, w12, w11
        "8f 69 1b 53", // lsl w15, w12, #5
        "2b 2c 40 b9", // ldr w11, [x1, #0x2c]
        "ec 01 0c 4b", // sub w12, w15, w12
        "42 20 00 11", // add w2, w2, #0x8
        "84 01 0b 0b", // add w4, w12, w11
        "5f 00 00 6b", // cmp w2, w0
        "ab fb ff 54", // b.lt (back to the first instruction, the loop's header)
    };

    private final Aarch64Disassembler disassembler = new Aarch64Disassembler();
    private final Aarch64RegisterModel registers = new Aarch64RegisterModel();

    @Test
    void findsTheHashLoopsRecurrence() {
        List<Instruction> loop = decode(HASH_LOOP);
        List<DependencyChain> chains = new LongDependencyChainRule().apply(loop, registers);
        // One finding: the loop-carried cycle. The straight-line chain through the same
        // instructions is part of it, so it isn't reported separately.
        assertEquals(1, chains.size(), () -> describe(chains));
        DependencyChain chain = chains.get(0);
        assertEquals(DependencyChain.Kind.LOOP_CARRIED, chain.kind());
        assertEquals(Optional.of("x4"), chain.carriedRegister());
        assertEquals(OptionalLong.of(0x1000), chain.loopHeader());
        // The critical path is lsl, sub, add for each of the 8 elements: from the h carried
        // in from the previous iteration to the h carried into the next.
        List<Instruction> path = chain.criticalPath();
        assertEquals(24, path.size(), () -> describe(chains));
        assertEquals("lsl w11, w4, #5", path.get(0).text());
        assertEquals("add w4, w12, w11", path.get(path.size() - 1).text());
        List<String> mnemonics = path.stream().map(Instruction::mnemonic).collect(Collectors.toList());
        for (int i = 0; i < mnemonics.size(); i += 3) {
            assertEquals(List.of("lsl", "sub", "add"), mnemonics.subList(i, i + 3), mnemonics::toString);
        }
        // Walking back from add w4 through every operand also reaches the 8 loads and
        // the address computation they share, but not the loop counter or the branch.
        assertEquals(33, chain.length(), () -> describe(chains));
        assertEquals(8, chain.instructions().stream().filter(i -> i.mnemonic().equals("ldr")).count());
        assertEquals("add x1, x10, w2, sxtw #2", chain.instructions().get(0).text());
        assertTrue(chain.instructions().stream().noneMatch(i -> i.mnemonic().equals("cmp") || i.mnemonic().startsWith("b.")));
    }

    @Test
    void buildsTheHashLoopsControlFlow() {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(decode(HASH_LOOP));
        // The whole loop is one block, ending in the back-edge to itself.
        assertEquals(1, controlFlow.blocks().size());
        assertEquals(List.of(0), controlFlow.blocks().get(0).successors());
        assertEquals(1, controlFlow.loops().size());
        assertEquals(0, controlFlow.loops().get(0).header());
        assertTrue(controlFlow.isRetreating(0, 0));
        DependencyGraph graph = DependencyGraph.of(controlFlow, registers);
        // lsl w11, w4, #5 reads the h from the previous iteration's final add.
        assertArrayEquals(new int[0], graph.dependencies(1));
        assertArrayEquals(new int[] {HASH_LOOP.length - 3}, graph.loopCarriedDependencies(1));
    }

    @Test
    void followsControlFlowNotLayout() {
        List<Instruction> instructions = decode(
                "65 00 00 b4", // 0x1000 cbz x5, 0x100c
                "20 04 00 91", // 0x1004 add x0, x1, #0x1
                "02 00 00 14", // 0x1008 b 0x1010
                "40 04 00 91", // 0x100c add x0, x2, #0x1
                "03 04 00 91"); // 0x1010 add x3, x0, #0x1
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        // Either branch's x0 can reach the join, not just the one laid out closest.
        assertArrayEquals(new int[] {1, 3}, graph.dependencies(4));
        assertEquals(List.of(), graph.controlFlow().loops());
    }

    @Test
    void separatesLoopCarriedFromWithinIterationDependencies() {
        List<Instruction> instructions = decode(
                "20 04 00 91", // 0x1000 add x0, x1, #0x1
                "01 04 00 91", // 0x1004 add x1, x0, #0x1
                "3f 00 02 eb", // 0x1008 cmp x1, x2
                "ab ff ff 54"); // 0x100c b.lt 0x1000
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        // add x1 uses this iteration's x0; add x0 uses the previous iteration's x1.
        assertArrayEquals(new int[] {0}, graph.dependencies(1));
        assertArrayEquals(new int[0], graph.loopCarriedDependencies(1));
        assertArrayEquals(new int[0], graph.dependencies(0));
        assertArrayEquals(new int[] {1}, graph.loopCarriedDependencies(0));
        assertEquals(1, graph.recurrences().stream().filter(r -> r.register().equals("x1")).count());
        assertEquals(2, graph.recurrences().stream().filter(r -> r.register().equals("x1")).findFirst().orElseThrow().length());
    }

    @Test
    void reportsRecurrencesFromTheirThreshold() {
        // Eight dependent adds carried round a loop: reported. The counter's
        // one-instruction recurrence isn't.
        List<String> code = new ArrayList<>(List.of(repeat("00 04 00 91", 8))); // add x0, x0, #0x1
        code.add("21 04 00 91"); // add x1, x1, #0x1
        code.add("3f 00 02 eb"); // cmp x1, x2
        code.add("cb fe ff 54"); // b.lt 0x1000
        List<Instruction> instructions = decode(code.toArray(new String[0]));
        List<DependencyChain> chains = new LongDependencyChainRule().apply(instructions, registers);
        assertEquals(1, chains.size(), () -> describe(chains));
        assertEquals(DependencyChain.Kind.LOOP_CARRIED, chains.get(0).kind());
        assertEquals(Optional.of("x0"), chains.get(0).carriedRegister());
        assertEquals(8, chains.get(0).criticalPathLength());
        assertEquals(8, chains.get(0).length());
    }

    @Test
    void linksEachReadToTheClosestWriter() {
        List<Instruction> instructions = decode(
                "20 04 00 91", // add x0, x1, #0x1
                "40 04 00 91", // add x0, x2, #0x1
                "00 04 00 91"); // add x0, x0, #0x1
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        // The last add depends on the second, which overwrote x0, not the first.
        assertArrayEquals(new int[] {1}, graph.dependencies(2));
        assertEquals(1, graph.chainLength(0));
        assertEquals(1, graph.chainLength(1));
        assertEquals(2, graph.chainLength(2));
        assertEquals(List.of(1, 2), graph.longestChainEndingAt(2));
    }

    @Test
    void independentInstructionsDoNotChain() {
        List<Instruction> instructions = decode(
                "20 04 00 91", // add x0, x1, #0x1
                "62 04 00 91", // add x2, x3, #0x1
                "a4 04 00 91", // add x4, x5, #0x1
                "e6 04 00 91"); // add x6, x7, #0x1
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        for (int i = 0; i < graph.size(); i++) {
            assertEquals(1, graph.chainLength(i));
        }
        assertEquals(List.of(), new LongDependencyChainRule(2, 1).apply(instructions, registers));
    }

    @Test
    void followsBaseRegisterWriteback() {
        // ldr x0, [x1], #0x8 four times: each depends on the previous update of x1.
        List<Instruction> instructions = decode("20 84 40 f8", "20 84 40 f8", "20 84 40 f8", "20 84 40 f8");
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        assertEquals(4, graph.chainLength(3));
    }

    @Test
    void undecodedInstructionsDontEndControlFlow() {
        // An instruction the disassembler doesn't know, at the start of the hash loop's
        // method, mustn't make the loop unreachable.
        String[] withUnknown = new String[HASH_LOOP.length + 1];
        withUnknown[0] = "00 00 00 02"; // 0x02000000, in an unallocated encoding space
        System.arraycopy(HASH_LOOP, 0, withUnknown, 1, HASH_LOOP.length);
        List<Instruction> instructions = decode(withUnknown);
        assertFalse(instructions.get(0).isDecoded());
        assertEquals(InstructionKind.UNKNOWN, instructions.get(0).kind());
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        assertEquals(1, controlFlow.loops().size());
        assertEquals(1, new LongDependencyChainRule().apply(instructions, registers).size());
    }

    @Test
    void undecodedInstructionsBreakChains() {
        List<Instruction> instructions = decode(
                "00 04 00 91", // add x0, x0, #0x1
                "00 04 00 91", // add x0, x0, #0x1
                "31 5a 20 0e", // cnt.8b: vector SIMD, not decoded
                "00 04 00 91", // add x0, x0, #0x1
                "00 04 00 91"); // add x0, x0, #0x1
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        assertEquals(2, graph.chainLength(1));
        assertEquals(2, graph.chainLength(4));
    }

    @Test
    void countsSamplesOnARecurrencesInputs() {
        // All the samples on the loads feeding the hash loop's chain: they count.
        List<Instruction> loop = decode(HASH_LOOP);
        DependencyChain chain = new LongDependencyChainRule()
                .apply(loop, registers, instruction -> instruction.mnemonic().equals("ldr") ? 3 : 0)
                .get(0);
        assertEquals(24, chain.samples());
        assertEquals(24, chain.spanSamples());
    }

    // C2's 8x unrolled loop for delta decoding, previous += data[i]; data[i] = previous;
    // the running value is carried in w14.
    static final String[] DELTA_LOOP = {
        "4b c9 30 8b", // add x11, x10, w16, sxtw #2
        "6d 11 40 b9", // ldr w13, [x11, #0x10]
        "cc 01 0d 0b", // add w12, w14, w13
        "6c 11 00 b9", // str w12, [x11, #0x10]
        "6e 15 40 b9", // ldr w14, [x11, #0x14]
        "cc 01 0c 0b", // add w12, w14, w12
        "6c 15 00 b9", // str w12, [x11, #0x14]
        "6e 19 40 b9", // ldr w14, [x11, #0x18]
        "cc 01 0c 0b", // add w12, w14, w12
        "6c 19 00 b9", // str w12, [x11, #0x18]
        "6e 1d 40 b9", // ldr w14, [x11, #0x1c]
        "cc 01 0c 0b", // add w12, w14, w12
        "6c 1d 00 b9", // str w12, [x11, #0x1c]
        "6e 21 40 b9", // ldr w14, [x11, #0x20]
        "cc 01 0c 0b", // add w12, w14, w12
        "6c 21 00 b9", // str w12, [x11, #0x20]
        "6e 25 40 b9", // ldr w14, [x11, #0x24]
        "cd 01 0c 0b", // add w13, w14, w12
        "6d 25 00 b9", // str w13, [x11, #0x24]
        "6e 29 40 b9", // ldr w14, [x11, #0x28]
        "cc 01 0d 0b", // add w12, w14, w13
        "6c 29 00 b9", // str w12, [x11, #0x28]
        "6e 2d 40 b9", // ldr w14, [x11, #0x2c]
        "ce 01 0c 0b", // add w14, w14, w12
        "10 22 00 11", // add w16, w16, #0x8
        "6e 2d 00 b9", // str w14, [x11, #0x2c]
        "1f 02 02 6b", // cmp w16, w2
        "ab fc ff 54", // b.lt (to the loop start)
    };

    @Test
    void includesTheStoresOfValuesTheChainProduces() {
        DependencyChain chain = new LongDependencyChainRule().apply(decode(DELTA_LOOP), registers).get(0);
        assertEquals(DependencyChain.Kind.LOOP_CARRIED, chain.kind());
        assertEquals("x14", chain.carriedRegister().orElseThrow());
        assertEquals(8, chain.criticalPathLength());
        // The address computation, 8 loads, 8 adds and the 8 stores writing out each
        // decoded value; not the loop counter, the compare or the branch.
        List<String> mnemonics = chain.instructions().stream().map(Instruction::mnemonic).collect(Collectors.toList());
        assertEquals(25, chain.length(), mnemonics::toString);
        assertEquals(8, mnemonics.stream().filter("str"::equals).count());
        assertTrue(chain.instructions().stream().noneMatch(i -> i.text().equals("add w16, w16, #0x8")));
    }

    @Test
    void storesJoinByTheValueStoredNotTheAddress() {
        // Store the loop counter instead of the first decoded value: x11, from the chain's
        // address computation, still addresses it, but what it writes out isn't the chain's.
        String[] loop = DELTA_LOOP.clone();
        loop[3] = "70 11 00 b9"; // str w16, [x11, #0x10]
        List<Instruction> instructions = decode(loop);
        assertEquals("str w16, [x11, #0x10]", instructions.get(3).text());
        DependencyChain chain = new LongDependencyChainRule().apply(instructions, registers).get(0);
        assertEquals(24, chain.length());
        assertTrue(chain.instructions().stream().noneMatch(i -> i.text().equals("str w16, [x11, #0x10]")));
    }

    @Test
    void ordersHottestFirst() {
        List<String> code = new ArrayList<>();
        code.addAll(List.of(repeat("00 04 00 91", 10))); // add x0, x0, #0x1: a chain of 10
        code.addAll(List.of(repeat("21 04 00 91", 12))); // add x1, x1, #0x1: a chain of 12
        List<Instruction> instructions = decode(code.toArray(new String[0]));
        // Put more samples on the shorter chain: it comes first.
        Map<Boolean, Long> weight = Map.of(true, 5L, false, 1L);
        List<DependencyChain> chains = new LongDependencyChainRule(8, 8)
                .apply(instructions, registers, instruction -> weight.get(instruction.operands().startsWith("x0")));
        assertEquals(2, chains.size());
        assertEquals(10, chains.get(0).length());
        assertEquals(50, chains.get(0).samples());
        assertEquals(50, chains.get(0).spanSamples());
        assertEquals(12, chains.get(1).length());
        assertEquals(12, chains.get(1).samples());
    }

    @Test
    void countsSamplesSkiddedOntoNearbyInstructions() {
        // A chain through x0, interleaved with independent instructions on x5.
        List<Instruction> instructions = decode(
                "00 04 00 91", // add x0, x0, #0x1
                "a5 04 00 91", // add x5, x5, #0x1
                "00 04 00 91", // add x0, x0, #0x1
                "a5 04 00 91", // add x5, x5, #0x1
                "00 04 00 91"); // add x0, x0, #0x1
        // All the samples land on the instructions between the chain's.
        List<DependencyChain> chains = new LongDependencyChainRule(3, 3).apply(
                instructions, registers, instruction -> instruction.operands().startsWith("x5") ? 7 : 0);
        DependencyChain chain = chains.stream().filter(c -> c.instructions().get(0).operands().startsWith("x0")).findFirst().orElseThrow();
        assertEquals(3, chain.length());
        assertEquals(0, chain.samples());
        assertEquals(14, chain.spanSamples());
    }

    @Test
    void ranksTheRecurrenceAboveTheChainLeadingIntoIt() {
        // Arrays.hashCode's unrolled loop, from a CI run where every sample landed on an
        // instruction that both the recurrence and the straight-line chain into it (the
        // first element's address and load, then the cycle) contain. The chain is two
        // instructions longer than the cycle, so on a tie in samples it came first.
        Listing method = Listing.load("hash-code-tied.tsv");
        List<DependencyChain> chains = new LongDependencyChainRule().apply(method.code(), registers, method::count);
        assertFalse(chains.isEmpty());
        DependencyChain hottest = chains.get(0);
        assertEquals(DependencyChain.Kind.LOOP_CARRIED, hottest.kind(), describe(chains));
        assertEquals(24, hottest.criticalPathLength(), describe(chains));
        assertEquals(81, hottest.samples(), describe(chains));
    }

    @Test
    void rejectsChainsShorterThanTwo() {
        assertThrows(IllegalArgumentException.class, () -> new LongDependencyChainRule(1, 8));
        assertThrows(IllegalArgumentException.class, () -> new LongDependencyChainRule(16, 0));
    }

    private static String[] repeat(String bytes, int times) {
        String[] repeated = new String[times];
        Arrays.fill(repeated, bytes);
        return repeated;
    }

    // Instructions from their bytes in memory order, laid out consecutively from 0x1000.
    private List<Instruction> decode(String... instructions) {
        byte[] code = new byte[instructions.length * 4];
        for (int i = 0; i < instructions.length; i++) {
            String[] bytes = instructions[i].split(" ");
            for (int b = 0; b < 4; b++) {
                code[i * 4 + b] = (byte) Integer.parseInt(bytes[b], 16);
            }
        }
        return disassembler.decodeAll(code, 0x1000);
    }

    private static String describe(List<DependencyChain> chains) {
        return chains.stream()
                .map(chain -> chain.kind() + " " + chain.length() + ": " + chain.instructions().stream().map(Instruction::text).collect(Collectors.joining("; ")))
                .collect(Collectors.joining("\n"));
    }
}
