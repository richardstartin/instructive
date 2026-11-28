package instructive.analyse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import instructive.analyse.MemoryOrderingModel.Ordering;
import instructive.parse.Instruction;
import instructive.parse.aarch64.Aarch64Disassembler;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class FetchInLoopRuleTest {
    private static final long BASE = Listing.BASE;

    static Stream<Arguments> reported() {
        // listing, the loop header, the accesses' orderings in address order (Acquire,
        // Release, Update, Fence), samples in the loop, on the accesses and the
        // instructions after them
        return Stream.of(
                // s += a.addAndGet(d[i]): ldaddal and lock xadd, one per unrolled copy
                arguments(Architecture.AARCH64, "atomic-long-add-and-get.tsv", 0x90, "UUUU", 82, 72),
                arguments(Architecture.X86, "atomic-long-add-and-get.tsv", 0x90, "UUUU", 99, 78),
                // a.incrementAndGet(), the result unused: ldaddal into xzr, lock add
                arguments(Architecture.AARCH64, "atomic-long-increment.tsv", 0x80, "UUUU", 99, 95),
                arguments(Architecture.X86, "atomic-long-increment.tsv", 0x71, "UUUU", 98, 98),
                // s += a.getAndAdd(d[i]) on an AtomicInteger
                arguments(Architecture.AARCH64, "atomic-integer-get-and-add.tsv", 0x90, "UUUU", 98, 80),
                arguments(Architecture.X86, "atomic-integer-get-and-add.tsv", 0x80, "UUUU", 99, 88),
                // a.compareAndSet(i - 1, i): casal and lock cmpxchg
                arguments(Architecture.AARCH64, "atomic-integer-cas.tsv", 0xa0, "UUUU", 99, 80),
                arguments(Architecture.X86, "atomic-integer-cas.tsv", 0x90, "UUUU", 97, 65),
                // b.compareAndSet(...) on an AtomicBoolean
                arguments(Architecture.AARCH64, "atomic-boolean-cas.tsv", 0x84, "UUUU", 98, 97),
                arguments(Architecture.X86, "atomic-boolean-cas.tsv", 0x83, "UUUU", 98, 23),
                // s += a.get() ^ d[i]: an ldar per element, unrolled 16 times. x86's
                // volatile loads are plain movs.
                arguments(Architecture.AARCH64, "atomic-integer-get.tsv", 0x90, "A".repeat(16), 97, 45),
                // b.set(...): an stlr per element on AArch64. x86's volatile stores are
                // plain movs, but C2 keeps one fence per iteration of the unrolled loop.
                arguments(Architecture.AARCH64, "atomic-boolean-set.tsv", 0x90, "R".repeat(16), 95, 21),
                arguments(Architecture.X86, "atomic-boolean-set.tsv", 0x92, "F", 92, 13),
                // a.add(d[i]) on a LongAdder, one thread: cells and base read with ldar,
                // then base CASed with release semantics (dmb ish, casl), unrolled twice.
                // x86 shows only the lock cmpxchg.
                arguments(Architecture.AARCH64, "long-adder-add.tsv", 0xb0, "AAFUAAFU", 99, 84),
                arguments(Architecture.X86, "long-adder-add.tsv", 0xa0, "UU", 98, 59),
                // Without LSE, each update is an ldaxr/stlxr retry loop of its own: the
                // accesses belong to the program's loop around them.
                arguments(Architecture.AARCH64, "atomic-long-add-and-get-no-lse.tsv", 0xa0, "UUUUUUUU", 88, 40),
                arguments(Architecture.AARCH64, "atomic-integer-cas-no-lse.tsv", 0xb0, "UUUUUUUU", 90, 28));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    void reported(Architecture architecture, String resource, int header, String orderings, long samples, long accessSamples) {
        Listing method = architecture.load(resource);
        List<FetchInLoop> findings = new FetchInLoopRule(architecture.ordering).apply(method.code(), method::count);
        assertEquals(1, findings.size(), () -> describe(findings));
        FetchInLoop found = findings.get(0);
        assertEquals(BASE + header, found.header(), () -> describe(findings));
        assertEquals(orderings, found.accesses().stream().map(access -> code(access.ordering())).collect(Collectors.joining()),
                () -> describe(findings));
        assertEquals(samples, found.samples());
        assertEquals(accessSamples, found.accessSamples());
        // The loop is where the method spends its time.
        assertTrue(found.samples() * 10 >= method.total() * 9);
        // Without samples, every loop with accesses: this one, and the pre- and
        // post-loops C2 puts around the unrolled loop.
        List<FetchInLoop> unsampled = new FetchInLoopRule(architecture.ordering).apply(method.code());
        assertTrue(unsampled.stream().anyMatch(loop -> loop.header() == BASE + header), () -> describe(unsampled));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        // box[0] += d[i]: no atomics or ordering
        "AARCH64, plain-counter.tsv",
        "X86, plain-counter.tsv",
        // a.addAndGet(sum) once, after the loop
        "AARCH64, atomic-add-after-loop.tsv",
        "X86, atomic-add-after-loop.tsv",
        // a.get() on x86: volatile loads are plain movs
        "X86, atomic-integer-get.tsv",
    })
    void notReported(Architecture architecture, String resource) {
        Listing method = architecture.load(resource);
        List<FetchInLoop> sampled = new FetchInLoopRule(architecture.ordering).apply(method.code(), method::count);
        assertEquals(List.of(), sampled, () -> describe(sampled));
        List<FetchInLoop> unsampled = new FetchInLoopRule(architecture.ordering).apply(method.code());
        assertEquals(List.of(), unsampled, () -> describe(unsampled));
    }

    @Test
    void leavesColdLoopsAlone() {
        // The same atomic loop, with every sample put outside it.
        Listing method = Architecture.AARCH64.load("atomic-long-add-and-get.tsv");
        FetchInLoop loop = new FetchInLoopRule(Architecture.AARCH64.ordering).apply(method.code(), method::count).get(0);
        List<FetchInLoop> findings = new FetchInLoopRule(Architecture.AARCH64.ordering)
                .apply(method.code(), instruction -> loop.instructions().contains(instruction) ? 0 : 10);
        assertTrue(findings.stream().noneMatch(found -> found.header() == loop.header()), () -> describe(findings));
        // And the threshold is configurable.
        List<FetchInLoop> everything = new FetchInLoopRule(Architecture.AARCH64.ordering, 0)
                .apply(method.code(), instruction -> loop.instructions().contains(instruction) ? 0 : 10);
        assertTrue(everything.stream().anyMatch(found -> found.header() == loop.header()), () -> describe(everything));
        assertThrows(IllegalArgumentException.class, () -> new FetchInLoopRule(Architecture.AARCH64.ordering, 1.5));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
        "88dffc8a, 'ldar w10, [x4]', ACQUIRE_LOAD",
        "08dffc41, 'ldarb w1, [x2]', ACQUIRE_LOAD",
        "f8bfc041, 'ldapr x1, [x2]', ACQUIRE_LOAD",
        "889ffc2d, 'stlr w13, [x1]', RELEASE_STORE",
        "089ffc41, 'stlrb w1, [x2]', RELEASE_STORE",
        "f8f10272, 'ldaddal x17, x18, [x19]', ATOMIC_UPDATE",
        "f8210062, 'ldadd x1, x2, [x3]', ATOMIC_UPDATE",
        "88e8fdcb, 'casal w8, w11, [x14]', ATOMIC_UPDATE",
        "c8a8fc2f, 'casl x8, x15, [x1]', ATOMIC_UPDATE",
        "f8e18062, 'swpal x1, x2, [x3]', ATOMIC_UPDATE",
        "b8e13062, 'ldsetal w1, w2, [x3]', ATOMIC_UPDATE",
        "c85ffe91, 'ldaxr x17, [x20]', ATOMIC_UPDATE",
        "c809fe88, 'stlxr w9, x8, [x20]', ATOMIC_UPDATE",
        "885f7c41, 'ldxr w1, [x2]', ATOMIC_UPDATE",
        "88037c41, 'stxr w3, w1, [x2]', ATOMIC_UPDATE",
        "d5033bbf, dmb ish, FENCE",
        "d50339bf, dmb ishld, FENCE",
        "d5033abf, dmb ishst, FENCE",
        "f9400041, 'ldr x1, [x2]', ",
        "f9000041, 'str x1, [x2]', ",
        "d5033fdf, isb, ",
    })
    void classifiesAarch64(String word, String text, Ordering ordering) {
        Instruction instruction = new Aarch64Disassembler().decode(Integer.parseUnsignedInt(word, 16), 0);
        assertEquals(text, instruction.text());
        assertEquals(Optional.ofNullable(ordering), Architecture.AARCH64.ordering.classify(instruction));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
        "f0 48 0f c1 5e 10, 'lock xadd qword ptr [rsi + 0x10], rbx', ATOMIC_UPDATE",
        "f0 0f b1 57 0c, 'lock cmpxchg dword ptr [rdi + 0xc], edx', ATOMIC_UPDATE",
        "f0 48 83 43 10 01, 'lock add qword ptr [rbx + 0x10], 0x1', ATOMIC_UPDATE",
        // HotSpot's fence after a volatile store
        "f0 83 44 24 c0 00, 'lock add dword ptr [rsp - 0x40], 0x0', FENCE",
        "f0 83 0c 24 00, 'lock or dword ptr [rsp], 0x0', FENCE",
        // xchg with memory is locked without a prefix
        "48 87 07, 'xchg qword ptr [rdi], rax', ATOMIC_UPDATE",
        "48 93, 'xchg rax, rbx', ",
        "0f ae f0, mfence, FENCE",
        "48 8b 07, 'mov rax, qword ptr [rdi]', ",
        "41 89 6b 0c, 'mov dword ptr [r11 + 0xc], ebp', ",
        "0f ae e8, lfence, ",
    })
    void classifiesX86(String bytes, String text, Ordering ordering) {
        Instruction instruction = Architecture.X86.disassembler.decode(HexFormat.ofDelimiter(" ").parseHex(bytes), 0, 0);
        assertEquals(text, instruction.text());
        assertEquals(Optional.ofNullable(ordering), Architecture.X86.ordering.classify(instruction));
    }

    private static String code(Ordering ordering) {
        return switch (ordering) {
            case ACQUIRE_LOAD -> "A";
            case RELEASE_STORE -> "R";
            case ATOMIC_UPDATE -> "U";
            case FENCE -> "F";
        };
    }

    private static String describe(List<FetchInLoop> findings) {
        return findings.stream()
                .map(found -> String.format("loop at +0x%x, %d samples, %d on accesses: %s", found.header() - BASE,
                        found.samples(), found.accessSamples(),
                        found.accesses().stream().map(access -> access.instruction().text() + " " + access.ordering())
                                .collect(Collectors.joining("; "))))
                .collect(Collectors.joining("\n"));
    }
}
