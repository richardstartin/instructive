package instructive.analyse;

import instructive.analyse.MemoryOrderingModel.Ordering;
import instructive.parse.Instruction;
import java.util.List;

/**
 * A hot loop that updates memory atomically or orders memory accesses on every iteration,
 * as {@code AtomicLong}, {@code AtomicInteger}, {@code AtomicBoolean}, {@code LongAdder}
 * and volatile fields compile to. Each atomic update has to own its cache line and, in
 * the orderings Java uses, waits for earlier stores to drain, so the CPU can't overlap one
 * iteration's update with the next. Contended across cores, each also costs a cache line
 * transfer, which timer samples can't distinguish from the uncontended cost.
 *
 * @param header         the address of the loop's first instruction
 * @param instructions   the loop's instructions, in address order
 * @param samples        the samples taken on them
 * @param accesses       the atomic and ordered accesses in the loop, in address order
 * @param accessSamples  the samples on the accesses and on the instructions just after
 *                       them, each counted once: a stalled instruction's samples often
 *                       land on the next one
 */
public record FetchInLoop(
        long header, List<Instruction> instructions, long samples, List<Access> accesses, long accessSamples) {

    /**
     * One atomic or ordered access.
     *
     * @param instruction the instruction
     * @param ordering    what it does to memory ordering
     * @param samples     the samples on it and on the instruction after it
     */
    public record Access(Instruction instruction, Ordering ordering, long samples) {}

    public FetchInLoop {
        instructions = List.copyOf(instructions);
        accesses = List.copyOf(accesses);
    }

    /** The accesses of one kind. */
    public List<Access> accesses(Ordering ordering) {
        return accesses.stream().filter(access -> access.ordering() == ordering).toList();
    }

    /** {@link #accessSamples()} as a share of {@link #samples()}, or 0 without samples. */
    public double accessShare() {
        return samples == 0 ? 0 : (double) accessSamples / samples;
    }
}
