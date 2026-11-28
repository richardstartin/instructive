package instructive.analyse.x86;

import instructive.analyse.VectorisationModel;
import instructive.parse.Instruction;
import instructive.parse.InstructionKind;
import instructive.parse.x86.X86RegisterModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link VectorisationModel} for x86-64, over {@link instructive.parse.x86.X86Disassembler}'s
 * Intel syntax and {@link X86RegisterModel}'s register names.
 *
 * <p>Operations with an SSE or AVX equivalent: scalar floating-point arithmetic, fused
 * multiply-adds and conversions (SSE and VEX forms), and integer add, subtract, logic,
 * shifts, extensions, {@code lea} and multiplies. Multiplies only on 32-bit registers and
 * narrower: a 64-bit multiply needs AVX-512DQ. An operand in memory, as x86 folds loads
 * into arithmetic ({@code mulsd xmm3, qword ptr [r11 + 8*r14 + 0x10]}), is a load of one
 * element; a RIP-relative one is a constant. A two-operand instruction's destination is
 * also its first source, and an integer operation on memory
 * ({@code add dword ptr [rcx + 4*rdi + 0x10], edx}) is an {@link Update} of the element.
 *
 * <p>A loop is vectorised if an instruction uses a {@code ymm} or {@code zmm} register,
 * accesses a whole vector in memory ({@code xmmword ptr}...), or is a packed operation on
 * {@code xmm} registers. Moves between registers ({@code movapd xmm3, xmm0},
 * {@code movq r11, xmm2}, which C2 uses for scalars and spills) and zeroing idioms
 * ({@code pxor xmm0, xmm0}) aren't.
 */
public final class X86VectorisationModel implements VectorisationModel {

    private static final Set<String> LOAD_MOVES = Set.of(
            "mov", "movzx", "movsx", "movsxd", "movsd", "movss", "vmovsd", "vmovss", "movq", "movd", "vmovq", "vmovd");
    private static final Set<String> STORE_MOVES = Set.of(
            "mov", "movsd", "movss", "vmovsd", "vmovss", "movq", "movd", "vmovq", "vmovd");
    private static final Set<String> REGISTER_MOVES = Set.of(
            "mov", "movapd", "movaps", "movsd", "movss", "vmovapd", "vmovaps", "movq", "movd", "vmovq", "vmovd",
            "movdqa", "movdqu", "vmovdqa", "vmovdqu", "movupd", "movups", "vmovupd", "vmovups");
    private static final Pattern FLOATING_POINT = Pattern.compile(
            "v?(add|sub|mul|div|sqrt|min|max)s[sd]|vf(n?m(add|sub))(132|213|231)s[sd]"
                    + "|v?cvtsi2s[sd]|v?cvts[sd]2s[sd]|v?cvtts[sd]2si");
    private static final Set<String> INTEGER = Set.of(
            "add", "sub", "and", "or", "xor", "neg", "not", "shl", "sal", "shr", "sar", "inc", "dec", "imul", "lea",
            "andn", "movzx", "movsx", "movsxd");
    // Integer operations x86 can apply to memory in place.
    private static final Set<String> UPDATES = Set.of(
            "add", "sub", "and", "or", "xor", "neg", "not", "shl", "sal", "shr", "sar", "inc", "dec");
    // Computing instructions that only write their destination.
    private static final Pattern WRITE_ONLY = Pattern.compile("lea|movzx|movsx|movsxd|v.*|andn|v?cvt.*");
    private static final Set<String> ZEROING = Set.of(
            "pxor", "xorps", "xorpd", "vpxor", "vpxord", "vpxorq", "vxorps", "vxorpd");

    private static final Pattern WORD = Pattern.compile("\\b[a-z][a-z0-9]*\\b");
    private static final Pattern MEMORY = Pattern.compile("(?:(byte|word|dword|qword|xmmword|ymmword|zmmword) ptr )?\\[([^\\]]*)\\]");
    private static final Pattern DISPLACEMENT = Pattern.compile("(0x[0-9a-f]+|\\d+)");
    private static final Pattern WIDE = Pattern.compile("\\b[yz]mm\\d+\\b|\\b(?:xmm|ymm|zmm)word ptr\\b");
    private static final Pattern XMM = Pattern.compile("\\bxmm\\d+\\b");

