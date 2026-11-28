package instructive.analyse;

import instructive.analyse.MemoryOrderingModel.Ordering;
import instructive.parse.Instruction;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Flags hot loops with atomic updates, fences, or acquire loads and release stores in
 * them, as {@code Atomic*}, {@code LongAdder} and volatile fields accessed inside a loop
 * compile to. It reports where these instructions execute and the samples on them, not
 * whether the program needs them on every iteration.
 *
 * <p>The {@link MemoryOrderingModel} says which instructions are which. Each such access
 * belongs to the smallest loop containing it that isn't the hardware retry loop of the
 * access itself (an AArch64 load/store-exclusive pair without LSE); a loop is reported if
 * it has at least {@code minimumShare} of the method's samples, or always without
 * samples. Accesses outside loops aren't reported.
 *
 * <p>Each finding has the samples on the accesses, counting each access's next
 * instruction too, since that is where a stalled access's samples tend to land. Uncontended
 * on one thread, acquire loads and release stores are cheap on recent cores and have few
 * samples, while atomic updates have most of the loop's. On x86, volatile loads and
 * stores are plain moves, so only the atomic updates and fences show.
 *
 * <p>Findings come with the most samples on their accesses first, then the hottest loops.
 */
public final class FetchInLoopRule {

    /** The default share of the method's samples a loop needs before it is reported. */
    public static final double DEFAULT_MINIMUM_SHARE = 0.1;

    private final MemoryOrderingModel model;
    private final double minimumShare;

    public FetchInLoopRule(MemoryOrderingModel model) {
        this(model, DEFAULT_MINIMUM_SHARE);
    }

    public FetchInLoopRule(MemoryOrderingModel model, double minimumShare) {
        if (!(minimumShare >= 0 && minimumShare <= 1)) {
            throw new IllegalArgumentException("minimum share not in [0, 1]: " + minimumShare);
        }
        this.model = Objects.requireNonNull(model);
        this.minimumShare = minimumShare;
    }

    /** Every loop in {@code instructions} with atomic or ordered accesses, ignoring samples. */
    public List<FetchInLoop> apply(List<Instruction> instructions) {
        return apply(instructions, instruction -> 0);
    }

    /** The hot loops in {@code instructions}, which must be in address order, with atomic or ordered accesses. */
    public List<FetchInLoop> apply(List<Instruction> instructions, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        List<Instruction> code = controlFlow.instructions();
        long[] samples = new long[code.size()];
        long total = 0;
        for (int i = 0; i < code.size(); i++) {
            samples[i] = sampleCount.applyAsLong(code.get(i));
            total += samples[i];
        }

        // Each loop's instructions, and whether it's a retry loop.
        List<ControlFlowGraph.Loop> loops = controlFlow.loops();
        List<List<Integer>> bodies = new ArrayList<>();
        boolean[] retry = new boolean[loops.size()];
        for (int loop = 0; loop < loops.size(); loop++) {
            List<Integer> body = new ArrayList<>();
            BitSet blocks = loops.get(loop).blocks();
            for (int block = blocks.nextSetBit(0); block >= 0; block = blocks.nextSetBit(block + 1)) {
                ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
                for (int i = basicBlock.start(); i < basicBlock.end(); i++) {
                    body.add(i);
                }
            }
            body.sort(null);
            bodies.add(body);
            retry[loop] = model.isRetryLoop(body.stream().map(code::get).toList());
        }

        // Each access, in the smallest loop containing it that isn't its own retry loop.
        Map<Integer, List<Integer>> accessesByLoop = new LinkedHashMap<>();
        Map<Integer, Ordering> orderings = new LinkedHashMap<>();
        for (int i = 0; i < code.size(); i++) {
            Optional<Ordering> ordering = model.classify(code.get(i));
            if (ordering.isEmpty()) {
                continue;
            }
            int block = controlFlow.blockOf(i);
            int smallest = -1;
            for (int loop = 0; loop < loops.size(); loop++) {
                if (!retry[loop] && loops.get(loop).contains(block)
                        && (smallest == -1 || bodies.get(loop).size() < bodies.get(smallest).size())) {
                    smallest = loop;
                }
            }
            if (smallest >= 0) {
                accessesByLoop.computeIfAbsent(smallest, loop -> new ArrayList<>()).add(i);
                orderings.put(i, ordering.get());
            }
        }

        List<FetchInLoop> findings = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : accessesByLoop.entrySet()) {
            List<Integer> body = bodies.get(entry.getKey());
            long loopSamples = 0;
            for (int i : body) {
                loopSamples += samples[i];
            }
            if (total > 0 && loopSamples < minimumShare * total) {
                continue;
            }
            List<FetchInLoop.Access> accesses = new ArrayList<>();
            Set<Integer> counted = new HashSet<>();
            for (int i : entry.getValue()) {
                long accessSamples = samples[i] + (i + 1 < code.size() ? samples[i + 1] : 0);
                accesses.add(new FetchInLoop.Access(code.get(i), orderings.get(i), accessSamples));
                counted.add(i);
                if (i + 1 < code.size()) {
                    counted.add(i + 1);
                }
            }
            long accessSamples = counted.stream().mapToLong(i -> samples[i]).sum();
            long header = code.get(controlFlow.blocks().get(loops.get(entry.getKey()).header()).start()).address();
            findings.add(new FetchInLoop(header, body.stream().map(code::get).toList(), loopSamples, accesses, accessSamples));
        }
        findings.sort(Comparator.comparingLong(FetchInLoop::accessSamples).reversed()
                .thenComparing(Comparator.comparingLong(FetchInLoop::samples).reversed())
                .thenComparingLong(FetchInLoop::header));
        return findings;
    }
}
