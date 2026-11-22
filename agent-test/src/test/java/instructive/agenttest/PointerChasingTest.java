package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;
import instructive.analyse.PointerChase;
import instructive.analyse.PointerChasingRule;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Finds the pointer chase in iterating a LinkedList, and none in an ArrayList. */
class PointerChasingTest {

    @BeforeAll
    static void onlyOnAarch64() {
        assumeTrue(System.getProperty("os.arch").equals("aarch64"), "the register model is for aarch64");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void findsTheChaseThroughALinkedList() {
        InstructionProfile profile = profile("sumLinked", new LinkedList<>(), PointerChasingTest::sumLinked);
        List<PointerChase> chases = apply(profile);
        String report = describe(profile, chases);
        assertEquals(1, chases.size(), report);
        PointerChase chase = chases.get(0);
        // node = node.next: each load on the cycle reads a field of the node the previous
        // one loaded. Its address register is written on the cycle (by the load before,
        // or by decoding its result).
        assertTrue(!chase.loads().isEmpty(), report);
        assertTrue(chase.cycle().stream().allMatch(i -> i.kind() == InstructionKind.LOAD
                || i.mnemonic().equals("lsl") || i.mnemonic().equals("mov") || i.mnemonic().equals("add")), report);
        // The nodes' items are loaded from them, and the Integers from those.
        assertTrue(chase.dereferences().size() >= 2 * chase.loads().size(), report);
        // The loop is where the method spends its time, and the chase is where the
        // samples in it land.
        assertTrue(chase.samples() * 10 >= profile.sampled() * 9, report);
        assertTrue(chase.chaseSamples() * 2 >= chase.samples(), report);
    }

    @Test
    void leavesAnArrayListAlone() {
        InstructionProfile profile = profile("sumArray", new ArrayList<>(), PointerChasingTest::sumArray);
        List<PointerChase> chases = apply(profile);
        assertTrue(chases.isEmpty(), describe(profile, chases));
    }

    static int sumLinked(List<Integer> list) {
        int sum = 0;
        for (int value : list) {
            sum += value;
        }
        return sum;
    }

    static int sumArray(List<Integer> list) {
        int sum = 0;
        for (int value : list) {
            sum += value;
        }
        return sum;
    }

    private interface Sum {
        int of(List<Integer> list);
    }

    private static List<PointerChase> apply(InstructionProfile profile) {
        List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
        return new PointerChasingRule().apply(code, new Aarch64RegisterModel(),
                instruction -> profile.count((int) (instruction.address() - profile.address())));
    }

    // Profiles `sum` over `list`, filled with 4,096 Integers, for a second and returns the
    // profile of the method named `name`. Each list type has its own method, so each is
    // compiled for one type.
    private static InstructionProfile profile(String name, List<Integer> list, Sum sum) {
        for (int i = 0; i < 1 << 12; i++) {
            list.add(i);
        }
        int sink = 0;
        for (int i = 0; i < 10000; i++) {
            sink += sum.of(list); // compile it
        }
        Sampler.resetCounts();
        assertTrue(Sampler.start());
        try {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                sink += sum.of(list);
            }
        } finally {
            Sampler.stop();
        }
        String method = "PointerChasingTest." + name + "(";
        assertTrue(sink != 42);
        return CompiledCopies.hottestC2(method);
    }

    private static String describe(InstructionProfile profile, List<PointerChase> chases) {
        StringBuilder report = new StringBuilder(profile.name() + " L" + profile.compLevel() + ", " + profile.sampled() + " samples\n");
        for (PointerChase chase : chases) {
            report.append(String.format("  loop at +0x%x via %s: %d samples, %d on the chase; cycle %s; %d dereferences%n",
                    chase.loopHeader() - profile.address(), chase.carriedRegister(), chase.samples(), chase.chaseSamples(),
                    chase.cycle().stream().map(Instruction::text).toList(), chase.dereferences().size()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            new InstructionProfileFormatter().allInstructions().format(profile, out);
        }
        return report + bytes.toString(StandardCharsets.UTF_8);
    }
}
