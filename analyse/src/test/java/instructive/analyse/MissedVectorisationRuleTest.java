package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import instructive.parse.Instruction;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MissedVectorisationRuleTest {
    private static final long BASE = Listing.BASE;

    // Two copies of x[i] * a, storing adjacent doubles: unrolled twice, not vectorised.
    private static final String[] AARCH64_TWO_COPIES = {
        "30 08 40 fd", // ldr d16, [x1, #0x10]
        "10 0a 60 1e", // fmul d16, d16, d0
        "50 08 00 fd", // str d16, [x2, #0x10]
        "31 0c 40 fd", // ldr d17, [x1, #0x18]
        "31 0a 60 1e", // fmul d17, d17, d0
        "51 0c 00 fd", // str d17, [x2, #0x18]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // The same, but the second copy stores two elements further on.
    private static final String[] AARCH64_NOT_ADJACENT = {
        "30 08 40 fd", // ldr d16, [x1, #0x10]
        "10 0a 60 1e", // fmul d16, d16, d0
        "50 08 00 fd", // str d16, [x2, #0x10]
        "31 0c 40 fd", // ldr d17, [x1, #0x18]
        "31 0a 60 1e", // fmul d17, d17, d0
        "51 10 00 fd", // str d17, [x2, #0x20]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // Two copies of x[i] / a on ints: there is no vector integer division.
    private static final String[] AARCH64_DIVIDE = {
        "25 10 40 b9", // ldr w5, [x1, #0x10]
        "a5 0c c0 1a", // sdiv w5, w5, w0
        "45 10 00 b9", // str w5, [x2, #0x10]
        "26 14 40 b9", // ldr w6, [x1, #0x14]
        "c6 0c c0 1a", // sdiv w6, w6, w0
        "46 14 00 b9", // str w6, [x2, #0x14]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // Two copies of x[i] * a on ints: a 32-bit multiply has a vector equivalent.
    private static final String[] AARCH64_MULTIPLY = {
        "25 10 40 b9", // ldr w5, [x1, #0x10]
        "a5 7c 00 1b", // mul w5, w5, w0
        "45 10 00 b9", // str w5, [x2, #0x10]
        "26 14 40 b9", // ldr w6, [x1, #0x14]
        "c6 7c 00 1b", // mul w6, w6, w0
        "46 14 00 b9", // str w6, [x2, #0x14]
        "63 08 00 11", // add w3, w3, #0x2
        "7f 00 04 6b", // cmp w3, w4
        "0b ff ff 54", // b.lt (to the loop start)
        "c0 03 5f d6", // ret
    };

    // Two copies of y[i] += a * x[i] with AVX: three-operand forms, loads folded in.
    private static final String[] X86_TWO_COPIES = {
        "c5 fb 59 5c c6 10", // vmulsd xmm3, xmm0, qword ptr [rsi + 8*rax + 0x10]
        "c5 e3 58 5c c2 10", // vaddsd xmm3, xmm3, qword ptr [rdx + 8*rax + 0x10]
        "c5 fb 11 5c c2 10", // vmovsd qword ptr [rdx + 8*rax + 0x10], xmm3
        "c5 fb 59 64 c6 18", // vmulsd xmm4, xmm0, qword ptr [rsi + 8*rax + 0x18]
        "c5 db 58 64 c2 18", // vaddsd xmm4, xmm4, qword ptr [rdx + 8*rax + 0x18]
        "c5 fb 11 64 c2 18", // vmovsd qword ptr [rdx + 8*rax + 0x18], xmm4
        "83 c0 02", // add eax, 0x2
        "39 c8", // cmp eax, ecx
        "7c d5", // jl (to the loop start)
        "c3", // ret
    };

    // The same with fused multiply-adds, which accumulate into the loaded y[i].
    private static final String[] X86_FUSED = {
        "c5 fb 10 5c c2 10", // vmovsd xmm3, qword ptr [rdx + 8*rax + 0x10]
        "c4 e2 f9 b9 5c c6 10", // vfmadd231sd xmm3, xmm0, qword ptr [rsi + 8*rax + 0x10]
        "c5 fb 11 5c c2 10", // vmovsd qword ptr [rdx + 8*rax + 0x10], xmm3
        "c5 fb 10 64 c2 18", // vmovsd xmm4, qword ptr [rdx + 8*rax + 0x18]
        "c4 e2 f9 b9 64 c6 18", // vfmadd231sd xmm4, xmm0, qword ptr [rsi + 8*rax + 0x18]
        "c5 fb 11 64 c2 18", // vmovsd qword ptr [rdx + 8*rax + 0x18], xmm4
        "83 c0 02", // add eax, 0x2
        "39 c8", // cmp eax, ecx
        "7c d3", // jl (to the loop start)
        "c3", // ret
    };

    // y[i] = a * x[i] + y[i] four at a time, vectorised with AVX.
    private static final String[] X86_VECTORISED = {
        "c5 fd 10 4c c6 10", // vmovupd ymm1, ymmword ptr [rsi + 8*rax + 0x10]
        "c4 e2 fd a8 4c c2 10", // vfmadd213pd ymm1, ymm0, ymmword ptr [rdx + 8*rax + 0x10]
        "c5 fd 11 4c c2 10", // vmovupd ymmword ptr [rdx + 8*rax + 0x10], ymm1
        "83 c0 04", // add eax, 0x4
        "39 c8", // cmp eax, ecx
        "7c e6", // jl (to the loop start)
        "c3", // ret
    };

    // Two copies of a[i] & b[i], the second stored two elements further on.
    private static final String[] X86_NOT_ADJACENT = {
        "4c 8b 44 c6 10", // mov r8, qword ptr [rsi + 8*rax + 0x10]
        "4c 23 44 c7 10", // and r8, qword ptr [rdi + 8*rax + 0x10]
        "4c 89 44 c2 10", // mov qword ptr [rdx + 8*rax + 0x10], r8
        "4c 8b 4c c6 18", // mov r9, qword ptr [rsi + 8*rax + 0x18]
        "4c 23 4c c7 18", // and r9, qword ptr [rdi + 8*rax + 0x18]
        "4c 89 4c c2 20", // mov qword ptr [rdx + 8*rax + 0x20], r9
        "83 c0 02", // add eax, 0x2
        "39 c8", // cmp eax, ecx
        "7c db", // jl (to the loop start)
        "c3", // ret
    };

    // Two copies of x[i] / a on ints.
    private static final String[] X86_DIVIDE = {
        "8b 44 8e 10", // mov eax, dword ptr [rsi + 4*rcx + 0x10]
        "99", // cdq
        "41 f7 f8", // idiv r8d
        "89 44 8a 10", // mov dword ptr [rdx + 4*rcx + 0x10], eax
        "8b 44 8e 14", // mov eax, dword ptr [rsi + 4*rcx + 0x14]
        "99", // cdq
        "41 f7 f8", // idiv r8d
        "89 44 8a 14", // mov dword ptr [rdx + 4*rcx + 0x14], eax
        "83 c1 02", // add ecx, 0x2
        "44 39 c9", // cmp ecx, r9d
        "7c e0", // jl (to the loop start)
        "c3", // ret
    };

    // Two copies of x[i] * a on ints.
    private static final String[] X86_MULTIPLY = {
        "8b 44 8e 10", // mov eax, dword ptr [rsi + 4*rcx + 0x10]
        "41 0f af c0", // imul eax, r8d
        "89 44 8a 10", // mov dword ptr [rdx + 4*rcx + 0x10], eax
        "44 8b 54 8e 14", // mov r10d, dword ptr [rsi + 4*rcx + 0x14]
        "45 0f af d0", // imul r10d, r8d
        "44 89 54 8a 14", // mov dword ptr [rdx + 4*rcx + 0x14], r10d
        "83 c1 02", // add ecx, 0x2
        "44 39 c9", // cmp ecx, r9d
        "7c de", // jl (to the loop start)
        "c3", // ret
    };

    static Stream<Arguments> unrolledButNotVectorised() {
        return Stream.of(
                // y[yOffset + i] += a * x[xOffset + i]: x and y might be the same array, so
                // C2 unrolls four times but keeps each copy scalar. The last copy addresses
                // its elements from registers of its own. On x86 the loads are folded into
                // the arithmetic, and SSE's two-operand forms copy a into the destination.
                arguments(Architecture.AARCH64, "daxpy-offsets.tsv", 0xe0, 8, List.of(0x104, 0x118, 0x12c, 0x154),
                        List.of("ldr", "ldr", "fmul", "fadd", "str"), List.of("fmul", "fadd"), 98),
                arguments(Architecture.X86, "daxpy-offsets.tsv", 0xe3, 8, List.of(0x11a, 0x131, 0x148, 0x160),
                        List.of("movapd", "mulsd", "addsd", "movsd"), List.of("mulsd", "addsd"), 95),
                // y[yOffset + i] += 3 * x[xOffset + i] on ints: the multiply by 3 is an add
                // of a shift. x86 adds to y[i] in memory.
                arguments(Architecture.AARCH64, "add-int-offsets.tsv", 0xe0, 4, List.of(0x104, 0x118, 0x138, 0x154),
                        List.of("ldr", "ldr", "add", "add", "str"), List.of("add", "add"), 97),
                arguments(Architecture.X86, "add-int-offsets.tsv", 0xc4, 4, List.of(0xfa, 0x110, 0x126, 0x13c),
                        List.of("mov", "mov", "shl", "add", "add"), List.of("shl", "add", "add"), 98),
                // y[i] += a * x[i + 1]: before JDK 25, superword wouldn't pack loads
                // misaligned with the stores.
                arguments(Architecture.AARCH64, "daxpy-constant-offset-jdk17.tsv", 0xb0, 8, List.of(0xcc, 0xe0, 0xf4, 0x10c),
                        List.of("ldr", "ldr", "fmul", "fadd", "str"), List.of("fmul", "fadd"), 97),
                arguments(Architecture.X86, "daxpy-constant-offset-jdk17.tsv", 0xb0, 8, List.of(0xc2, 0xdb, 0xf4, 0x10d),
                        List.of("movapd", "mulsd", "addsd", "movsd"), List.of("mulsd", "addsd"), 97),
                // c[i] = a[i] & b[i] on longs with -XX:-UseSuperWord: unrolled eight times.
                // C2 swaps the operands of some copies' and, which doesn't matter.
                arguments(Architecture.AARCH64, "intersect-no-superword.tsv", 0xa0, 8,
                        List.of(0xbc, 0xcc, 0xdc, 0xec, 0xfc, 0x10c, 0x11c, 0x130),
                        List.of("ldr", "ldr", "and", "str"), List.of("and"), 99),
                arguments(Architecture.X86, "intersect-no-superword.tsv", 0xb0, 8,
                        List.of(0xba, 0xc9, 0xd8, 0xe7, 0xf6, 0x105, 0x114, 0x123),
                        List.of("mov", "and", "mov"), List.of("and"), 94));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void unrolledButNotVectorised(
            Architecture architecture, String resource, int header, int elementSize, List<Integer> stores,
            List<String> copy, List<String> operations, long samples) {
        Listing method = architecture.load(resource);
        List<MissedVectorisation> findings = apply(architecture, method);
        assertEquals(1, findings.size(), () -> describe(findings));
        MissedVectorisation found = findings.get(0);
        assertEquals(BASE + header, found.header(), () -> describe(findings));
        assertEquals(stores.size(), found.lanes());
        assertEquals(elementSize, found.elementSize());
        assertEquals(stores, stores(found));
        for (List<Instruction> instructions : found.copies()) {
            assertEquals(copy, instructions.stream().map(Instruction::mnemonic).toList(), () -> describe(findings));
        }
        assertEquals(operations, found.operations().stream().map(Instruction::mnemonic).toList());
        assertEquals(samples, found.samples());
        assertTrue(samples * 10 >= method.total() * 9);
    }

    @Test
    void operationsAreTheFirstCopys() {
        MissedVectorisation aarch64 = apply(Architecture.AARCH64, Architecture.AARCH64.load("daxpy-offsets.tsv")).get(0);
        assertEquals(List.of("fmul d16, d16, d0", "fadd d16, d16, d17"), aarch64.operations().stream().map(Instruction::text).toList());
        MissedVectorisation x86 = apply(Architecture.X86, Architecture.X86.load("daxpy-offsets.tsv")).get(0);
        assertEquals(List.of("mulsd xmm3, qword ptr [r11 + 8*r14 + 0x10]", "addsd xmm3, qword ptr [rcx + 8*rsi + 0x10]"),
                x86.operations().stream().map(Instruction::text).toList());
    }

    static Stream<Arguments> cannotBeVectorised() {
        return Arrays.stream(Architecture.values()).flatMap(architecture -> Stream.of(
                // vectorised: the loop has vector instructions
                "daxpy.tsv",
                "intersect.tsv",
                // a floating-point sum: nothing is stored in the loop
                "daxpy-sum.tsv",
                // y[i] = a * y[i - 1] + x[i]: unrolled, each copy using the one before
                "daxpy-recurrence.tsv",
                // y[i] *= x[i] on longs: no 64-bit vector multiply in Advanced SIMD, or on
                // x86 before AVX-512
                "mul-long-offsets.tsv")
                .map(resource -> arguments(architecture, resource)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void cannotBeVectorised(Architecture architecture, String resource) {
        Listing method = architecture.load(resource);
        List<MissedVectorisation> sampled = apply(architecture, method);
        assertEquals(List.of(), sampled, () -> describe(sampled));
        List<MissedVectorisation> unsampled = apply(architecture, method.code());
        assertEquals(List.of(), unsampled, () -> describe(unsampled));
    }

    static Stream<Arguments> synthetic() {
        return Stream.of(
                arguments(Architecture.AARCH64, "two adjacent copies", AARCH64_TWO_COPIES, List.of(0x08, 0x14)),
                arguments(Architecture.AARCH64, "a 32-bit multiply", AARCH64_MULTIPLY, List.of(0x08, 0x14)),
                arguments(Architecture.AARCH64, "elements not adjacent", AARCH64_NOT_ADJACENT, List.of()),
                arguments(Architecture.AARCH64, "an integer division", AARCH64_DIVIDE, List.of()),
                arguments(Architecture.X86, "two adjacent copies with AVX", X86_TWO_COPIES, List.of(0x0c, 0x1e)),
                arguments(Architecture.X86, "fused multiply-adds", X86_FUSED, List.of(0x0d, 0x20)),
                arguments(Architecture.X86, "a 32-bit multiply", X86_MULTIPLY, List.of(0x08, 0x15)),
                arguments(Architecture.X86, "vectorised with AVX", X86_VECTORISED, List.of()),
                arguments(Architecture.X86, "elements not adjacent", X86_NOT_ADJACENT, List.of()),
                arguments(Architecture.X86, "an integer division", X86_DIVIDE, List.of()));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void synthetic(Architecture architecture, String description, String[] bytes, List<Integer> stores) {
        List<MissedVectorisation> findings = apply(architecture, bytes);
        if (stores.isEmpty()) {
            assertEquals(List.of(), findings, () -> describe(findings));
        } else {
            assertEquals(1, findings.size(), () -> describe(findings));
            assertEquals(stores, stores(findings.get(0)));
        }
    }

    @Test
    void leavesLoopsWithoutCopiesAlone() {
        assertTrue(apply(Architecture.AARCH64, Listing.decode(LongDependencyChainRuleTest.HASH_LOOP)).isEmpty());
        assertTrue(apply(Architecture.AARCH64, Listing.decode(LongDependencyChainRuleTest.DELTA_LOOP)).isEmpty());
        assertTrue(apply(Architecture.AARCH64, Listing.load("add-with-call-in-condition.tsv").code()).isEmpty());
    }

    private static List<MissedVectorisation> apply(Architecture architecture, Listing listing) {
        return new MissedVectorisationRule(architecture.vectorisation).apply(listing.code(), architecture.registers, listing::count);
    }

    private static List<MissedVectorisation> apply(Architecture architecture, List<Instruction> code) {
        return new MissedVectorisationRule(architecture.vectorisation).apply(code, architecture.registers);
    }

    private static List<MissedVectorisation> apply(Architecture architecture, String... bytes) {
        return apply(architecture, Listing.decode(architecture.disassembler, bytes));
    }

    private static List<Integer> stores(MissedVectorisation found) {
        return found.copies().stream().map(copy -> (int) (copy.get(copy.size() - 1).address() - BASE)).toList();
    }

    private static String describe(List<MissedVectorisation> findings) {
        return findings.stream()
                .map(found -> String.format("loop at +0x%x, %d lanes of %d bytes, %d samples: %s", found.header() - BASE,
                        found.lanes(), found.elementSize(), found.samples(), found.copies()))
                .collect(Collectors.joining("\n"));
    }
}