    @Override
    public Optional<Operation> describe(Instruction instruction) {
        if (!instruction.isDecoded() || instruction.mnemonic().contains(" ") || instruction.operands().contains("{")
                || WIDE.matcher(instruction.operands()).find()) {
            return Optional.empty();
        }
        String mnemonic = instruction.mnemonic();
        List<String> operands = X86RegisterModel.split(instruction.operands());
        if (operands.isEmpty()) {
            return Optional.empty();
        }
        String first = operands.get(0);
        if (operands.size() == 2) {
            String second = operands.get(1);
            if (X86RegisterModel.isMemory(first) && STORE_MOVES.contains(mnemonic)) {
                Optional<Operand> to = memory(first, mnemonic);
                String register = X86RegisterModel.canonical(second);
                return to.isPresent() && to.get() instanceof Memory memory && register != null
                        ? Optional.of(new Store(register, memory)) : Optional.empty();
            }
            if (X86RegisterModel.isMemory(second) && LOAD_MOVES.contains(mnemonic)) {
                Optional<Operand> from = memory(second, mnemonic);
                if (from.isPresent() && from.get() instanceof Memory memory) {
                    return Optional.of(new Load(memory));
                }
                // A constant, loaded each iteration.
                return from.map(constant -> new Compute(((Constant) constant).shape(), List.of()));
            }
            String target = X86RegisterModel.canonical(first);
            String source = X86RegisterModel.canonical(second);
            if (REGISTER_MOVES.contains(mnemonic) && target != null && source != null) {
                return Optional.of(new Move(source));
            }
        }
        if (X86RegisterModel.isMemory(first)) {
            return update(mnemonic, operands);
        }
        if (X86RegisterModel.canonical(first) == null) {
            return Optional.empty();
        }
        boolean integer = INTEGER.contains(mnemonic);
        if (!integer && !FLOATING_POINT.matcher(mnemonic).matches()) {
            return Optional.empty();
        }
        String width = width(first);
        if (mnemonic.equals("imul") && width.equals("q")) {
            return Optional.empty();
        }
        // The destination replaced by its width, each source by "_".
        List<Operand> sources = new ArrayList<>();
        StringBuilder shape = new StringBuilder(mnemonic).append(' ').append(width);
        // Fused multiply-adds accumulate into the destination.
        boolean readsDestination = mnemonic.startsWith("vf")
                || (!WRITE_ONLY.matcher(mnemonic).matches() && !(mnemonic.equals("imul") && operands.size() == 3));
        if (readsDestination) {
            sources.add(new Register(X86RegisterModel.canonical(first)));
            shape.append(" _");
        }
        for (int i = 1; i < operands.size(); i++) {
            String operand = operands.get(i);
            shape.append(", ");
            if (mnemonic.equals("lea")) {
                // An address computed, not loaded: its registers are the sources.
                Matcher words = WORD.matcher(operand);
                StringBuilder address = new StringBuilder();
                while (words.find()) {
                    String register = X86RegisterModel.canonical(words.group());
                    if (register == null) {
                        return Optional.empty();
                    }
                    sources.add(new Register(register));
                    words.appendReplacement(address, "_");
                }
                words.appendTail(address);
                shape.append(address);
            } else if (X86RegisterModel.isMemory(operand)) {
                Optional<Operand> memory = memory(operand, "");
                if (memory.isEmpty()) {
                    return Optional.empty();
                }
                sources.add(memory.get());
                shape.append('_');
            } else if (X86RegisterModel.canonical(operand) != null) {
                sources.add(new Register(X86RegisterModel.canonical(operand)));
                shape.append('_');
            } else {
                shape.append(operand);
            }
        }
        return Optional.of(new Compute(shape.toString(), sources));
    }

    // An integer operation on an element in memory: "add dword ptr [rcx + 4*rdi + 0x10], edx".
    private static Optional<Operation> update(String mnemonic, List<String> operands) {
        if (!UPDATES.contains(mnemonic)) {
            return Optional.empty();
        }
        Optional<Operand> to = memory(operands.get(0), "");
        if (to.isEmpty() || !(to.get() instanceof Memory memory)) {
            return Optional.empty();
        }
        List<Operand> sources = new ArrayList<>(List.of(memory));
        StringBuilder shape = new StringBuilder(mnemonic).append(' ').append(memory.shape()).append(" _");
        for (int i = 1; i < operands.size(); i++) {
            String operand = operands.get(i);
            String register = X86RegisterModel.canonical(operand);
            shape.append(", ");
            if (register != null) {
                sources.add(new Register(register));
                shape.append('_');
            } else {
                shape.append(operand);
            }
        }
        return Optional.of(new Update(memory, new Compute(shape.toString(), sources)));
    }

    @Override
    public boolean isVector(Instruction instruction) {
        String operands = instruction.operands();
        if (WIDE.matcher(operands).find()) {
            return true;
        }
        if (instruction.kind() != InstructionKind.SIMD || !XMM.matcher(operands).find()) {
            return false;
        }
        String mnemonic = instruction.mnemonic();
        List<String> split = X86RegisterModel.split(operands);
        boolean registersOnly = split.stream().noneMatch(X86RegisterModel::isMemory);
        if (REGISTER_MOVES.contains(mnemonic) && registersOnly) {
            return false;
        }
        return !(ZEROING.contains(mnemonic) && split.stream().distinct().count() == 1);
    }

    // One element in memory, or a constant if it's addressed relative to the instruction
    // pointer; empty if it's addressed another way (a segment) or isn't one element.
    private static Optional<Operand> memory(String operand, String mnemonic) {
        Matcher matcher = MEMORY.matcher(operand);
        if (!matcher.matches() || operand.contains(":")) {
            return Optional.empty();
        }
        String sizeName = matcher.group(1);
        int size = sizeName == null ? 0 : switch (sizeName) {
            case "byte" -> 1;
            case "word" -> 2;
            case "dword" -> 4;
            case "qword" -> 8;
            default -> 0;
        };
        if (size == 0) {
            return Optional.empty();
        }
        String shape = mnemonic.isEmpty() ? sizeName : mnemonic + " " + sizeName;
        String address = matcher.group(2);
        if (address.startsWith("rip")) {
            return Optional.of(new Constant("constant " + shape + " [" + address + "]"));
        }
        // "r11 + 8*r14 + 0x10": the displacement is the term that isn't a register.
        long displacement = 0;
        String[] terms = address.split(" ");
        for (int i = 0; i < terms.length; i++) {
            if (DISPLACEMENT.matcher(terms[i]).matches()) {
                long value = Long.decode(terms[i]);
                displacement = i > 0 && terms[i - 1].equals("-") ? -value : value;
            }
        }
        return Optional.of(new Memory(size, displacement, shape));
    }

    // A register's width, as a letter: q, d, w, b for general registers, x for xmm.
    private static String width(String register) {
        if (register.startsWith("xmm")) {
            return "x";
        }
        if (register.startsWith("r") && !register.matches("r\\d+[dwb]")) {
            return "q";
        }
        if (register.startsWith("e") || register.endsWith("d")) {
            return "d";
        }
        if (register.endsWith("l") || register.endsWith("b") || register.endsWith("h")) {
            return "b";
        }
        return "w";
    }
}
