package instructive.analyse.aarch64;

import instructive.analyse.VectorisationModel;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link VectorisationModel} for AArch64, over {@link instructive.parse.aarch64.Aarch64Disassembler}'s
 * text and {@link instructive.parse.aarch64.Aarch64RegisterModel}'s register names.
 *
 * <p>Operations with an Advanced SIMD equivalent: floating-point arithmetic and
 * conversions, and integer add, subtract, logic, shifts and extensions on any width.
 * Multiplies only on 32-bit registers: Advanced SIMD has no 64-bit multiply. Loads and
 * stores are the single-register forms with an immediate offset, or none. A loop is
 * vectorised if it has an instruction the disassembler classifies as SIMD, or loads or
 * stores a whole vector register.
 */
public final class Aarch64VectorisationModel implements VectorisationModel {

    private static final Set<String> VECTORISABLE = Set.of(
            "fadd", "fsub", "fmul", "fdiv", "fmadd", "fmsub", "fnmadd", "fnmsub", "fnmul", "fneg", "fabs",
            "fsqrt", "fmax", "fmin", "fmaxnm", "fminnm", "fcvt", "scvtf", "ucvtf", "fcvtzs", "fcvtzu",
            "frintn", "frintm", "frintp", "frintz", "frinta", "frinti", "frintx",
            "add", "sub", "neg", "and", "orr", "eor", "bic", "orn", "mvn", "lsl", "lsr", "asr",
            "sxtb", "sxth", "sxtw", "uxtb", "uxth");
    private static final Set<String> NARROW_ONLY = Set.of("mul", "madd", "msub", "mneg");
    private static final Set<String> MOVES = Set.of("mov", "fmov");
    private static final Set<String> LOADS = Set.of(
            "ldr", "ldur", "ldrb", "ldurb", "ldrh", "ldurh", "ldrsb", "ldursb", "ldrsh", "ldursh", "ldrsw", "ldursw");
    private static final Set<String> STORES = Set.of("str", "stur", "strb", "sturb", "strh", "sturh");

    private static final Set<InstructionKind> LOAD_OR_STORE = Set.of(InstructionKind.LOAD, InstructionKind.STORE);

    private static final Pattern REGISTER = Pattern.compile("\\b(?:[wxbhsdq]\\d+|[wx]zr|sp)\\b");
    // A memory operand with an immediate offset, or none: "[x11, #0x28]", "[x11]".
    private static final Pattern ADDRESS = Pattern.compile("\\[[wx]\\d+(?:, #(-?0x[0-9a-f]+|-?\\d+))?\\]$");

    @Override
    public Optional<Operation> describe(Instruction instruction) {
        String mnemonic = instruction.mnemonic();
        String operands = instruction.operands();
        if (LOADS.contains(mnemonic) || STORES.contains(mnemonic)) {
            Matcher address = ADDRESS.matcher(operands);
            int size = elementSize(instruction);
            List<String> registers = registers(operands);
            if (!address.find() || size == 0 || size == 16 || registers.isEmpty()) {
                return Optional.empty();
            }
            long displacement = address.group(1) == null ? 0 : Long.decode(address.group(1));
            Memory memory = new Memory(size, displacement, mnemonic + " " + operands.charAt(0));
            String data = registers.get(0);
            if (LOADS.contains(mnemonic)) {
                return Optional.of(new Load(memory));
            }
            return data.endsWith("zr") ? Optional.empty() : Optional.of(new Store(canonical(data), memory));
        }
        List<String> registers = registers(operands);
        if (MOVES.contains(mnemonic) && registers.size() == 2 && !operands.contains("#") && !registers.get(1).endsWith("zr")) {
            return Optional.of(new Move(canonical(registers.get(1))));
        }
        boolean vectorisable = VECTORISABLE.contains(mnemonic)
                || (NARROW_ONLY.contains(mnemonic) && operands.startsWith("w"));
        if (!vectorisable || registers.isEmpty() || registers.get(0).equals("sp")) {
            return Optional.empty();
        }
        // The text with the destination replaced by its width and each source by "_".
        StringBuilder shape = new StringBuilder(mnemonic).append(' ');
        List<Operand> sources = new ArrayList<>();
        Matcher matcher = REGISTER.matcher(operands);
        boolean destination = true;
        while (matcher.find()) {
            String token = matcher.group();
            String replacement;
            if (destination) {
                replacement = token.substring(0, 1);
                destination = false;
            } else if (token.endsWith("zr")) {
                replacement = token;
            } else if (token.equals("sp")) {
                return Optional.empty();
            } else {
                replacement = "_";
                sources.add(new Register(canonical(token)));
            }
            matcher.appendReplacement(shape, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(shape);
        return Optional.of(new Compute(shape.toString(), sources));
    }

    @Override
    public boolean isVector(Instruction instruction) {
        // Vector arithmetic, and loads and stores of whole vector registers ("ldr q16").
        return instruction.kind() == InstructionKind.SIMD || (LOAD_OR_STORE.contains(instruction.kind())
                && (instruction.operands().startsWith("q") || instruction.operands().startsWith("v")));
    }

    private static List<String> registers(String operands) {
        List<String> found = new ArrayList<>();
        Matcher matcher = REGISTER.matcher(operands);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    // The canonical name of a register, as Aarch64RegisterModel gives it.
    private static String canonical(String register) {
        return switch (register.charAt(0)) {
            case 'w', 'x' -> "x" + register.substring(1);
            case 'b', 'h', 's', 'd', 'q' -> "v" + register.substring(1);
            default -> register;
        };
    }

    // The size of the element a single-register load or store moves, or 0 if unknown.
    private static int elementSize(Instruction instruction) {
        String mnemonic = instruction.mnemonic();
        if (mnemonic.endsWith("sw")) {
            return 4;
        }
        if (mnemonic.endsWith("b")) {
            return 1;
        }
        if (mnemonic.endsWith("h")) {
            return 2;
        }
        String operands = instruction.operands();
        return switch (operands.isEmpty() ? ' ' : operands.charAt(0)) {
            case 'b' -> 1;
            case 'h' -> 2;
            case 'w', 's' -> 4;
            case 'x', 'd' -> 8;
            case 'q' -> 16;
            default -> 0;
        };
    }
}
