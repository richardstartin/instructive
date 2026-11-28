package instructive.parse.x86;

import instructive.parse.Instruction;
import instructive.parse.RegisterModel;
import instructive.parse.RegisterUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Register usage of instructions decoded by {@link X86Disassembler}, worked out from
 * their mnemonic and operands (Intel syntax, destination first).
 *
 * <p>Canonical names: {@code rax}-{@code r15} for general registers, whatever width they
 * are written at ({@code eax}, {@code ax}, {@code al} and {@code ah} are all {@code rax}),
 * {@code xmm0}-{@code xmm31} for vector registers (whether written {@code xmm},
 * {@code ymm} or {@code zmm}), and {@code k0}-{@code k7} for mask registers. The
 * instruction pointer, segment registers and x87 registers are left out.
 *
 * <p>Generally the first operand is written and read, as two-operand instructions
 * combine it with the source ({@code add rax, rbx}, {@code mulsd xmm0, xmm1}), and the
 * rest are read. The exceptions:
 * <ul>
 *   <li>moves, loads, {@code lea}, conversions to a general register, bit counts, and
 *       instructions with three or more operands (VEX and EVEX forms such as
 *       {@code vmulsd xmm0, xmm1, xmm2}, and {@code imul r, r/m, imm}) only write their
 *       destination; fused multiply-adds and other accumulating forms read it too;</li>
 *   <li>a write to an 8- or 16-bit register, a scalar {@code movss}/{@code movsd}
 *       between registers and an SSE scalar conversion only replace part of the
 *       destination, so they read it too;</li>
 *   <li>zeroing idioms ({@code xor eax, eax}, {@code pxor xmm0, xmm0}) only write;</li>
 *   <li>compares and tests only read, and {@code nop}s, whatever their operands, use
 *       nothing;</li>
 *   <li>a memory destination writes memory, not registers: the address registers and
 *       sources are read, and the sources are {@link RegisterUsage#stored() stored};</li>
 *   <li>implicit operands: {@code mul}/{@code div} and friends use {@code rax} and
 *       {@code rdx}, sign extensions of {@code rax}, string instructions {@code rsi},
 *       {@code rdi}, {@code rcx} and {@code rax}, {@code cmpxchg} {@code rax}, and
 *       {@code push}, {@code pop}, {@code call}, {@code ret} and {@code leave} the stack
 *       pointer.</li>
 * </ul>
 * Condition flags are not modelled, and neither are the registers a call clobbers.
 */
public final class X86RegisterModel implements RegisterModel {

    private static final Map<String, String> GENERAL = new HashMap<>();
    // 8- and 16-bit names, whose writes keep the rest of the register.
    private static final Set<String> PARTIAL = new HashSet<>();

    static {
        String[][] legacy = {
            {"rax", "eax", "ax", "al", "ah"}, {"rbx", "ebx", "bx", "bl", "bh"}, {"rcx", "ecx", "cx", "cl", "ch"},
            {"rdx", "edx", "dx", "dl", "dh"}, {"rsi", "esi", "si", "sil"}, {"rdi", "edi", "di", "dil"},
            {"rbp", "ebp", "bp", "bpl"}, {"rsp", "esp", "sp", "spl"},
        };
        for (String[] names : legacy) {
            for (int i = 0; i < names.length; i++) {
                GENERAL.put(names[i], names[0]);
                if (i >= 2) {
                    PARTIAL.add(names[i]);
                }
            }
        }
        for (int n = 8; n < 16; n++) {
            String name = "r" + n;
            GENERAL.put(name, name);
            GENERAL.put(name + "d", name);
            GENERAL.put(name + "w", name);
            GENERAL.put(name + "b", name);
            PARTIAL.add(name + "w");
            PARTIAL.add(name + "b");
        }
    }

    private static final Pattern VECTOR = Pattern.compile("[xyz]mm([0-9]|[12][0-9]|3[01])");
    private static final Pattern MASK = Pattern.compile("k[0-7]");
    private static final Pattern WORD = Pattern.compile("\\b[a-z][a-z0-9]*\\b");

    private static final Set<String> READ_ONLY = Set.of(
            "cmp", "test", "bt", "ucomiss", "ucomisd", "comiss", "comisd", "vucomiss", "vucomisd", "vcomiss",
            "vcomisd", "ptest", "vptest", "kortestb", "kortestw", "kortestd", "kortestq", "ktestb", "ktestw",
            "ktestd", "ktestq", "jmp", "prefetcht0", "prefetcht1", "prefetcht2", "prefetchnta", "prefetchw",
            "clflush", "clflushopt", "clwb");
    private static final Pattern WRITE_ONLY = Pattern.compile(
            "mov|movabs|movzx|movsx|movsxd|lea|movd|movq|movaps|movapd|movups|movupd|movdqa|movdqu|movntdqa|movnti"
                    + "|movntps|movntpd|movntdq|lddqu|popcnt|lzcnt|tzcnt|cvtt?s[sd]2si|pmovmskb|movmskp[sd]|pshuf[dhl]w?"
                    + "|pextr[bwdq]|set[a-z]+|k?mov[bwdq]?|cvtdq2p[sd]|cvtp[sd]2dq|cvttp[sd]2dq|cvtps2pd|cvtpd2ps"
                    + "|sqrtp[sd]|movshdup|movsldup|movddup|pmov[sz]x[bwdq]+");
    // Three-operand and VEX/EVEX forms that accumulate into their destination.
    private static final Pattern ACCUMULATES = Pattern.compile(
            "vf(n?m(add|sub)|maddsub|msubadd)\\d+[ps][sdh]|vpdp\\w+|vpmadd52\\w+|vpternlog[dq]|vperm[ti]2\\w+"
                    + "|vpshl?dv\\w+");
    private static final Set<String> ZEROING = Set.of(
            "xor", "sub", "pxor", "xorps", "xorpd", "vpxor", "vpxord", "vpxorq", "vxorps", "vxorpd", "psubd", "psubq");
    private static final Pattern STRING = Pattern.compile("(movs|stos|lods|cmps|scas)[bwdq]");

    @Override
    public RegisterUsage usage(Instruction instruction) {
        if (!instruction.isDecoded()) {
            return RegisterUsage.UNKNOWN;
        }
        String[] words = instruction.mnemonic().split(" ");
        String mnemonic = words[words.length - 1];
        boolean repeated = words.length > 1 && words[0].startsWith("rep");
        List<String> operands = split(instruction.operands());
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        Set<String> stored = new LinkedHashSet<>();

        // movsd and cmpsd are also SSE instructions; the string forms address es:[rdi].
        boolean sse = (mnemonic.equals("movsd") || mnemonic.equals("cmpsd")) && !instruction.operands().contains("es:[");
        if (STRING.matcher(mnemonic).matches() && !sse) {
            string(mnemonic.substring(0, 4), repeated, reads, writes, stored);
            return new RegisterUsage(reads, writes, stored, true);
        }
        switch (mnemonic) {
            // A multi-byte nop's memory operand is never accessed.
            case "nop" -> { }
            case "ret" -> {
                reads.add("rsp");
                writes.add("rsp");
            }
            case "call" -> {
                operands.forEach(operand -> registers(operand, reads));
                reads.add("rsp");
                writes.add("rsp");
            }
            case "push" -> {
                operands.forEach(operand -> registers(operand, reads));
                reads.add("rsp");
                writes.add("rsp");
            }
            case "pop" -> {
                destination(operands.get(0), reads, writes, false);
                reads.add("rsp");
                writes.add("rsp");
            }
            case "leave" -> {
                reads.add("rbp");
                writes.add("rbp");
                writes.add("rsp");
            }
            case "cdq", "cqo", "cwd" -> {
                reads.add("rax");
                writes.add("rdx");
            }
            case "cdqe", "cwde", "cbw" -> {
                reads.add("rax");
                writes.add("rax");
            }
            case "mul", "imul", "div", "idiv" -> {
                if (operands.size() == 1) {
                    registers(operands.get(0), reads);
                    reads.add("rax");
                    if (mnemonic.endsWith("div")) {
                        reads.add("rdx");
                    }
                    writes.add("rax");
                    writes.add("rdx");
                } else {
                    general(mnemonic, operands, reads, writes, stored);
                }
            }
            case "xchg", "xadd" -> {
                for (String operand : operands) {
                    registers(operand, reads);
                    if (!isMemory(operand)) {
                        registers(operand, writes);
                    }
                }
                if (isMemory(operands.get(0))) {
                    registers(operands.get(1), stored);
                }
            }
            case "cmpxchg" -> {
                operands.forEach(operand -> registers(operand, reads));
                reads.add("rax");
                writes.add("rax");
                if (isMemory(operands.get(0))) {
                    registers(operands.get(1), stored);
                } else {
                    registers(operands.get(0), writes);
                }
            }
            case "cmpxchg8b", "cmpxchg16b" -> {
                registers(operands.get(0), reads);
                reads.addAll(List.of("rax", "rdx", "rbx", "rcx"));
                writes.addAll(List.of("rax", "rdx"));
                stored.addAll(List.of("rbx", "rcx"));
            }
            case "rdtsc" -> writes.addAll(List.of("rax", "rdx"));
            case "rdtscp" -> writes.addAll(List.of("rax", "rdx", "rcx"));
            case "cpuid" -> {
                reads.addAll(List.of("rax", "rcx"));
                writes.addAll(List.of("rax", "rbx", "rcx", "rdx"));
            }
            default -> general(mnemonic, operands, reads, writes, stored);
        }
        return new RegisterUsage(reads, writes, stored, true);
    }

    private static void general(String mnemonic, List<String> operands, Set<String> reads, Set<String> writes, Set<String> stored) {
        if (operands.isEmpty() || mnemonic.startsWith("j") || READ_ONLY.contains(mnemonic)) {
            operands.forEach(operand -> registers(operand, reads));
            return;
        }
        String first = operands.get(0);
        if (isMemory(first)) {
            // Writes memory: everything is read, and the sources are stored.
            operands.forEach(operand -> registers(operand, reads));
            for (int i = 1; i < operands.size(); i++) {
                if (!isMemory(operands.get(i))) {
                    registerOperand(operands.get(i), stored);
                }
            }
            return;
        }
        if (zeroing(mnemonic, operands)) {
            registerOperand(first, writes);
            return;
        }
        boolean readsDestination;
        if (ACCUMULATES.matcher(mnemonic).matches()) {
            readsDestination = true;
        } else if (operands.size() >= 3 || mnemonic.startsWith("v") || WRITE_ONLY.matcher(mnemonic).matches()) {
            // Three-operand forms and VEX/EVEX write the whole destination; an SSE scalar
            // move between registers only replaces its low element.
            readsDestination = false;
        } else if ((mnemonic.equals("movsd") || mnemonic.equals("movss")) && !isMemory(operands.get(1))) {
            readsDestination = true;
        } else {
            // Two-operand forms combine the destination with the source, and SSE scalar
            // conversions, square roots and roundings write only its low element.
            readsDestination = !mnemonic.equals("movsd") && !mnemonic.equals("movss");
        }
        destination(first, reads, writes, readsDestination || mergingMask(first));
        for (int i = 1; i < operands.size(); i++) {
            registers(operands.get(i), reads);
        }
    }

    private static boolean zeroing(String mnemonic, List<String> operands) {
        if (!ZEROING.contains(mnemonic) || operands.size() < 2) {
            return false;
        }
        String register = operands.get(0);
        for (String operand : operands) {
            if (!operand.equals(register)) {
                return false;
            }
        }
        return true;
    }

    // A destination register, written, and read too if it's only partly written.
    private static void destination(String operand, Set<String> reads, Set<String> writes, boolean readsDestination) {
        if (isMemory(operand)) {
            registers(operand, reads);
            return;
        }
        String register = firstWord(operand);
        String name = canonical(register);
        if (name != null) {
            writes.add(name);
            if (readsDestination || PARTIAL.contains(register)) {
                reads.add(name);
            }
        }
        masks(operand, reads);
    }

    // Merge masking ("{k1}" without "{z}") keeps the masked-off elements of the destination.
    private static boolean mergingMask(String operand) {
        return operand.contains("{k") && !operand.contains("{z}");
    }

    private static void string(String operation, boolean repeated, Set<String> reads, Set<String> writes, Set<String> stored) {
        switch (operation) {
            case "movs", "cmps" -> {
                reads.addAll(List.of("rsi", "rdi"));
                writes.addAll(List.of("rsi", "rdi"));
            }
            case "stos" -> {
                reads.addAll(List.of("rdi", "rax"));
                writes.add("rdi");
                stored.add("rax");
            }
            case "lods" -> {
                reads.add("rsi");
                writes.addAll(List.of("rsi", "rax"));
            }
            default -> { // scas
                reads.addAll(List.of("rdi", "rax"));
                writes.add("rdi");
            }
        }
        if (repeated) {
            reads.add("rcx");
            writes.add("rcx");
        }
    }

    /**
     * The canonical name of a register as written in an operand ({@code r10d} is
     * {@code r10}, {@code ymm3} is {@code xmm3}), or null if it isn't a register this
     * model tracks.
     */
    public static String canonical(String register) {
        String general = GENERAL.get(register);
        if (general != null) {
            return general;
        }
        Matcher vector = VECTOR.matcher(register);
        if (vector.matches()) {
            return "xmm" + vector.group(1);
        }
        return MASK.matcher(register).matches() ? register : null;
    }

    /** Whether an operand, as {@link #split} gives it, addresses memory. */
    public static boolean isMemory(String operand) {
        return operand.contains("[");
    }

    // Splits at commas outside brackets and braces.
    public static List<String> split(String operands) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < operands.length(); i++) {
            char c = operands.charAt(i);
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(operands.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < operands.length()) {
            result.add(operands.substring(start).trim());
        }
        return result;
    }

    // Every register named in an operand: address registers, masks, the register itself.
    private static void registers(String operand, Set<String> into) {
        Matcher matcher = WORD.matcher(operand);
        while (matcher.find()) {
            String name = canonical(matcher.group());
            if (name != null) {
                into.add(name);
            }
        }
    }

    // A register operand's register, leaving out its mask.
    private static void registerOperand(String operand, Set<String> into) {
        String name = canonical(firstWord(operand));
        if (name != null) {
            into.add(name);
        }
    }

    private static void masks(String operand, Set<String> into) {
        int brace = operand.indexOf('{');
        if (brace >= 0) {
            registers(operand.substring(brace), into);
        }
    }

    private static String firstWord(String operand) {
        int end = 0;
        while (end < operand.length() && Character.isLetterOrDigit(operand.charAt(end))) {
            end++;
        }
        return operand.substring(0, end);
    }
}
