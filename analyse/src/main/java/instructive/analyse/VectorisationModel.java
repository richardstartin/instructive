package instructive.analyse;

import instructive.analyse.aarch64.Aarch64VectorisationModel;
import instructive.analyse.x86.X86VectorisationModel;
import instructive.parse.Instruction;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What one architecture's instructions do to scalar data, as {@link MissedVectorisationRule}
 * needs to know it: which instructions are plain loads and stores of one element, which
 * move a value between registers, and which compute with an operation that has a vector
 * equivalent. Registers are named canonically, as the architecture's
 * {@link instructive.parse.RegisterModel} names them.
 */
public interface VectorisationModel {

    /** An operand an operation reads. */
    sealed interface Operand permits Register, Memory, Constant {}

    /** A register, by canonical name. */
    record Register(String name) implements Operand {}

    /**
     * One element in memory, addressed by registers and an immediate displacement.
     *
     * @param size         the element's size in bytes
     * @param displacement the immediate part of the address; adjacent elements addressed
     *                     from the same registers differ in it by {@code size}
     * @param shape        how it is accessed, for comparing copies: e.g. {@code ldr d}
     */
    record Memory(int size, long displacement, String shape) implements Operand {}

    /** A value that doesn't change from one iteration to the next, such as a constant in memory. */
    record Constant(String shape) implements Operand {}

    /** What an instruction does to scalar data. */
    sealed interface Operation permits Load, Store, Update, Move, Compute {}

    /** Loads one element into a register. */
    record Load(Memory from) implements Operation {}

    /**
     * Stores one register's value as one element.
     *
     * @param register the register stored, by canonical name
     */
    record Store(String register, Memory to) implements Operation {}

    /**
     * Replaces one element with the result of an operation on it, as x86's
     * {@code add dword ptr [rcx + 4*rdi + 0x10], edx} does: a load, an operation and a
     * store in one instruction.
     *
     * @param value the operation; {@code to} is among its sources
     */
    record Update(Memory to, Compute value) implements Operation {}

    /** Copies a register's value to another register unchanged. */
    record Move(String source) implements Operation {}

    /**
     * Computes a value with an operation that has a vector equivalent.
     *
     * @param shape   the instruction with its sources left out (its mnemonic, the
     *                destination's width, any immediates), for comparing copies
     * @param sources what it reads, in operand order: registers, elements in memory (on
     *                x86, loads folded into the operation) and constants
     */
    record Compute(String shape, List<Operand> sources) implements Operation {
        public Compute {
            sources = List.copyOf(sources);
        }
    }

    /**
     * What {@code instruction} does to scalar data, or empty if it is none of these
     * operations: a vector instruction, a load or store of anything but one element at an
     * immediate offset, an operation without a vector equivalent, a control transfer...
     */
    Optional<Operation> describe(Instruction instruction);

    /** Whether {@code instruction} operates on vectors: the loop it is in has been vectorised. */
    boolean isVector(Instruction instruction);

    /** The model for an {@code os.arch} value, if there is one. */
    static Optional<VectorisationModel> forArchitecture(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> Optional.of(new Aarch64VectorisationModel());
            case "amd64", "x86_64" -> Optional.of(new X86VectorisationModel());
            default -> Optional.empty();
        };
    }
}
