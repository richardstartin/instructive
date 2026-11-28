package instructive.analyse;

import instructive.parse.Instruction;
import instructive.parse.RegisterModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Flags long chains of data-dependent instructions. A long chain serialises execution:
 * however wide the CPU, each instruction must wait for the one before it. In a loop, a
 * chain carried from one iteration to the next (a recurrence) bounds the loop's speed
 * however much it is unrolled. Hashing ({@code h = 31 * h + x}) is the classic example.
 *
 * <p>Builds a control-flow-aware {@link DependencyGraph} and reports:
 * <ul>
 *   <li>loop-carried recurrences whose cycle is at least {@code minimumRecurrenceLength}
 *       instructions, keeping the longest where several overlap. The chain reported is
 *       everything the carried value depends on within an iteration, so the samples on
 *       its loads and address arithmetic count towards it too, and the stores writing
 *       out values it produces;</li>
 *   <li>maximal straight-line chains (ones no later instruction extends) of at least
 *       {@code minimumChainLength} instructions, unless all of their instructions are
 *       in a reported recurrence's chain: a chain from the first element's load into
 *       the cycle is the recurrence.</li>
 * </ul>
 * Findings come hottest first, by {@link DependencyChain#samples()}, then by critical path.
 */
public final class LongDependencyChainRule {

    /** The default minimum length of a straight-line chain to flag. */
    public static final int DEFAULT_MINIMUM_CHAIN_LENGTH = 16;
    /**
     * The default minimum length of a recurrence to flag: lower than for straight-line
     * chains, because a recurrence repeats on every iteration.
     */
    public static final int DEFAULT_MINIMUM_RECURRENCE_LENGTH = 8;

    private final int minimumChainLength;
    private final int minimumRecurrenceLength;

    public LongDependencyChainRule() {
        this(DEFAULT_MINIMUM_CHAIN_LENGTH, DEFAULT_MINIMUM_RECURRENCE_LENGTH);
    }

    public LongDependencyChainRule(int minimumChainLength, int minimumRecurrenceLength) {
        if (minimumChainLength < 2 || minimumRecurrenceLength < 1) {
            throw new IllegalArgumentException(
                    "minimum lengths too short: " + minimumChainLength + ", " + minimumRecurrenceLength);
        }
        this.minimumChainLength = minimumChainLength;
        this.minimumRecurrenceLength = minimumRecurrenceLength;
    }

    /**
     * The long dependency chains in {@code instructions}, which must be in address order,
     * with {@code samples} giving the samples taken on each instruction.
     */
    public List<DependencyChain> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> samples) {
        DependencyGraph graph = DependencyGraph.of(instructions, registers);
        ControlFlowGraph controlFlow = graph.controlFlow();
        long[] sampleCounts = new long[graph.size()];
        long[] before = new long[graph.size() + 1];
        for (int i = 0; i < graph.size(); i++) {
            sampleCounts[i] = samples.applyAsLong(graph.instruction(i));
            before[i + 1] = before[i] + sampleCounts[i];
        }

        List<DependencyChain> findings = new ArrayList<>();
        // Longest first, so a recurrence inside a longer one is recognised and dropped.
        List<DependencyGraph.Recurrence> recurrences = new ArrayList<>(graph.recurrences());
        recurrences.sort(Comparator.comparingInt(DependencyGraph.Recurrence::length).reversed());
        List<Set<Integer>> reported = new ArrayList<>();
        // Every reported recurrence's instructions: its cycle, and the loads and address
        // arithmetic feeding it.
        List<Set<Integer>> reportedInstructions = new ArrayList<>();
        for (DependencyGraph.Recurrence recurrence : recurrences) {
            Set<Integer> members = new HashSet<>(recurrence.cycle());
            if (recurrence.length() < minimumRecurrenceLength || reported.stream().anyMatch(r -> r.containsAll(members))) {
                continue;
            }
            reported.add(members);
            ControlFlowGraph.Loop loop = controlFlow.loops().get(recurrence.loop());
            long loopSamples = 0;
            for (int i = 0; i < graph.size(); i++) {
                if (loop.contains(controlFlow.blockOf(i))) {
                    loopSamples += sampleCounts[i];
                }
            }
            long header = graph.instruction(controlFlow.blocks().get(loop.header()).start()).address();
            int producer = recurrence.cycle().get(recurrence.length() - 1);
            List<Integer> chain = new ArrayList<>(graph.dependenciesWithin(producer, recurrence.loop()));
            chain.addAll(graph.storesOf(chain, recurrence.loop()));
            Collections.sort(chain);
            reportedInstructions.add(new HashSet<>(chain));
            findings.add(new DependencyChain(DependencyChain.Kind.LOOP_CARRIED, instructions(graph, chain),
                    instructions(graph, recurrence.cycle()), sum(sampleCounts, chain), loopSamples,
                    Optional.of(recurrence.register()), OptionalLong.of(header)));
        }

        for (int i = 0; i < graph.size(); i++) {
            if (graph.chainLength(i) < minimumChainLength || graph.isExtended(i)) {
                continue;
            }
            List<Integer> chain = graph.longestChainEndingAt(i);
            // A chain into a recurrence, such as the first element's load and then the
            // cycle, is part of the recurrence's finding.
            if (reportedInstructions.stream().anyMatch(r -> r.containsAll(chain))) {
                continue;
            }
            int first = chain.stream().mapToInt(Integer::intValue).min().orElseThrow();
            int last = chain.stream().mapToInt(Integer::intValue).max().orElseThrow();
            List<Instruction> path = instructions(graph, chain);
            findings.add(new DependencyChain(DependencyChain.Kind.STRAIGHT_LINE, path, path,
                    sum(sampleCounts, chain), before[last + 1] - before[first], Optional.empty(), OptionalLong.empty()));
        }
        findings.sort(Comparator.comparingLong(DependencyChain::samples).reversed()
                .thenComparing(Comparator.comparingInt(DependencyChain::criticalPathLength).reversed()));
        return findings;
    }

    /** {@link #apply(List, RegisterModel, ToLongFunction)} without samples. */
    public List<DependencyChain> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    private static List<Instruction> instructions(DependencyGraph graph, List<Integer> indexes) {
        List<Instruction> instructions = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            instructions.add(graph.instruction(index));
        }
        return instructions;
    }

    private static long sum(long[] counts, List<Integer> indexes) {
        long sum = 0;
        for (int index : indexes) {
            sum += counts[index];
        }
        return sum;
    }
}
