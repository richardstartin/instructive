package instructive.agenttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import instructive.agent.InstructionProfile;
import instructive.agent.Sampler;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Finds the C2 code of a kernel an analysis test profiles. The kernels are compiled by C2
 * only, and before their warm-up returns (compiler-directives.json), so their hottest
 * compiled copy is C2 code; this checks that, and says which copies there were if not.
 */
final class CompiledCopies {
    private static final int C2 = 4;

    private CompiledCopies() {}

    /**
     * A snapshot of the sampled compiled copy of the method whose name contains
     * {@code method} with the most samples, which must be C2 code.
     */
    static InstructionProfile hottestC2(String method) {
        List<InstructionProfile> copies = new ArrayList<>();
        // Hottest first.
        Sampler.forEachHottestMethod(Integer.MAX_VALUE, profile -> {
            if (profile.name().contains(method)) {
                copies.add(profile.snapshot());
            }
        });
        assertFalse(copies.isEmpty(), () -> "no samples of " + method);
        InstructionProfile hottest = copies.get(0);
        assertEquals(C2, hottest.compLevel(), () -> "the hottest copy of " + method + " isn't C2 code; copies: "
                + copies.stream()
                        .map(copy -> "tier " + copy.compLevel() + " #" + copy.compileId() + " " + copy.total() + " samples")
                        .collect(Collectors.joining(", ")));
        return hottest;
    }
}
