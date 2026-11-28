package instructive.analyse;

import instructive.analyse.VectorisationModel.Compute;
import instructive.analyse.VectorisationModel.Constant;
import instructive.analyse.VectorisationModel.Load;
import instructive.analyse.VectorisationModel.Memory;
import instructive.analyse.VectorisationModel.Move;
import instructive.analyse.VectorisationModel.Operand;
import instructive.analyse.VectorisationModel.Operation;
import instructive.analyse.VectorisationModel.Register;
import instructive.analyse.VectorisationModel.Store;
import instructive.analyse.VectorisationModel.Update;
import instructive.parse.Instruction;
import instructive.parse.RegisterModel;
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
import java.util.TreeSet;
import java.util.function.ToLongFunction;

/**
 * Flags hot loops that C2 unrolled but didn't vectorise: the unrolled copies do the
 * same scalar operations on adjacent elements, operations that have vector equivalents.
 * C2's superword pass packs such copies into vector instructions when it can prove them
 * independent. When it can't, for example because two arrays accessed at different
 * offsets might be the same array, it leaves the copies scalar.
 *
 * <p>Copies are found the way a superword pass seeds its packs, from the stores. For
 * each plain store in an innermost loop, it builds the expression the stored value is
 * computed by within the iteration: operations with vector equivalents (floating-point
 * arithmetic and conversions; integer add, subtract, logic, shifts and extensions, and
 * multiplies the architecture has vector forms of), down to loads (on x86, also the
 * memory operands folded into the operations) and to values defined outside the loop.
 * The {@link VectorisationModel} says which instructions are which. Stores whose
 * expressions are identical, operation by operation, immediates included, are copies
 * when:
 * <ul>
 *   <li>they store adjacent elements, as do the loads in each position of their
 *       expressions: their immediate offsets step by the element's size;</li>
 *   <li>they share no instructions, and none uses a value another computed, in this
 *       iteration or the one before, so the copies don't depend on each other;</li>
 *   <li>each loads at least one element.</li>
 * </ul>
 * A store whose expression reaches anything else (a value carried round the loop, a
 * value with more than one definition, an instruction without a vector equivalent such
 * as a 64-bit multiply or a division by an integer) has no copies. Loops containing
 * vector instructions are already vectorised and are left out, as are loops with less
 * than {@code minimumShare} of the method's samples (without samples, every loop is
 * considered), which also leaves out the scalar pre- and post-loops around a vectorised
 * loop.
 *
 * <p>Reductions, which carry the result round the loop instead of storing it, aren't
 * covered: C2 can't vectorise a floating-point sum without changing its rounding.
 *
 * <p>Findings come hottest first.
 */
public final class MissedVectorisationRule {

    /** The default share of the method's samples a loop needs before it is reported. */
    public static final double DEFAULT_MINIMUM_SHARE = 0.1;

    private final VectorisationModel model;
    private final double minimumShare;

    public MissedVectorisationRule(VectorisationModel model) {
        this(model, DEFAULT_MINIMUM_SHARE);
    }

    public MissedVectorisationRule(VectorisationModel model, double minimumShare) {
        if (!(minimumShare >= 0 && minimumShare <= 1)) {
            throw new IllegalArgumentException("minimum share not in [0, 1]: " + minimumShare);
        }
        this.model = Objects.requireNonNull(model);
        this.minimumShare = minimumShare;
    }

    /** Every loop in {@code instructions} with copies that weren't vectorised, ignoring samples. */
    public List<MissedVectorisation> apply(List<Instruction> instructions, RegisterModel registers) {
        return apply(instructions, registers, instruction -> 0);
    }

