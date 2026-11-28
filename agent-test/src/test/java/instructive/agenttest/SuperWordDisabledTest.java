package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import instructive.analyse.MissedVectorisation;
import instructive.parse.Instruction;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * With C2's vectoriser turned off ({@code -XX:-UseSuperWord}, set by the
 * {@code noSuperWordTest} task), a loop it would vectorise is unrolled into scalar
 * copies, which {@code MissedVectorisationRule} reports.
 */
@Tag("no-superword")
class SuperWordDisabledTest {

    @BeforeAll
    static void onlyOnASupportedArchitectureWithoutSuperWord() {
        MissedVectorisationTest.onlyOnASupportedArchitecture();
        assumeTrue(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                .contains("-XX:-UseSuperWord"), "needs -XX:-UseSuperWord");
    }

    @AfterEach
    void stopSampler() {
        Sampler.stop();
    }

    @Test
    void findsAnIntersectionThatWasNotVectorised() {
        InstructionProfile profile = MissedVectorisationTest.profileIntersect();
        List<MissedVectorisation> findings = MissedVectorisationTest.apply(profile);
        String report = MissedVectorisationTest.describe(profile, findings);
        assertEquals(1, findings.size(), report);
        MissedVectorisation found = findings.get(0);
        assertTrue(found.lanes() >= 2, report);
        assertEquals(8, found.elementSize(), report);
        assertEquals(List.of("and"), found.operations().stream().map(Instruction::mnemonic).toList(), report);
        // Each copy loads both words, ands them and stores the result. x86 folds one of the
        // loads into the and.
        List<String> copy = MissedVectorisationTest.X86 ? List.of("mov", "and", "mov") : List.of("ldr", "ldr", "and", "str");
        for (List<Instruction> instructions : found.copies()) {
            assertEquals(copy, instructions.stream().map(Instruction::mnemonic).toList(), report);
        }
        // Only the loop: the stubs after the code hold addresses, which can decode as SIMD.
        assertEquals(0, MissedVectorisationTest.vector(found.instructions()), report);
        assertTrue(found.samples() * 4 >= profile.sampled() * 3, report);
    }
}
