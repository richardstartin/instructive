package instructive.analyse.aarch64;

import instructive.analyse.MemoryOrderingModel;
import instructive.parse.Instruction;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link MemoryOrderingModel} for AArch64, over {@link instructive.parse.aarch64.Aarch64Disassembler}'s
 * mnemonics.
 *
 * <ul>
 *   <li>acquire loads: {@code ldar}, {@code ldapr} and their byte, halfword and unscaled
 *       forms, which C2 emits for volatile reads ({@code AtomicInteger.get()});</li>
 *   <li>release stores: {@code stlr} and its forms, for volatile writes
 *       ({@code AtomicBoolean.set});</li>
 *   <li>atomic updates: the LSE atomics, in every ordering ({@code ldaddal} for
 *       {@code getAndAdd}, {@code casal} for {@code compareAndSet}, {@code casl} for
 *       {@code LongAdder}'s release CAS, {@code swp}, {@code ldset}...), and the load- and
 *       store-exclusives C2 uses without LSE;</li>
 *   <li>fences: {@code dmb} with any option ({@code ish}, {@code ishld}, {@code ishst}).</li>
 * </ul>
 * A retry loop is one of at most {@link #RETRY_LOOP_LENGTH} instructions with a
 * load-exclusive and a store-exclusive: C2's {@code ldaxr}/{@code stlxr}/{@code cbnz}
 * sequence for an atomic update without LSE.
 */
public final class Aarch64MemoryOrderingModel implements MemoryOrderingModel {

    /** The most instructions a load/store-exclusive retry loop has. */
    public static final int RETRY_LOOP_LENGTH = 8;

    private static final Pattern LOAD_EXCLUSIVE = Pattern.compile("lda?xp?r?[bh]?");
    private static final Pattern STORE_EXCLUSIVE = Pattern.compile("stl?xp?r?[bh]?");
    private static final Pattern ACQUIRE_LOAD = Pattern.compile("ldapu?r[bh]?|ldar[bh]?|ldapurs[bhw]");
    private static final Pattern RELEASE_STORE = Pattern.compile("stl(u?r)[bh]?");
    private static final Pattern LSE = Pattern.compile(
            "(ld|st)(add|clr|eor|set|smax|smin|umax|umin)(a|l|al)?[bh]?|swp(a|l|al)?[bh]?|casp?(a|l|al)?[bh]?");

    @Override
    public Optional<Ordering> classify(Instruction instruction) {
        String mnemonic = instruction.mnemonic();
        if (LOAD_EXCLUSIVE.matcher(mnemonic).matches() || STORE_EXCLUSIVE.matcher(mnemonic).matches()
                || LSE.matcher(mnemonic).matches()) {
            return Optional.of(Ordering.ATOMIC_UPDATE);
        }
        if (ACQUIRE_LOAD.matcher(mnemonic).matches()) {
            return Optional.of(Ordering.ACQUIRE_LOAD);
        }
        if (RELEASE_STORE.matcher(mnemonic).matches()) {
            return Optional.of(Ordering.RELEASE_STORE);
        }
        if (mnemonic.equals("dmb")) {
            return Optional.of(Ordering.FENCE);
        }
        return Optional.empty();
    }

    @Override
    public boolean isRetryLoop(List<Instruction> loop) {
        return loop.size() <= RETRY_LOOP_LENGTH
                && loop.stream().anyMatch(instruction -> LOAD_EXCLUSIVE.matcher(instruction.mnemonic()).matches())
                && loop.stream().anyMatch(instruction -> STORE_EXCLUSIVE.matcher(instruction.mnemonic()).matches());
    }
}
