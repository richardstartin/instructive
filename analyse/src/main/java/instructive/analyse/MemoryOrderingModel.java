package instructive.analyse;

import instructive.analyse.aarch64.Aarch64MemoryOrderingModel;
import instructive.analyse.x86.X86MemoryOrderingModel;
import instructive.parse.Instruction;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which of one architecture's instructions order memory accesses or update memory
 * atomically, as {@link FetchInLoopRule} needs to know: what Java's volatile accesses,
 * {@code Atomic*} classes, {@code VarHandle}s and {@code LongAdder} compile to.
 */
public interface MemoryOrderingModel {

    /** What an instruction does to memory ordering. */
    enum Ordering {
        /** A load later accesses can't be reordered before: a volatile read, {@code ldar}. */
        ACQUIRE_LOAD,
        /** A store earlier accesses can't be reordered after: a volatile write, {@code stlr}. */
        RELEASE_STORE,
        /**
         * An atomic read-modify-write ({@code ldaddal}, {@code casal}, {@code lock xadd},
         * {@code lock cmpxchg}), or one half of a load/store-exclusive pair. It needs the
         * cache line exclusively, and usually orders accesses around it too.
         */
        ATOMIC_UPDATE,
        /** A barrier: {@code dmb ish}, {@code mfence}, or x86's locked add of 0 to the stack. */
        FENCE
    }

    /** What {@code instruction} does to memory ordering, or empty if it is an ordinary instruction. */
    Optional<Ordering> classify(Instruction instruction);

    /**
     * Whether a loop, given as its instructions in address order, is the hardware retry
     * loop of a single atomic update rather than a loop of the program: AArch64 without
     * LSE retries a load-exclusive and store-exclusive pair until the store succeeds.
     */
    boolean isRetryLoop(List<Instruction> loop);

    /** The model for an {@code os.arch} value, if there is one. */
    static Optional<MemoryOrderingModel> forArchitecture(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> Optional.of(new Aarch64MemoryOrderingModel());
            case "amd64", "x86_64" -> Optional.of(new X86MemoryOrderingModel());
            default -> Optional.empty();
        };
    }
}
