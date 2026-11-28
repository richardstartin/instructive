package instructive.parse.x86;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import instructive.parse.Instruction;
import instructive.parse.RegisterUsage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class X86RegisterModelTest {
    private static final Pattern CANONICAL = Pattern.compile(
            "r(ax|bx|cx|dx|si|di|bp|sp|8|9|1[0-5])|xmm([0-9]|[12][0-9]|3[01])|k[0-7]");

    private final X86Disassembler disassembler = new X86Disassembler();
    private final X86RegisterModel registers = new X86RegisterModel();

    @Test
    void twoOperandInstructionsReadAndWriteTheDestination() {
        assertUsage("48 01 d8", "add rax, rbx", Set.of("rax", "rbx"), Set.of("rax"));
        // Every width of a register is the same register.
        assertUsage("41 83 c2 04", "add r10d, 0x4", Set.of("r10"), Set.of("r10"));
        assertUsage("2b 4c fe 10", "sub ecx, dword ptr [rsi + 8*rdi + 0x10]", Set.of("rcx", "rsi", "rdi"), Set.of("rcx"));
        assertUsage("48 0f af c3", "imul rax, rbx", Set.of("rax", "rbx"), Set.of("rax"));
        assertUsage("48 d3 e0", "shl rax, cl", Set.of("rax", "rcx"), Set.of("rax"));
        assertUsage("0f 4c c3", "cmovl eax, ebx", Set.of("rax", "rbx"), Set.of("rax"));
        assertUsage("f2 43 0f 59 5c f3 10", "mulsd xmm3, qword ptr [r11 + 8*r14 + 0x10]",
                Set.of("xmm3", "r11", "r14"), Set.of("xmm3"));
        assertUsage("f2 0f 58 d9", "addsd xmm3, xmm1", Set.of("xmm3", "xmm1"), Set.of("xmm3"));
        assertUsage("66 0f db c8", "pand xmm1, xmm0", Set.of("xmm1", "xmm0"), Set.of("xmm1"));
    }

    @Test
    void movesOnlyWriteTheDestination() {
        assertUsage("4e 8b 5c da 18", "mov r11, qword ptr [rdx + 8*r11 + 0x18]", Set.of("rdx", "r11"), Set.of("r11"));
        assertUsage("48 8d 44 8b 08", "lea rax, [rbx + 4*rcx + 0x8]", Set.of("rbx", "rcx"), Set.of("rax"));
        assertUsage("0f b6 06", "movzx eax, byte ptr [rsi]", Set.of("rsi"), Set.of("rax"));
        assertUsage("4c 63 ce", "movsxd r9, esi", Set.of("rsi"), Set.of("r9"));
        assertUsage("f2 0f 10 07", "movsd xmm0, qword ptr [rdi]", Set.of("rdi"), Set.of("xmm0"));
        assertUsage("66 0f 28 d8", "movapd xmm3, xmm0", Set.of("xmm0"), Set.of("xmm3"));
        assertUsage("66 49 0f 7e d3", "movq r11, xmm2", Set.of("xmm2"), Set.of("r11"));
        assertUsage("66 49 0f 6e d3", "movq xmm2, r11", Set.of("r11"), Set.of("xmm2"));
        assertUsage("f2 0f 2c c0", "cvttsd2si eax, xmm0", Set.of("xmm0"), Set.of("rax"));
        assertUsage("f3 0f 6f 64 fa 40", "movdqu xmm4, xmmword ptr [rdx + 8*rdi + 0x40]", Set.of("rdx", "rdi"), Set.of("xmm4"));
        assertUsage("48 6b c3 03", "imul rax, rbx, 0x3", Set.of("rbx"), Set.of("rax"));
    }

    @Test
    void partialWritesAlsoReadTheDestination() {
        assertUsage("88 d8", "mov al, bl", Set.of("rax", "rbx"), Set.of("rax"));
        assertUsage("0f 94 c0", "sete al", Set.of("rax"), Set.of("rax"));
        // A move between registers replaces only the low double.
        assertUsage("f2 0f 10 c1", "movsd xmm0, xmm1", Set.of("xmm0", "xmm1"), Set.of("xmm0"));
        assertUsage("f2 0f 2a c0", "cvtsi2sd xmm0, eax", Set.of("rax", "xmm0"), Set.of("xmm0"));
    }

    @Test
    void zeroingIdiomsOnlyWrite() {
        assertUsage("31 c0", "xor eax, eax", Set.of(), Set.of("rax"));
        assertUsage("31 d8", "xor eax, ebx", Set.of("rax", "rbx"), Set.of("rax"));
        assertUsage("66 0f ef c0", "pxor xmm0, xmm0", Set.of(), Set.of("xmm0"));
        assertUsage("c5 f8 57 c0", "vxorps xmm0, xmm0, xmm0", Set.of(), Set.of("xmm0"));
    }

    @Test
    void comparesAndBranchesOnlyRead() {
        assertUsage("41 39 c2", "cmp r10d, eax", Set.of("r10", "rax"), Set.of());
        assertUsage("48 85 c0", "test rax, rax", Set.of("rax"), Set.of());
        assertUsage("66 0f 2e c1", "ucomisd xmm0, xmm1", Set.of("xmm0", "xmm1"), Set.of());
        assertUsage("ff 60 08", "jmp qword ptr [rax + 0x8]", Set.of("rax"), Set.of());
        assertUsage("0f 1f 04 00", "nop dword ptr [rax + rax]", Set.of(), Set.of());
    }

    @Test
    void vexAndEvexFormsWriteOnlyTheDestination() {
        assertUsage("c4 81 7b 59 5c f3 10", "vmulsd xmm3, xmm0, qword ptr [r11 + 8*r14 + 0x10]",
                Set.of("xmm0", "r11", "r14"), Set.of("xmm3"));
        assertUsage("c5 e3 58 d9", "vaddsd xmm3, xmm3, xmm1", Set.of("xmm3", "xmm1"), Set.of("xmm3"));
        assertUsage("c5 fb 10 07", "vmovsd xmm0, qword ptr [rdi]", Set.of("rdi"), Set.of("xmm0"));
        // ymm and zmm are views of the same registers.
        assertUsage("c5 ed 58 08", "vaddpd ymm1, ymm2, ymmword ptr [rax]", Set.of("xmm2", "rax"), Set.of("xmm1"));
        assertUsage("c4 e2 e0 f2 c1", "andn rax, rbx, rcx", Set.of("rbx", "rcx"), Set.of("rax"));
        // Fused multiply-adds accumulate into the destination.
        assertUsage("c4 e2 f1 b9 c2", "vfmadd231sd xmm0, xmm1, xmm2", Set.of("xmm0", "xmm1", "xmm2"), Set.of("xmm0"));
        // Merge masking keeps the masked-off elements; zero masking doesn't.
        assertUsage("62 f1 75 49 fe c2", "vpaddd zmm0 {k1}, zmm1, zmm2", Set.of("xmm0", "xmm1", "xmm2", "k1"), Set.of("xmm0"));
        assertUsage("62 f1 75 c9 fe c2", "vpaddd zmm0 {k1} {z}, zmm1, zmm2", Set.of("xmm1", "xmm2", "k1"), Set.of("xmm0"));
    }

    @Test
    void storesReadTheirAddressAndStoreTheirSource() {
        assertUsage("4c 89 54 f9 10", "mov qword ptr [rcx + 8*rdi + 0x10], r10", Set.of("rcx", "rdi", "r10"), Set.of());
        assertStored("4c 89 54 f9 10", Set.of("r10"));
        assertUsage("f2 0f 11 5c f1 10", "movsd qword ptr [rcx + 8*rsi + 0x10], xmm3", Set.of("rcx", "rsi", "xmm3"), Set.of());
        assertStored("f2 0f 11 5c f1 10", Set.of("xmm3"));
        assertStored("c5 fb 11 5c f1 10", Set.of("xmm3"));
        assertStored("f3 0f 7f 6c f9 10", Set.of("xmm5"));
        // The mask selects the elements stored; it isn't stored itself.
        assertUsage("62 f1 fe 49 7f 07", "vmovdqu64 zmmword ptr [rdi] {k1}, zmm0", Set.of("rdi", "k1", "xmm0"), Set.of());
        assertStored("62 f1 fe 49 7f 07", Set.of("xmm0"));
        // Read-modify-write of memory.
        assertUsage("48 01 01", "add qword ptr [rcx], rax", Set.of("rcx", "rax"), Set.of());
    }

    @Test
    void implicitOperands() {
        assertUsage("48 f7 e1", "mul rcx", Set.of("rax", "rcx"), Set.of("rax", "rdx"));
        assertUsage("49 f7 f8", "idiv r8", Set.of("rax", "rdx", "r8"), Set.of("rax", "rdx"));
        assertUsage("99", "cdq", Set.of("rax"), Set.of("rdx"));
        assertUsage("55", "push rbp", Set.of("rbp", "rsp"), Set.of("rsp"));
        assertUsage("5d", "pop rbp", Set.of("rsp"), Set.of("rbp", "rsp"));
        assertUsage("c3", "ret", Set.of("rsp"), Set.of("rsp"));
        assertUsage("ff d0", "call rax", Set.of("rax", "rsp"), Set.of("rsp"));
        assertUsage("48 93", "xchg rax, rbx", Set.of("rax", "rbx"), Set.of("rax", "rbx"));
        assertUsage("f0 48 0f b1 0a", "lock cmpxchg qword ptr [rdx], rcx", Set.of("rdx", "rcx", "rax"), Set.of("rax"));
        assertStored("f0 48 0f b1 0a", Set.of("rcx"));
        assertUsage("f3 48 ab", "rep stosq qword ptr es:[rdi], rax", Set.of("rdi", "rax", "rcx"), Set.of("rdi", "rcx"));
        assertStored("f3 48 ab", Set.of("rax"));
    }

    @Test
    void undecodedInstructionsAreUnknown() {
        Instruction prefix = disassembler.decode(HexFormat.of().parseHex("f0"), 0, 0);
        assertFalse(registers.usage(prefix).known());
        assertEquals(RegisterUsage.UNKNOWN, registers.usage(prefix));
    }

    @Test
    void namesEveryRegisterCanonicallyAcrossTheVectors() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String line : resource("llvm-vectors.tsv")) {
            String[] fields = line.split("\t");
            Instruction instruction =
                    disassembler.decode(HexFormat.of().parseHex(fields[1]), 0, Long.parseUnsignedLong(fields[0], 16));
            RegisterUsage usage;
            try {
                usage = registers.usage(instruction);
            } catch (RuntimeException e) {
                problems.add(instruction.text() + ": " + e);
                continue;
            }
            checked++;
            Set<String> named = new HashSet<>(usage.reads());
            named.addAll(usage.writes());
            for (String register : named) {
                if (!CANONICAL.matcher(register).matches()) {
                    problems.add(instruction.text() + ": " + register);
                }
            }
            if (!usage.reads().containsAll(usage.stored())) {
                problems.add(instruction.text() + ": stores what it doesn't read");
            }
        }
        assertTrue(checked > 30_000);
        assertEquals(List.of(), problems.subList(0, Math.min(20, problems.size())), problems.size() + " problems");
    }

    private void assertUsage(String bytes, String text, Set<String> reads, Set<String> writes) {
        Instruction instruction = decode(bytes);
        assertEquals(text, instruction.text());
        RegisterUsage usage = registers.usage(instruction);
        assertTrue(usage.known(), text);
        assertEquals(reads, usage.reads(), text + " reads");
        assertEquals(writes, usage.writes(), text + " writes");
    }

    private void assertStored(String bytes, Set<String> stored) {
        Instruction instruction = decode(bytes);
        RegisterUsage usage = registers.usage(instruction);
        assertEquals(stored, usage.stored(), instruction.text() + " stores");
        assertTrue(usage.reads().containsAll(stored), instruction.text());
    }

    private Instruction decode(String bytes) {
        return disassembler.decode(HexFormat.ofDelimiter(" ").parseHex(bytes), 0, 0);
    }

    private static List<String> resource(String name) throws IOException {
        try (var in = Objects.requireNonNull(X86RegisterModelTest.class.getResourceAsStream(name), name);
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        }
    }
}