    /**
     * The hot loops in {@code instructions}, which must be in address order, whose
     * unrolled copies weren't vectorised: one finding for each set of copies.
     */
    public List<MissedVectorisation> apply(
            List<Instruction> instructions, RegisterModel registers, ToLongFunction<Instruction> sampleCount) {
        ControlFlowGraph controlFlow = ControlFlowGraph.of(instructions);
        DependencyGraph graph = DependencyGraph.of(controlFlow, registers);
        List<Instruction> code = controlFlow.instructions();
        long total = 0;
        for (Instruction instruction : code) {
            total += sampleCount.applyAsLong(instruction);
        }

        List<MissedVectorisation> findings = new ArrayList<>();
        for (int loop = 0; loop < controlFlow.loops().size(); loop++) {
            BitSet blocks = controlFlow.loops().get(loop).blocks();
            Set<Integer> body = new TreeSet<>();
            boolean innermost = true;
            for (int block = blocks.nextSetBit(0); block >= 0; block = blocks.nextSetBit(block + 1)) {
                innermost &= controlFlow.innermostLoop(block) == loop;
                ControlFlowGraph.BasicBlock basicBlock = controlFlow.blocks().get(block);
                for (int i = basicBlock.start(); i < basicBlock.end(); i++) {
                    body.add(i);
                }
            }
            if (!innermost) {
                continue;
            }
            long samples = 0;
            boolean vectorised = false;
            for (int i : body) {
                samples += sampleCount.applyAsLong(code.get(i));
                vectorised |= model.isVector(code.get(i));
            }
            if (vectorised || (total > 0 && samples < minimumShare * total)) {
                continue;
            }

            Map<String, List<Expression>> byShape = new LinkedHashMap<>();
            for (int i : body) {
                Expression expression = expression(graph, registers, body, i);
                if (expression != null) {
                    byShape.computeIfAbsent(expression.shape, shape -> new ArrayList<>()).add(expression);
                }
            }
            long header = code.get(controlFlow.blocks().get(controlFlow.loops().get(loop).header()).start()).address();
            for (List<Expression> candidates : byShape.values()) {
                List<Expression> copies = copies(graph, candidates);
                if (copies.isEmpty()) {
                    continue;
                }
                List<Instruction> instructionsInLoop = new ArrayList<>();
                for (int i : body) {
                    instructionsInLoop.add(code.get(i));
                }
                List<List<Instruction>> copyInstructions = new ArrayList<>();
                for (Expression copy : copies) {
                    copyInstructions.add(copy.nodes.stream().sorted().map(code::get).toList());
                }
                List<Instruction> operations = copies.get(0).operations.stream().sorted().map(code::get).toList();
                findings.add(new MissedVectorisation(header, instructionsInLoop, samples, copyInstructions, operations,
                        copies.get(0).stored.size()));
            }
        }
        findings.sort(Comparator.comparingLong(MissedVectorisation::samples).reversed()
                .thenComparingLong(MissedVectorisation::header)
                .thenComparingLong(finding -> finding.copies().get(0).get(0).address()));
        return findings;
    }

    /**
     * A store and the expression computing the value it stores within one iteration.
     *
     * @param stored     the element stored
     * @param shape      the expression as text, with registers replaced by what computes
     *                   them, so copies have the same shape
     * @param nodes      the store, its loads and operations, and the moves between them
     * @param loads      the elements loaded, in the order they appear in {@code shape}
     * @param operations the operations
     */
    private record Expression(
            int store, Memory stored, String shape, Set<Integer> nodes, List<Memory> loads, Set<Integer> operations) {}

    // The expression stored by instruction `i`, or null if it isn't a plain store of a
    // value computed in the loop from loads by vectorisable operations.
    private Expression expression(DependencyGraph graph, RegisterModel registers, Set<Integer> body, int i) {
        Optional<Operation> operation = model.describe(graph.instruction(i));
        Builder builder = new Builder(graph, registers, body);
        builder.nodes.add(i);
        Memory to;
        String value;
        if (operation.isPresent() && operation.get() instanceof Store store) {
            to = store.to();
            value = builder.shape(i, store.register());
        } else if (operation.isPresent() && operation.get() instanceof Update update) {
            // The store's value is the operation's, on the element it replaces among others.
            to = update.to();
            value = builder.compute(i, update.value());
        } else {
            return null;
        }
        if (builder.failed || builder.loads.isEmpty()) {
            return null;
        }
        String shape = to.shape() + " (" + value + ")";
        return new Expression(i, to, shape, builder.nodes, builder.loads, builder.operations);
    }

    private final class Builder {
        final DependencyGraph graph;
        final RegisterModel registers;
        final Set<Integer> body;
        final Set<Integer> nodes = new HashSet<>();
        final List<Memory> loads = new ArrayList<>();
        final Set<Integer> operations = new HashSet<>();
        boolean failed;

