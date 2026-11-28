package instructive.analyse.x86;

import instructive.analyse.MemoryOrderingModel;
import instructive.parse.Instruction;
import instructive.parse.x86.X86RegisterModel;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link MemoryOrderingModel} for x86-64, over {@link instructive.parse.x86.X86Disassembler}'s
 * Intel syntax.
 *
 * <ul>
 *   <li>atomic updates: {@code lock}-prefixed instructions ({@code lock xadd} for
 *       {@code getAndAdd}, {@code lock cmpxchg} for {@code compareAndSet} and
 *       {@code LongAdder}, {@code lock add} when the result isn't used) and {@code xchg}
 *       with memory, which locks without a prefix ({@code getAndSet});</li>
 *   <li>fences: {@code mfence}, and a locked add or or of 0 to the stack
 *       ({@code lock add dword ptr [rsp - 0x40], 0x0}), which is how HotSpot orders a
 *       volatile store before later loads.</li>
 * </ul>
 * x86 orders loads and stores more strongly than AArch64 (TSO), so volatile reads and
 * plain-ordered volatile writes are ordinary {@code mov}s: there are no acquire loads or
 * release stores to see, and C2 drops the fence after a volatile store when nothing
 * needs it before the next one. No x86 loop is a retry loop.
 */
public final class X86MemoryOrderingModel implements MemoryOrderingModel {

    // "lock add dword ptr [rsp - 0x40], 0x0": a full fence, not an update of anything.
    private static final Pattern STACK_FENCE = Pattern.compile("(add|or) \\w+ ptr \\[rsp(?: [+-] 0x[0-9a-f]+)?\\], 0x0");

    @Override
    public Optional<Ordering> classify(Instruction instruction) {
        String mnemonic = instruction.mnemonic();
        if (mnemonic.equals("mfence")) {
            return Optional.of(Ordering.FENCE);
        }
        if (mnemonic.startsWith("lock ")) {
            String operation = mnemonic.substring("lock ".length());
            return Optional.of(STACK_FENCE.matcher(operation + " " + instruction.operands()).matches()
                    ? Ordering.FENCE : Ordering.ATOMIC_UPDATE);
        }
        if (mnemonic.equals("xchg") && X86RegisterModel.split(instruction.operands()).stream().anyMatch(X86RegisterModel::isMemory)) {
            return Optional.of(Ordering.ATOMIC_UPDATE);
        }
        return Optional.empty();
    }

    @Override
    public boolean isRetryLoop(List<Instruction> loop) {
        return false;
    }
}