        Builder(DependencyGraph graph, RegisterModel registers, Set<Integer> body) {
            this.graph = graph;
            this.registers = registers;
            this.body = body;
        }

        // The shape of the value of `register` as instruction `user` reads it.
        String shape(int user, String register) {
            int writer = writer(user, register);
            if (writer == -1) {
                return "invariant " + register;
            }
            Optional<Operation> described = writer < 0 ? Optional.empty() : model.describe(graph.instruction(writer));
            if (described.isEmpty()) {
                failed = true;
                return "?";
            }
            nodes.add(writer);
            Operation operation = described.get();
            if (operation instanceof Load load) {
                loads.add(load.from());
                return load.from().shape();
            }
            if (operation instanceof Move move) {
                return shape(writer, move.source());
            }
            if (operation instanceof Compute compute) {
                return compute(writer, compute);
            }
            failed = true;
            return "?";
        }

        // The shape of the value instruction `i` computes with `compute`.
        String compute(int i, Compute compute) {
            operations.add(i);
            StringBuilder shape = new StringBuilder(compute.shape()).append(" (");
            for (int source = 0; source < compute.sources().size(); source++) {
                shape.append(source == 0 ? "" : ", ").append(shape(i, compute.sources().get(source)));
            }
            return shape.append(')').toString();
        }

        private String shape(int user, Operand operand) {
            if (operand instanceof Register source) {
                return "(" + shape(user, source.name()) + ")";
            }
            if (operand instanceof Memory memory) {
                loads.add(memory);
                return memory.shape();
            }
            return ((Constant) operand).shape();
        }

        // The one instruction in the loop that `register`'s value at `user` comes from
        // within the iteration; -1 if it comes from outside the loop, -2 if from more than
        // one instruction or from the previous iteration.
        int writer(int user, String register) {
            int found = -1;
            for (int writer : graph.dependencies(user)) {
                if (body.contains(writer) && registers.usage(graph.instruction(writer)).writes().contains(register)) {
                    if (found != -1) {
                        return -2;
                    }
                    found = writer;
                }
            }
            for (int writer : graph.loopCarriedDependencies(user)) {
                if (body.contains(writer) && registers.usage(graph.instruction(writer)).writes().contains(register)) {
                    return -2;
                }
            }
            return found;
        }
    }

    // The largest set of `candidates`, all of one shape, that are copies of each other,
    // in the order of the elements they store; empty if there are fewer than two.
    private static List<Expression> copies(DependencyGraph graph, List<Expression> candidates) {
        if (candidates.size() < 2) {
            return List.of();
        }
        List<Expression> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingLong(expression -> expression.stored.displacement()));
        // Adjacent elements: each access steps by its element's size from the copy before.
        for (int k = 1; k < sorted.size(); k++) {
            if (!adjacent(sorted.get(k - 1).stored, sorted.get(k).stored)) {
                return List.of();
            }
            for (int load = 0; load < sorted.get(k).loads.size(); load++) {
                if (!adjacent(sorted.get(k - 1).loads.get(load), sorted.get(k).loads.get(load))) {
                    return List.of();
                }
            }
        }
        // Independent: no shared instructions, and none reads a value another copy computed.
        Map<Integer, Expression> owner = new LinkedHashMap<>();
        for (Expression expression : sorted) {
            for (int node : expression.nodes) {
                if (owner.put(node, expression) != null) {
                    return List.of();
                }
            }
        }
        for (Expression expression : sorted) {
            for (int node : expression.nodes) {
                for (int[] writers : new int[][] {graph.dependencies(node), graph.loopCarriedDependencies(node)}) {
                    for (int writer : writers) {
                        Expression other = owner.get(writer);
                        if (other != null && other != expression) {
                            return List.of();
                        }
                    }
                }
            }
        }
        return sorted;
    }

    // Whether `next` is the element after `previous`, judging by their displacements.
    private static boolean adjacent(Memory previous, Memory next) {
        return next.size() == previous.size() && next.displacement() - previous.displacement() == next.size();
    }
}
