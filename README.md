# instructive

instructive is a sampling profiler for the JVM that finds the hottest JIT-compiled
methods and gives you an instruction-level profile of each one: which machine
instructions inside the method the CPU time is spent on.

## Why

Most JVM profilers sample stack traces. On each sample they unwind the stack and
record how the current frame was reached, which answers *"which call paths are
expensive?"*. That is the right question for a lot of performance work, but it stops
at the frame. Once you know a method is hot, a stack trace can't tell you what is
going on inside it: which loop, which load, which branch, or whether the code the JIT
produced is the code you expected.

instructive focuses on what goes on *inside* the frame rather than on how the frame
was reached. It doesn't unwind stacks at all. On each sample it records only the
interrupted program counter, attributed to the compiled method that contains it. That
yields two things:

- **The hottest compiled methods.** Each compilation (method, tier, compile id) is
  counted separately, so a C1 and a C2 version of the same method are distinguished.
- **An instruction profile for each of them.** Samples are counted per instruction, and
  the method's machine code can be fetched and disassembled to read the profile against.

### Goals

- **Lower CPU overhead than a stack trace sampling agent.** The signal handler does no
  unwinding and never reads JVM metadata or the code heap. It does one lookup in a
  lock-free radix tree and two atomic increments.
- **Memory overhead comparable to a stack trace sampling agent.** Instead of storing
  stack traces, instructive keeps a 16-bit counter per possible instruction start in
  live compiled code (every 4 bytes on AArch64, every byte on x86), plus a small header
  and the method's name. Space is reused as methods are unloaded. In a test with heavy
  class churn on AArch64, the storage settled at about 950 KB for 1.2 MB of live
  compiled code.
- **Online use.** This is a similar view to what JMH's `perfasm` profiler gives you
  for a benchmark: hot regions of the generated assembly. JMH does that offline, for
  benchmarks, using external tools. instructive aims to provide it from inside a running
  application, with no external tools, while the application does its real work.

These are design goals. The overheads haven't been benchmarked against other agents yet.

## Example

A tight loop, profiled for a second:

```java
import instructive.agent.InstructionProfileFormatter;
import instructive.agent.Sampler;

public class Example {
    static long hot(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000, x = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 100_000; i++) {
                x = x * 31 + i;
            }
        }
        return x;
    }

    public static void main(String[] args) {
        for (int i = 0; i < 100; i++) {
            hot(1); // warm up, so the loop is JIT compiled
        }
        Sampler.start();
        long result = hot(1000);
        Sampler.stop();

        InstructionProfileFormatter formatter = new InstructionProfileFormatter().allInstructions();
        Sampler.forEachHottestMethod(1, profile -> formatter.format(profile.snapshot(), System.out));
        if (result == 42) {
            System.out.println(result);
        }
    }
}
```

Run with the agent jar (and the `parse` jar, which has the disassembler) on the class path:

```
java --enable-native-access=ALL-UNNAMED -javaagent:agent.jar -cp agent.jar:parse.jar:. Example
```

The output, on an Apple Silicon Mac, abridged around the inner loop:

```
Example.hot(J)J
  tier 4 (C2), compile id 349, 632 bytes at 0x00000001168d4f00, 88 samples

  pc                  bytes        instruction                   count        %
  ...
  0x00000001168d4fcc  1f 20 03 d5  nop
  0x00000001168d4fd0  eb 03 0e 2a  mov w11, w14                      1    1.14%
  0x00000001168d4fd4  aa ea 7b d3  lsl x10, x21, #5
  0x00000001168d4fd8  6e 7d 40 93  sxtw x14, w11
  0x00000001168d4fdc  4a 01 15 cb  sub x10, x10, x21
  0x00000001168d4fe0  4a 01 0e 8b  add x10, x10, x14
  ...
  0x00000001168d5080  4c e9 7b d3  lsl x12, x10, #5                  1    1.14%
  0x00000001168d5084  8a 01 0a cb  sub x10, x12, x10                 1    1.14%
  0x00000001168d5088  4a 01 0e 8b  add x10, x10, x14                 1    1.14%
  0x00000001168d508c  4c e9 7b d3  lsl x12, x10, #5                  5    5.68%
  0x00000001168d5090  8a 01 0a cb  sub x10, x12, x10                10   11.36%
  0x00000001168d5094  4a 01 0e 8b  add x10, x10, x14                 8    9.09%
  0x00000001168d5098  6e 41 00 11  add w14, w11, #0x10               6    6.82%
  0x00000001168d509c  55 01 17 8b  add x21, x10, x23                 5    5.68%
  0x00000001168d50a0  df 01 0d 6b  cmp w14, w13                     14   15.91%
  0x00000001168d50a4  6b f9 ff 54  b.lt 0x1168d4fd0 <+0xd0>
  0x00000001168d50a8  8c 1b 40 f9  ldr x12, [x28, #0x30]
  0x00000001168d50ac  9f 01 40 b9  ldr wzr, [x12]
  ...
```

How to read it:
- **The inner loop** runs from `+0xd0` to the back-edge `b.lt ... <+0xd0>`. C2 has
  unrolled it 16 times (`add w14, w11, #0x10` steps `i` by 16), and each `x * 31 + i` is
  a `lsl #5`, a `sub` and an `add`.
- **The samples** fall inside the loop, heaviest on the compare at its end.
- **After the loop,** `ldr wzr, [x12]` is HotSpot's safepoint poll.

On x86-64 Linux the listing is in Intel syntax. Here is a similar loop on an Intel Xeon
(only sampled instructions, abridged):

```
Demo.sum([I)J
  tier 4 (C2), compile id 352, 624 bytes at 0x00007f60dba92d00, 297 samples

  pc                  bytes           instruction        count        %
  0x00007f60dba92dbd  49 c1 e0 05     shl r8, 0x5           14    4.71%
  0x00007f60dba92ddb  4c 03 f2        add r14, rdx          12    4.04%
  ...
  0x00007f60dba92e4a  4d 8b d8        mov r11, r8           14    4.71%
  0x00007f60dba92e4d  49 c1 eb 03     shr r11, 0x3           2    0.67%
  0x00007f60dba92e51  49 33 c3        xor rax, r11          14    4.71%
  0x00007f60dba92e54  49 03 c0        add rax, r8           14    4.71%
  0x00007f60dba92e57  48 8b f8        mov rdi, rax          16    5.39%
  0x00007f60dba92e5a  48 c1 ef 03     shr rdi, 0x3           1    0.34%
  0x00007f60dba92e5e  41 83 c2 08     add r10d, 0x8         18    6.06%
```

## Analysis rules

The `analyse` module has rules that read a method's machine code and instruction profile
and explain what makes a hot loop slow. Each takes the decoded instructions, a register
model and the samples per instruction, and returns its findings hottest first.

| Rule | Finds | Section |
|---|---|---|
| `LongDependencyChainRule` | chains of instructions that each wait for the one before, within an iteration or carried from one iteration to the next | [Finding long dependency chains](#finding-long-dependency-chains) |
| `NotUnrolledLoopRule` | hot loops handling one element per iteration, which C2 hasn't unrolled and so can't vectorise | [Finding loops that aren't unrolled](#finding-loops-that-arent-unrolled) |
| `PointerChasingRule` | loops where each load's address comes from the previous iteration's load, as in walking a linked list | [Finding pointer chasing](#finding-pointer-chasing) |
| `FailedCheckRule` | explicit null checks that throw: nulls have been seen there | [Finding null checks that have failed](#finding-null-checks-that-have-failed) |
| `FailedCheckRule` | explicit range checks that throw, or that a loop kept: out-of-bounds indexes have been seen there, or the loop isn't counted | [Finding range checks that have failed](#finding-range-checks-that-have-failed) |
| `UnpredictableBranchRule` | hot loops branching on the data they load, whose samples land where mispredicted branches' would | [Finding unpredictable branches](#finding-unpredictable-branches) |
| `MegamorphicCallRule` | virtual and interface calls dispatching through a vtable or itable stub, whose cost the agent attributes to the call | [Finding megamorphic calls](#finding-megamorphic-calls) |
| `MissedVectorisationRule` | unrolled loops whose copies do the same scalar operations on adjacent elements, which C2 didn't vectorise, often because two arrays might overlap | [Finding missed vectorisation](#finding-missed-vectorisation) |
| `FetchInLoopRule` | hot loops that execute atomic updates, fences, or acquire loads and release stores on every iteration, as `Atomic*`, `LongAdder` and volatile fields compile to | [Finding atomics in hot loops](#finding-atomics-in-hot-loops) |

## Finding long dependency chains

An instruction profile shows where the time goes, but not why. One common reason a hot
loop is slow is a long *dependency chain*: each instruction needs the result of the one
before it. A modern core can run several instructions per cycle, but not instructions
that wait on each other, so a loop dominated by one chain runs at the chain's latency
rather than at the core's throughput. The `analyse` module finds such chains
automatically, from the instruction stream and its control flow.

### The hash loop

`Arrays.hashCode(int[])` computes `h = 31 * h + a[i]`. Each element's hash depends on
the previous element's, so however many elements C2 handles per loop iteration, they
form a single chain. With the vectorised intrinsic disabled
(`-XX:-UseVectorizedHashCodeIntrinsic`), C2 on AArch64 unrolls the loop 8 times and
computes `31 * h` as `(h << 5) - h`. The first two of the eight elements look like this:

```
lsl w11, w4, #5         // h << 5            (h is in w4)
ldr w12, [x1, #0x10]    // a[i]
sub w11, w11, w4        // (h << 5) - h      needs the lsl
add w12, w11, w12       // + a[i]            needs the sub and the ldr: h for the next element
lsl w14, w12, #5        //                   needs the add
ldr w11, [x1, #0x14]    // a[i + 1]
sub w12, w14, w12       //                   needs the lsl
add w12, w12, w11       //                   needs the sub and the ldr
...                     // six more elements
add w4, w12, w11        // h for the next iteration
```

### The instruction profile

One second of `Arrays.hashCode` on a 65,536-element array, printed by
`InstructionProfileFormatter().allInstructions()` and abridged to the unrolled loop, from
its first instruction to the back-edge `b.lt`, then the safepoint poll:

```
jdk.internal.util.ArraysSupport.hashCode([IIII)I
  tier 4 (C2), compile id 366, 544 bytes at 0x0000000116b1a300, 72 samples

  pc                  bytes        instruction                   count        %
  ...
  0x0000000116b1a3c0  41 c9 22 8b  add x1, x10, w2, sxtw #2          4    5.56%
  0x0000000116b1a3c4  8b 68 1b 53  lsl w11, w4, #5
  0x0000000116b1a3c8  2c 10 40 b9  ldr w12, [x1, #0x10]
  0x0000000116b1a3cc  6b 01 04 4b  sub w11, w11, w4
  0x0000000116b1a3d0  6c 01 0c 0b  add w12, w11, w12
  0x0000000116b1a3d4  8e 69 1b 53  lsl w14, w12, #5
  0x0000000116b1a3d8  2b 14 40 b9  ldr w11, [x1, #0x14]
  0x0000000116b1a3dc  cc 01 0c 4b  sub w12, w14, w12
  0x0000000116b1a3e0  8c 01 0b 0b  add w12, w12, w11                 1    1.39%
  0x0000000116b1a3e4  8e 69 1b 53  lsl w14, w12, #5
  0x0000000116b1a3e8  2b 18 40 b9  ldr w11, [x1, #0x18]
  0x0000000116b1a3ec  cc 01 0c 4b  sub w12, w14, w12
  0x0000000116b1a3f0  8c 01 0b 0b  add w12, w12, w11
  0x0000000116b1a3f4  8b 69 1b 53  lsl w11, w12, #5
  0x0000000116b1a3f8  2e 1c 40 b9  ldr w14, [x1, #0x1c]              1    1.39%
  0x0000000116b1a3fc  6c 01 0c 4b  sub w12, w11, w12
  0x0000000116b1a400  8b 01 0e 0b  add w11, w12, w14
  0x0000000116b1a404  6f 69 1b 53  lsl w15, w11, #5
  0x0000000116b1a408  2c 20 40 b9  ldr w12, [x1, #0x20]              2    2.78%
  0x0000000116b1a40c  eb 01 0b 4b  sub w11, w15, w11
  0x0000000116b1a410  6c 01 0c 0b  add w12, w11, w12
  0x0000000116b1a414  8f 69 1b 53  lsl w15, w12, #5
  0x0000000116b1a418  2b 24 40 b9  ldr w11, [x1, #0x24]              1    1.39%
  0x0000000116b1a41c  ec 01 0c 4b  sub w12, w15, w12
  0x0000000116b1a420  8c 01 0b 0b  add w12, w12, w11
  0x0000000116b1a424  8f 69 1b 53  lsl w15, w12, #5
  0x0000000116b1a428  2b 28 40 b9  ldr w11, [x1, #0x28]              4    5.56%
  0x0000000116b1a42c  ec 01 0c 4b  sub w12, w15, w12
  0x0000000116b1a430  8c 01 0b 0b  add w12, w12, w11
  0x0000000116b1a434  8f 69 1b 53  lsl w15, w12, #5
  0x0000000116b1a438  2b 2c 40 b9  ldr w11, [x1, #0x2c]             59   81.94%
  0x0000000116b1a43c  ec 01 0c 4b  sub w12, w15, w12
  0x0000000116b1a440  42 20 00 11  add w2, w2, #0x8
  0x0000000116b1a444  84 01 0b 0b  add w4, w12, w11
  0x0000000116b1a448  5f 00 00 6b  cmp w2, w0
  0x0000000116b1a44c  ab fb ff 54  b.lt 0x116b1a3c0 <+0xc0>
  0x0000000116b1a450  8b 1b 40 f9  ldr x11, [x28, #0x30]
  0x0000000116b1a454  7f 01 40 b9  ldr wzr, [x11]
  ...
```

The samples are on the loop's loads, and most of all on the eighth element's,
`ldr w11, [x1, #0x2c]` at `+0x138`, with 59 of the 72. Each load's value goes straight
into the `add` that computes the next `h`: the load is part of the chain, and sampling
attributes the chain's cost to it.

### Finding the chain

`DependencyGraph` first builds the method's control flow (`ControlFlowGraph`): basic
blocks, the branches between them, and loops. Here that includes the unrolled loop, which
starts at `+0x0c0` and branches back to it from `+0x14c`. For every register an
instruction reads, it then finds the instructions whose writes can reach it along that
control flow. A write that reaches only round the back-edge is carried from one iteration
to the next. Here `+0x144` writes `w4`, the `h` the next iteration starts from, and
`+0x0c4` and `+0x0cc` read it.

`LongDependencyChainRule` takes the instruction that produces the carried value,
`add w4, w12, w11` at `+0x144`, and walks backwards from it. For each instruction it
follows every operand to the instruction that wrote it within the iteration: from
`+0x144` through `w11` to the load at `+0x138` and through `w12` to the `sub` at
`+0x13c`, and so on back to the start of the loop. Each time the walk reaches a sampled
instruction, it adds that instruction's samples to the chain's total. Instructions on the
longest run that each need the one before, the critical path, are marked `*`:

```
  offset  instruction                   count  total  operands written by
* +0x144  add w4, w12, w11                        0  w11 from +0x138, w12 from +0x13c
* +0x13c  sub w12, w15, w12                       0  w12 from +0x130, w15 from +0x134
  +0x138  ldr w11, [x1, #0x2c]            59     59  x1 from +0x0c0
* +0x134  lsl w15, w12, #5                       59  w12 from +0x130
* +0x130  add w12, w12, w11                      59  w11 from +0x128, w12 from +0x12c
* +0x12c  sub w12, w15, w12                      59  w12 from +0x120, w15 from +0x124
  +0x128  ldr w11, [x1, #0x28]             4     63  x1 from +0x0c0
* +0x124  lsl w15, w12, #5                       63  w12 from +0x120
* +0x120  add w12, w12, w11                      63  w11 from +0x118, w12 from +0x11c
* +0x11c  sub w12, w15, w12                      63  w12 from +0x110, w15 from +0x114
  +0x118  ldr w11, [x1, #0x24]             1     64  x1 from +0x0c0
* +0x114  lsl w15, w12, #5                       64  w12 from +0x110
* +0x110  add w12, w11, w12                      64  w11 from +0x10c, w12 from +0x108
* +0x10c  sub w11, w15, w11                      64  w11 from +0x100, w15 from +0x104
  +0x108  ldr w12, [x1, #0x20]             2     66  x1 from +0x0c0
* +0x104  lsl w15, w11, #5                       66  w11 from +0x100
* +0x100  add w11, w12, w14                      66  w12 from +0x0fc, w14 from +0x0f8
* +0x0fc  sub w12, w11, w12                      66  w11 from +0x0f4, w12 from +0x0f0
  +0x0f8  ldr w14, [x1, #0x1c]             1     67  x1 from +0x0c0
* +0x0f4  lsl w11, w12, #5                       67  w12 from +0x0f0
* +0x0f0  add w12, w12, w11                      67  w11 from +0x0e8, w12 from +0x0ec
* +0x0ec  sub w12, w14, w12                      67  w12 from +0x0e0, w14 from +0x0e4
  +0x0e8  ldr w11, [x1, #0x18]                   67  x1 from +0x0c0
* +0x0e4  lsl w14, w12, #5                       67  w12 from +0x0e0
* +0x0e0  add w12, w12, w11                1     68  w11 from +0x0d8, w12 from +0x0dc
* +0x0dc  sub w12, w14, w12                      68  w12 from +0x0d0, w14 from +0x0d4
  +0x0d8  ldr w11, [x1, #0x14]                   68  x1 from +0x0c0
* +0x0d4  lsl w14, w12, #5                       68  w12 from +0x0d0
* +0x0d0  add w12, w11, w12                      68  w11 from +0x0cc, w12 from +0x0c8
* +0x0cc  sub w11, w11, w4                       68  w11 from +0x0c4, w4 from +0x144 on the previous iteration
  +0x0c8  ldr w12, [x1, #0x10]                   68  x1 from +0x0c0
* +0x0c4  lsl w11, w4, #5                        68  w4 from +0x144 on the previous iteration
  +0x0c0  add x1, x10, w2, sxtw #2         4     72

```

The result:

```
chain: 33 instructions, 72 samples (of 72 in the method); critical path (*): 24 instructions
```

All of the method's 72 samples are on the chain: 67 on the loads, 1 on the arithmetic,
and 4 on the address computation the loads share. The critical path is 24
instructions per iteration, or three per element (`lsl`, `sub`, `add`), each waiting for
the one before. That matches the measured speed: on an Apple M2 the loop runs at about
0.88 ns, roughly 3 cycles, per element, whether the array fits in L1 or not.

### Delta decoding

A different chain, from a different loop: delta decoding, where each value is stored as
the difference from the one before. Decoding adds each delta to the previous decoded
value, so every element waits for the one before it:

```java
static void decodeRunning(int[] data) {
    int previous = data[0];
    for (int i = 1; i < data.length; i++) {
        previous += data[i];
        data[i] = previous;
    }
}
```

C2 unrolls this 8 times too, and keeps `previous` in a register. One second of decoding a
65,536-element array, the unrolled loop abridged as before:

```
Delta.decodeRunning([I)V
  tier 4 (C2), compile id 365, 456 bytes at 0x00000001402d6000, 68 samples

  pc                  bytes        instruction                      count        %
  ...
  0x00000001402d6090  4b c9 30 8b  add x11, x10, w16, sxtw #2
  0x00000001402d6094  6d 11 40 b9  ldr w13, [x11, #0x10]
  0x00000001402d6098  cc 01 0d 0b  add w12, w14, w13
  0x00000001402d609c  6c 11 00 b9  str w12, [x11, #0x10]                4    5.88%
  0x00000001402d60a0  6e 15 40 b9  ldr w14, [x11, #0x14]
  0x00000001402d60a4  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60a8  6c 15 00 b9  str w12, [x11, #0x14]                1    1.47%
  0x00000001402d60ac  6e 19 40 b9  ldr w14, [x11, #0x18]                6    8.82%
  0x00000001402d60b0  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60b4  6c 19 00 b9  str w12, [x11, #0x18]                5    7.35%
  0x00000001402d60b8  6e 1d 40 b9  ldr w14, [x11, #0x1c]
  0x00000001402d60bc  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60c0  6c 1d 00 b9  str w12, [x11, #0x1c]                4    5.88%
  0x00000001402d60c4  6e 21 40 b9  ldr w14, [x11, #0x20]
  0x00000001402d60c8  cc 01 0c 0b  add w12, w14, w12
  0x00000001402d60cc  6c 21 00 b9  str w12, [x11, #0x20]                6    8.82%
  0x00000001402d60d0  6e 25 40 b9  ldr w14, [x11, #0x24]                2    2.94%
  0x00000001402d60d4  cd 01 0c 0b  add w13, w14, w12                    2    2.94%
  0x00000001402d60d8  6d 25 00 b9  str w13, [x11, #0x24]                2    2.94%
  0x00000001402d60dc  6e 29 40 b9  ldr w14, [x11, #0x28]                7   10.29%
  0x00000001402d60e0  cc 01 0d 0b  add w12, w14, w13
  0x00000001402d60e4  6c 29 00 b9  str w12, [x11, #0x28]                3    4.41%
  0x00000001402d60e8  6e 2d 40 b9  ldr w14, [x11, #0x2c]                5    7.35%
  0x00000001402d60ec  ce 01 0c 0b  add w14, w14, w12
  0x00000001402d60f0  10 22 00 11  add w16, w16, #0x8                   4    5.88%
  0x00000001402d60f4  6e 2d 00 b9  str w14, [x11, #0x2c]               17   25.00%
  0x00000001402d60f8  1f 02 02 6b  cmp w16, w2
  0x00000001402d60fc  ab fc ff 54  b.lt 0x1402d6090 <+0x90>
  0x00000001402d6100  8b 1b 40 f9  ldr x11, [x28, #0x30]
  ...
```

This time the samples are spread over the loads and stores, with the most, 17 of 68, on
the last store, `str w14, [x11, #0x2c]` at `+0x0f4`. The rule finds one recurrence,
carried through `w14`. It walks back from `add w14, w14, w12` at `+0x0ec`, the
instruction producing the next iteration's `previous`, as before. It also adds the stores
that write out a value the chain produced. A store's operands do different jobs: in
`str w14, [x11, #0x2c]`, `w14` is the value written out, so the store depends on the
chain through it, while `x11` only addresses the destination. So a store joins the chain
through its stored register (marked `(stored)`), not through its address. A store through
`x11` of a value from outside the chain would stay out, even though `x11` comes from the
chain's address computation.

```
  offset  instruction                   count  total  operands written by
  +0x0f4  str w14, [x11, #0x2c]           17     17  x11 from +0x090, w14 (stored) from +0x0ec
* +0x0ec  add w14, w14, w12                      17  w12 from +0x0e0, w14 from +0x0e8
  +0x0e8  ldr w14, [x11, #0x2c]            5     22  x11 from +0x090
  +0x0e4  str w12, [x11, #0x28]            3     25  x11 from +0x090, w12 (stored) from +0x0e0
* +0x0e0  add w12, w14, w13                      25  w13 from +0x0d4, w14 from +0x0dc
  +0x0dc  ldr w14, [x11, #0x28]            7     32  x11 from +0x090
  +0x0d8  str w13, [x11, #0x24]            2     34  x11 from +0x090, w13 (stored) from +0x0d4
* +0x0d4  add w13, w14, w12                2     36  w12 from +0x0c8, w14 from +0x0d0
  +0x0d0  ldr w14, [x11, #0x24]            2     38  x11 from +0x090
  +0x0cc  str w12, [x11, #0x20]            6     44  x11 from +0x090, w12 (stored) from +0x0c8
* +0x0c8  add w12, w14, w12                      44  w12 from +0x0bc, w14 from +0x0c4
  +0x0c4  ldr w14, [x11, #0x20]                  44  x11 from +0x090
  +0x0c0  str w12, [x11, #0x1c]            4     48  x11 from +0x090, w12 (stored) from +0x0bc
* +0x0bc  add w12, w14, w12                      48  w12 from +0x0b0, w14 from +0x0b8
  +0x0b8  ldr w14, [x11, #0x1c]                  48  x11 from +0x090
  +0x0b4  str w12, [x11, #0x18]            5     53  x11 from +0x090, w12 (stored) from +0x0b0
* +0x0b0  add w12, w14, w12                      53  w12 from +0x0a4, w14 from +0x0ac
  +0x0ac  ldr w14, [x11, #0x18]            6     59  x11 from +0x090
  +0x0a8  str w12, [x11, #0x14]            1     60  x11 from +0x090, w12 (stored) from +0x0a4
* +0x0a4  add w12, w14, w12                      60  w12 from +0x098, w14 from +0x0a0
  +0x0a0  ldr w14, [x11, #0x14]                  60  x11 from +0x090
  +0x09c  str w12, [x11, #0x10]            4     64  x11 from +0x090, w12 (stored) from +0x098
* +0x098  add w12, w14, w13                      64  w13 from +0x094, w14 from +0x0ec on the previous iteration
  +0x094  ldr w13, [x11, #0x10]                  64  x11 from +0x090
  +0x090  add x11, x10, w16, sxtw #2             64
```

```
chain: 25 instructions, 64 samples (of 68 in the method); critical path (*): 8 instructions
```

The critical path is one `add` per element, each adding a delta to the previous
element's result: delta decoding's dependency, and nothing else. The loads bring in the
deltas and the stores write out the decoded values, so 64 of the 68 samples are on the
chain. The other 4 are on the loop counter's `add w16, w16, #0x8`, which the chain neither
reads nor feeds.

Written as `data[i] += data[i - 1]` instead, the same decoding isn't found. C2 keeps the
running value in a register within an iteration, but each iteration starts by reloading
the previous element from the array (`ldp w13, w12, [x11, #0xc]`). The dependency from
one iteration to the next goes through a store and a load, which the analysis doesn't
track, and the 8 `add`s within an iteration are below the straight-line threshold.

The rule reports chains like this one as `LOOP_CARRIED` findings when the critical path is
at least 8 instructions. It also reports straight-line chains, of at least 16 instructions
within one pass through the code. Findings are ranked by the samples on their
instructions:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<DependencyChain> chains = new LongDependencyChainRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
DependencyChain chain = chains.get(0);
chain.instructions();     // the 33 instructions the carried h depends on
chain.criticalPath();     // the 24 that must run one after another
chain.samples();          // 72
```

The analysis tracks registers only. Condition flags are left out, so a compare followed
by a branch isn't counted as a data dependency, and so are memory dependencies through
stores and loads. Register usage is modelled for AArch64 (`Aarch64RegisterModel`) and
x86-64 (`X86RegisterModel`). `MissedVectorisationRule` works on both; the other rules
match AArch64 instructions and are tested on AArch64 only.

## Finding loops that aren't unrolled

C2 unrolls counted loops: it copies the body several times per iteration, so the loop's
overhead (the counter, the compare and branch, any range check) is paid once for several
elements, and the CPU gets several elements' work to overlap. Unrolling is also where
vectorisation starts. C2's superword pass looks for isomorphic operations in the unrolled
copies (the same operation on adjacent elements) and packs them into vector
instructions. A loop that isn't unrolled can't be vectorised.

C2 only unrolls loops it can treat as counted: an `int` counter stepped by a constant up
to a limit that doesn't change. A method call in the condition that isn't inlined breaks
that, because C2 can't know what it returns next time. (A `long` counter doesn't, from
JDK 17 on: C2 splits the loop into an outer loop around an inner one counted by an `int`,
and unrolls that.)

### Vectorised, and not

The same kernel, `c[i] = a[i] + b[i]`, with two conditions. With `limit` kept out of line
(`-XX:CompileCommand=dontinline`), as a large method would be:

```java
static void add(int[] a, int[] b, int[] c) {
    for (int i = 0; i < a.length; i++) {
        c[i] = a[i] + b[i];
    }
}

static int limit(int[] a) {
    return a.length;
}

static void addWithCallInCondition(int[] a, int[] b, int[] c) {
    for (int i = 0; i < limit(a); i++) {
        c[i] = a[i] + b[i];
    }
}
```

On 4,096-element arrays, on an Apple M2:

| | JDK 17 (Linux) | JDK 21 (Linux) | JDK 25 (Linux) | JDK 25 (macOS) |
|---|---|---|---|---|
| `add` | 0.066 ns/element | 0.067 | 0.066 | 0.063 |
| `addWithCallInCondition` | 3.70 ns/element | 3.78 | 3.62 | 1.86 |

C2 vectorises `add`. Here is its main loop on JDK 25, from the instruction profile:
8 copies of a 4-lane vector add, so 32 elements per iteration (`add w11, w18, #0x20`).
`ldr q` and `str q` move 4 `int`s at a time. The disassembler doesn't print vector
instructions yet; the `.word 0x4eb18610` is `add v16.4s, v16.4s, v17.4s`.

```
Vec.add([I[I[I)V
  tier 4 (C2), compile id 334, 680 bytes at 0x0000ffff73f63a80, 91 samples

  pc                  bytes        instruction                         count        %
  ...
  0x0000ffff73f63b60  f2 03 0b 2a  mov w18, w11
  0x0000ffff73f63b64  4b 7e 7e 93  sbfiz x11, x18, #2, #32                 1    1.10%
  0x0000ffff73f63b68  cf 01 0b 8b  add x15, x14, x11
  0x0000ffff73f63b6c  50 00 0b 8b  add x16, x2, x11
  0x0000ffff73f63b70  f0 05 c0 3d  ldr q16, [x15, #0x10]
  0x0000ffff73f63b74  11 06 c0 3d  ldr q17, [x16, #0x10]
  0x0000ffff73f63b78  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63b7c  6b 00 0b 8b  add x11, x3, x11
  0x0000ffff73f63b80  70 05 80 3d  str q16, [x11, #0x10]
  0x0000ffff73f63b84  10 0a c0 3d  ldr q16, [x16, #0x20]
  0x0000ffff73f63b88  f1 09 c0 3d  ldr q17, [x15, #0x20]
  0x0000ffff73f63b8c  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63b90  70 09 80 3d  str q16, [x11, #0x20]
  0x0000ffff73f63b94  10 0e c0 3d  ldr q16, [x16, #0x30]                   3    3.30%
  0x0000ffff73f63b98  f1 0d c0 3d  ldr q17, [x15, #0x30]                  10   10.99%
  0x0000ffff73f63b9c  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63ba0  70 0d 80 3d  str q16, [x11, #0x30]                   1    1.10%
  0x0000ffff73f63ba4  10 12 c0 3d  ldr q16, [x16, #0x40]                   1    1.10%
  0x0000ffff73f63ba8  f1 11 c0 3d  ldr q17, [x15, #0x40]                   1    1.10%
  0x0000ffff73f63bac  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63bb0  70 11 80 3d  str q16, [x11, #0x40]
  0x0000ffff73f63bb4  10 16 c0 3d  ldr q16, [x16, #0x50]
  0x0000ffff73f63bb8  f1 15 c0 3d  ldr q17, [x15, #0x50]
  0x0000ffff73f63bbc  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63bc0  70 15 80 3d  str q16, [x11, #0x50]                   7    7.69%
  0x0000ffff73f63bc4  10 1a c0 3d  ldr q16, [x16, #0x60]                   5    5.49%
  0x0000ffff73f63bc8  f1 19 c0 3d  ldr q17, [x15, #0x60]                  10   10.99%
  0x0000ffff73f63bcc  10 86 b1 4e  .word 0x4eb18610                        1    1.10%
  0x0000ffff73f63bd0  70 19 80 3d  str q16, [x11, #0x60]
  0x0000ffff73f63bd4  f0 1d c0 3d  ldr q16, [x15, #0x70]
  0x0000ffff73f63bd8  11 1e c0 3d  ldr q17, [x16, #0x70]
  0x0000ffff73f63bdc  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63be0  70 1d 80 3d  str q16, [x11, #0x70]                   5    5.49%
  0x0000ffff73f63be4  f0 21 c0 3d  ldr q16, [x15, #0x80]                   2    2.20%
  0x0000ffff73f63be8  11 22 c0 3d  ldr q17, [x16, #0x80]                  17   18.68%
  0x0000ffff73f63bec  10 86 b1 4e  .word 0x4eb18610
  0x0000ffff73f63bf0  70 21 80 3d  str q16, [x11, #0x80]                  24   26.37%
  0x0000ffff73f63bf4  4b 82 00 11  add w11, w18, #0x20
  0x0000ffff73f63bf8  7f 01 0c 6b  cmp w11, w12
  0x0000ffff73f63bfc  2b fb ff 54  b.lt 0xffff73f63b60 <+0xe0>
  ...
```

`addWithCallInCondition` does one element per iteration, with each array's range checked
every time (`cmp`/`b.hs`), the arrays and their lengths spilled to the stack and reloaded
around the call, and the call itself:

```
Vec.addWithCallInCondition([I[I[I)V
  tier 4 (C2), compile id 323, 536 bytes at 0x0000ffff97f63980, 43 samples

  pc                  bytes        instruction                     count        %
  ...
  0x0000ffff97f639e0  08 00 00 14  b 0xffff97f63a00 <+0x80>
  0x0000ffff97f639e4  1f 20 03 d5  nop
  0x0000ffff97f639e8  1f 20 03 d5  nop
  0x0000ffff97f639ec  1f 20 03 d5  nop
  0x0000ffff97f639f0  ee 3f 40 a9  ldp x14, x15, [sp]
  0x0000ffff97f639f4  f2 0b 40 f9  ldr x18, [sp, #0x10]
  0x0000ffff97f639f8  f1 43 43 29  ldp w17, w16, [sp, #0x18]
  0x0000ffff97f639fc  e2 23 40 b9  ldr w2, [sp, #0x20]                18   41.86%
  0x0000ffff97f63a00  aa 7f 7e 93  sbfiz x10, x29, #2, #32
  0x0000ffff97f63a04  eb 01 0a 8b  add x11, x15, x10
  0x0000ffff97f63a08  cc 01 0a 8b  add x12, x14, x10
  0x0000ffff97f63a0c  bf 03 11 6b  cmp w29, w17
  0x0000ffff97f63a10  82 03 00 54  b.hs 0xffff97f63a80 <+0x100>
  0x0000ffff97f63a14  8d 11 40 b9  ldr w13, [x12, #0x10]               5   11.63%
  0x0000ffff97f63a18  bf 03 10 6b  cmp w29, w16
  0x0000ffff97f63a1c  22 04 00 54  b.hs 0xffff97f63aa0 <+0x120>
  0x0000ffff97f63a20  6c 11 40 b9  ldr w12, [x11, #0x10]              12   27.91%
  0x0000ffff97f63a24  8c 01 0d 0b  add w12, w12, w13
  0x0000ffff97f63a28  bf 03 02 6b  cmp w29, w2
  0x0000ffff97f63a2c  c2 04 00 54  b.hs 0xffff97f63ac4 <+0x144>
  0x0000ffff97f63a30  4a 02 0a 8b  add x10, x18, x10
  0x0000ffff97f63a34  4c 11 00 b9  str w12, [x10, #0x10]
  0x0000ffff97f63a38  ee 3f 00 a9  stp x14, x15, [sp]
  0x0000ffff97f63a3c  f2 0b 00 f9  str x18, [sp, #0x10]
  0x0000ffff97f63a40  f1 43 03 29  stp w17, w16, [sp, #0x18]
  0x0000ffff97f63a44  e2 23 00 b9  str w2, [sp, #0x20]                 1    2.33%
  0x0000ffff97f63a48  bd 07 00 11  add w29, w29, #0x1
  0x0000ffff97f63a4c  e1 03 0e aa  mov x1, x14
  0x0000ffff97f63a50  6c fe ff 97  bl 0xffff97f63400
  0x0000ffff97f63a54  1f 20 03 d5  nop                                 6   13.95%
  0x0000ffff97f63a58  9f 39 80 f2  movk xzr, #0x1cc
  0x0000ffff97f63a5c  1f 20 80 f2  movk xzr, #0x100                    1    2.33%
  0x0000ffff97f63a60  bf 03 00 6b  cmp w29, w0
  0x0000ffff97f63a64  6b fc ff 54  b.lt 0xffff97f639f0 <+0x70>
  ...
```

### The rule

`NotUnrolledLoopRule` reports hot loops that process one element per iteration. For each
innermost loop with at least 10% of the method's samples, it finds the induction
variables: recurrences made only of adding or subtracting a constant and moving between
registers. Here that's `w29`, stepped by `add w29, w29, #0x1` at `+0x0c8`. It then works
out what a step covers from how the variable is used:

- as an index, scaled or extended in an operand (`add x10, x11, w13, sxtw #2`) or by an
  instruction of its own (`sbfiz x10, x29, #2, #32` at `+0x080`), a step counts
  elements;
- as the base register of a load or store, it counts bytes, so a step of one register's
  width is one element.

A loop is reported if every induction variable classified that way covers one element.
In `add`, the vector loop's `w18` is scaled by `sbfiz` too, but steps by 32, so it isn't
reported. Neither are the scalar loops C2 puts before and after it to reach an aligned
start and finish the remainder: they step by one, but have few samples.

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<NotUnrolledLoop> loops = new NotUnrolledLoopRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
NotUnrolledLoop loop = loops.get(0);
loop.header();             // the address of +0x080
loop.inductionVariable();  // x29
loop.step();               // 1
loop.calls();              // the bl at +0x0d0
loop.samples();            // 43, all of the method's
```

The finding lists the loop's calls, since a call that isn't inlined is the usual reason,
and whether the induction variable is 64-bit.

## Finding pointer chasing

Walking a linked structure, each load's address is the result of the load before it:
`node = node.next`. An array walk computes every address from an index, so the CPU can
issue many loads at once and the prefetcher can run ahead; a pointer chase can't start a
load until the previous one has completed. The loop runs at the latency of its loads,
and a cache miss on one node stalls everything after it.

### LinkedList

Summing the same 4,096 `Integer`s, through the same `List` interface:

```java
static int sum(List<Integer> list) {
    int sum = 0;
    for (int value : list) {
        sum += value;
    }
    return sum;
}
```

| | JDK 17 (Linux) | JDK 21 (Linux) | JDK 25 (Linux) | JDK 25 (macOS) |
|---|---|---|---|---|
| `ArrayList` | 0.35 ns/element | 0.36 | 0.35 | 0.35 |
| `LinkedList` | 1.33 ns/element | 1.26 | 1.26 | 1.84 |

C2 inlines the iterator and unrolls the `LinkedList` loop twice, so each iteration follows
`next` twice. Here is the loop from the JDK 25 profile on Linux, where the small heap
uses compressed references that need no decoding:

```
Chase.sum(Ljava/util/List;)I
  tier 4 (C2), compile id 339, 1000 bytes at 0x0000ffffa3f63e80, 99 samples

  pc                  bytes        instruction                         count        %
  ...
  0x0000ffffa3f63f80  e5 03 06 2a  mov w5, w6
  0x0000ffffa3f63f84  11 12 40 b9  ldr w17, [x16, #0x10]
  0x0000ffffa3f63f88  0e 0e 40 b9  ldr w14, [x16, #0xc]                    1    1.01%
  0x0000ffffa3f63f8c  c3 09 40 b9  ldr w3, [x14, #0x8]                    56   56.57%
  0x0000ffffa3f63f90  7f 00 0d 6b  cmp w3, w13                             4    4.04%
  0x0000ffffa3f63f94  21 0c 00 54  b.ne 0xffffa3f64118 <+0x298>
  0x0000ffffa3f63f98  30 12 40 b9  ldr w16, [x17, #0x10]
  0x0000ffffa3f63f9c  ce 0d 40 b9  ldr w14, [x14, #0xc]
  0x0000ffffa3f63fa0  22 0e 40 b9  ldr w2, [x17, #0xc]                    13   13.13%
  0x0000ffffa3f63fa4  c0 01 00 0b  add w0, w14, w0
  0x0000ffffa3f63fa8  62 03 00 34  cbz w2, 0xffffa3f64014 <+0x194>
  0x0000ffffa3f63fac  43 08 40 b9  ldr w3, [x2, #0x8]                      5    5.05%
  0x0000ffffa3f63fb0  7f 00 0a 6b  cmp w3, w10
  0x0000ffffa3f63fb4  ee 03 02 aa  mov x14, x2
  0x0000ffffa3f63fb8  c1 0a 00 54  b.ne 0xffffa3f64110 <+0x290>
  0x0000ffffa3f63fbc  c2 0d 40 b9  ldr w2, [x14, #0xc]                    13   13.13%
  0x0000ffffa3f63fc0  a6 08 00 11  add w6, w5, #0x2                        5    5.05%
  0x0000ffffa3f63fc4  40 00 00 0b  add w0, w2, w0
  0x0000ffffa3f63fc8  df 00 01 6b  cmp w6, w1
  0x0000ffffa3f63fcc  ab fd ff 54  b.lt 0xffffa3f63f80 <+0x100>            1    1.01%
  ...
```

`node.next` is loaded at `+0x104` into `w17`, and loaded again from that node at
`+0x118`, into `w16`: the register the next iteration loads from. Hanging off each node
are the loads of its `item` (`+0x108`, `+0x120`), the `Integer`'s class word, checked
before the cast (`+0x10c`, `+0x12c`), and its `value` (`+0x11c`, `+0x13c`). Each of those
waits on the node, and the first class check alone has 56 of the 99 samples.

With a heap that needs shifted compressed references (macOS here), each reference is
decoded with `lsl #3` before it can be used as an address, and that is on the chase too:

```
Chase.sum(Ljava/util/List;)I
  tier 4 (C2), compile id 372, 1040 bytes at 0x000000011494e340, 86 samples

  pc                  bytes        instruction                      count        %
  ...
  0x000000011494e440  e4 03 02 2a  mov w4, w2
  0x000000011494e444  31 f0 7d d3  lsl x17, x1, #3
  0x000000011494e448  2c 12 40 b9  ldr w12, [x17, #0x10]
  0x000000011494e44c  31 0e 40 b9  ldr w17, [x17, #0xc]
  0x000000011494e450  31 f2 7d d3  lsl x17, x17, #3
  0x000000011494e454  22 0a 40 b9  ldr w2, [x17, #0x8]                 42   48.84%
  0x000000011494e458  5f 00 0f 6b  cmp w2, w15
  0x000000011494e45c  85 f1 7d d3  lsl x5, x12, #3                      1    1.16%
  0x000000011494e460  e1 0c 00 54  b.ne 0x11494e5fc <+0x2bc>            2    2.33%
  0x000000011494e464  a1 10 40 b9  ldr w1, [x5, #0x10]                  4    4.65%
  0x000000011494e468  a2 0c 40 b9  ldr w2, [x5, #0xc]                   1    1.16%
  0x000000011494e46c  27 0e 40 b9  ldr w7, [x17, #0xc]                 10   11.63%
  0x000000011494e470  51 f0 7d d3  lsl x17, x2, #3
  0x000000011494e474  e0 00 00 0b  add w0, w7, w0                       1    1.16%
  0x000000011494e478  71 03 00 b4  cbz x17, 0x11494e4e4 <+0x1a4>
  0x000000011494e47c  27 0a 40 b9  ldr w7, [x17, #0x8]                  9   10.47%
  0x000000011494e480  ff 00 0d 6b  cmp w7, w13                          1    1.16%
  0x000000011494e484  81 0b 00 54  b.ne 0x11494e5f4 <+0x2b4>
  0x000000011494e488  22 0e 40 b9  ldr w2, [x17, #0xc]                 11   12.79%
  0x000000011494e48c  40 00 00 0b  add w0, w2, w0
  0x000000011494e490  82 08 00 11  add w2, w4, #0x2                     1    1.16%
  0x000000011494e494  5f 00 06 6b  cmp w2, w6                           3    3.49%
  0x000000011494e498  4b fd ff 54  b.lt 0x11494e440 <+0x100>
  ...
```

### The rule

`PointerChasingRule` looks for a loop-carried recurrence (see the dependency chains
above) with a load on its cycle. A load only reads registers to form its address, so a
load on the cycle has its address computed from the value the previous iteration carried
in, whether as a base (`node.next`) or an index (`i = next[i]`). Decoding and register
moves on the way are part of the cycle. For each, it reports:

- the cycle and the loads on it, one per step of the chase;
- the dereferences: the other loads in the loop whose address comes from the chase,
  such as the `item`, class word and `value` loads above;
- the loop's samples, and the samples on the chase and everything in the loop that
  depends on it: the dereferences, and the instructions using what they load. A stalled
  load's samples often land on the instruction waiting for it instead (skid), and how
  far depends on the CPU: on one CI runner's Neoverse cores, 20 of this loop's samples
  were on the `cmp` after the first class word's load, where the M2 puts them on the
  load itself.

Loops with less than 10% of the method's samples, like the one-node loop C2 adds to
finish an odd count, aren't reported. For the Linux profile above:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<PointerChase> chases = new PointerChasingRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
PointerChase chase = chases.get(0);
chase.carriedRegister();  // x16
chase.cycle();            // ldr w17, [x16, #0x10]; ldr w16, [x17, #0x10]
chase.dereferences();     // 6 loads: each node's item, its class and its value
chase.samples();          // 98, the loop's
chase.chaseSamples();     // 92 of them on the chase and what depends on it: all but the counter's
```

The `ArrayList` loop has no finding: its recurrences are the sum and the index, neither
through a load.

## Finding null checks that have failed

C2 doesn't test references for null. It compiles a field access as a plain load and
relies on the load faulting if the reference is null: an *implicit* null check, which
costs nothing until it fails. When it does fail, the signal handler turns the fault into
the `NullPointerException`. If that happens a few times at the same location, or the
profile recorded a null there before the method was compiled, C2 recompiles the method
with an *explicit* test and branch at that location, and compiles the failure as a throw
of a preallocated exception: C2's fast throw, which has no stack trace
(`-XX:+OmitStackTraceInFastThrow`, the default).

So a test and a branch to a throw before a field access says that nulls have been seen
there. And since those exceptions have no stack trace, it says where the
`NullPointerException`s without one come from.

### Summing boxes

```java
static int sumBoxes(Box[] boxes) {
    int sum = 0;
    for (int i = 0; i < boxes.length; i++) {
        sum += boxes[i].value;
    }
    return sum;
}
```

Compiled by C2 on JDK 25 (Linux), the main loop, unrolled 4 times, loads 4 references
and then each `value` (`ldr w14, [x15, #0xc]`), with no tests:

```
Trap.sumBoxes([LTrap$Box;)I
  tier 4 (C2), compile id 305, 376 bytes at 0x0000ffff93f63340, 96 samples

  pc                  bytes        instruction                       count        %
  ...
  0x0000ffff93f633b0  2e c8 2a 8b  add x14, x1, w10, sxtw #2
  0x0000ffff93f633b4  d0 3d 42 29  ldp w16, w15, [x14, #0x10]
  0x0000ffff93f633b8  d2 19 40 b9  ldr w18, [x14, #0x18]
  0x0000ffff93f633bc  10 0e 40 b9  ldr w16, [x16, #0xc]
  0x0000ffff93f633c0  d1 1d 40 b9  ldr w17, [x14, #0x1c]
  0x0000ffff93f633c4  ee 0d 40 b9  ldr w14, [x15, #0xc]                 13   13.54%
  0x0000ffff93f633c8  52 0e 40 b9  ldr w18, [x18, #0xc]                  9    9.38%
  0x0000ffff93f633cc  0f 00 10 0b  add w15, w0, w16
  0x0000ffff93f633d0  31 0e 40 b9  ldr w17, [x17, #0xc]                 63   65.63%
  0x0000ffff93f633d4  ef 01 0e 0b  add w15, w15, w14
  0x0000ffff93f633d8  ee 01 12 0b  add w14, w15, w18
  0x0000ffff93f633dc  4a 11 00 11  add w10, w10, #0x4
  0x0000ffff93f633e0  c0 01 11 0b  add w0, w14, w17
  0x0000ffff93f633e4  5f 01 0d 6b  cmp w10, w13
  0x0000ffff93f633e8  4b fe ff 54  b.lt 0xffff93f633b0 <+0x70>          11   11.46%
  ...
```

The same method after compiled code has thrown 5 `NullPointerException`s from it. Three
were enough on JDK 17, 21 and 25; fewer left the code unchanged. Each reference is now
tested with `cbz` before it is used, in the code before the loop (`+0x03c`), in each of
the 4 copies in the loop (`+0x088` to `+0x0a0`) and in the loop after it (`+0x0f0`),
and every test branches to `+0x10c`. That loads the preallocated exception (`mov x1`,
`movk`), clears one of its fields, pops the frame and jumps out to the exception handler:

```
Trap.sumBoxes([LTrap$Box;)I
  tier 4 (C2), compile id 313, 520 bytes at 0x0000ffff77f63900, 97 samples

  pc                  bytes        instruction                          count        %
  ...
  0x0000ffff77f63938  2d 10 40 b9  ldr w13, [x1, #0x10]
  0x0000ffff77f6393c  8d 06 00 34  cbz w13, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f63940  6c 0c 00 51  sub w12, w3, #0x3
  0x0000ffff77f63944  ea 03 00 32  orr w10, wzr, #0x1
  0x0000ffff77f63948  a0 0d 40 b9  ldr w0, [x13, #0xc]
  0x0000ffff77f6394c  9f 05 00 71  cmp w12, #0x1
  0x0000ffff77f63950  8d 04 00 54  b.le 0xffff77f639e0 <+0xe0>
  0x0000ffff77f63954  0d f4 81 52  mov w13, #0xfa0
  0x0000ffff77f63958  6e 00 0a 4b  sub w14, w3, w10
  0x0000ffff77f6395c  9f 01 0a 6b  cmp w12, w10
  0x0000ffff77f63960  cb 0d 00 51  sub w11, w14, #0x3
  0x0000ffff77f63964  eb b3 8b 1a  csel w11, wzr, w11, lt
  0x0000ffff77f63968  7f 81 3e 71  cmp w11, #0xfa0
  0x0000ffff77f6396c  ab 81 8b 1a  csel w11, w13, w11, hi
  0x0000ffff77f63970  6e 01 0a 0b  add w14, w11, w10
  0x0000ffff77f63974  1f 20 03 d5  nop
  0x0000ffff77f63978  1f 20 03 d5  nop
  0x0000ffff77f6397c  1f 20 03 d5  nop
  0x0000ffff77f63980  2b c8 2a 8b  add x11, x1, w10, sxtw #2
  0x0000ffff77f63984  6f 11 40 b9  ldr w15, [x11, #0x10]                   20   20.62%
  0x0000ffff77f63988  2f 04 00 34  cbz w15, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f6398c  71 15 40 b9  ldr w17, [x11, #0x14]                   13   13.40%
  0x0000ffff77f63990  f1 03 00 34  cbz w17, 0xffff77f63a0c <+0x10c>         2    2.06%
  0x0000ffff77f63994  70 19 40 b9  ldr w16, [x11, #0x18]                   13   13.40%
  0x0000ffff77f63998  b0 03 00 34  cbz w16, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f6399c  72 1d 40 b9  ldr w18, [x11, #0x1c]                    9    9.28%
  0x0000ffff77f639a0  72 03 00 34  cbz w18, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f639a4  eb 0d 40 b9  ldr w11, [x15, #0xc]
  0x0000ffff77f639a8  31 0e 40 b9  ldr w17, [x17, #0xc]
  0x0000ffff77f639ac  0f 0e 40 b9  ldr w15, [x16, #0xc]                     2    2.06%
  0x0000ffff77f639b0  10 00 0b 0b  add w16, w0, w11
  0x0000ffff77f639b4  10 02 11 0b  add w16, w16, w17
  0x0000ffff77f639b8  4b 0e 40 b9  ldr w11, [x18, #0xc]                    34   35.05%
  0x0000ffff77f639bc  10 02 0f 0b  add w16, w16, w15
  0x0000ffff77f639c0  4a 11 00 11  add w10, w10, #0x4                       2    2.06%
  0x0000ffff77f639c4  00 02 0b 0b  add w0, w16, w11
  0x0000ffff77f639c8  5f 01 0e 6b  cmp w10, w14
  0x0000ffff77f639cc  ab fd ff 54  b.lt 0xffff77f63980 <+0x80>
  0x0000ffff77f639d0  8b 1b 40 f9  ldr x11, [x28, #0x30]
  0x0000ffff77f639d4  7f 01 40 b9  ldr wzr, [x11]
  0x0000ffff77f639d8  5f 01 0c 6b  cmp w10, w12
  0x0000ffff77f639dc  eb fb ff 54  b.lt 0xffff77f63958 <+0x58>
  0x0000ffff77f639e0  5f 01 03 6b  cmp w10, w3
  0x0000ffff77f639e4  ca 02 00 54  b.ge 0xffff77f63a3c <+0x13c>
  0x0000ffff77f639e8  2b c8 2a 8b  add x11, x1, w10, sxtw #2
  0x0000ffff77f639ec  6b 11 40 b9  ldr w11, [x11, #0x10]
  0x0000ffff77f639f0  eb 00 00 34  cbz w11, 0xffff77f63a0c <+0x10c>
  0x0000ffff77f639f4  6c 0d 40 b9  ldr w12, [x11, #0xc]
  0x0000ffff77f639f8  4a 05 00 11  add w10, w10, #0x1
  0x0000ffff77f639fc  00 00 0c 0b  add w0, w0, w12
  0x0000ffff77f63a00  5f 01 03 6b  cmp w10, w3
  0x0000ffff77f63a04  2b ff ff 54  b.lt 0xffff77f639e8 <+0xe8>
  0x0000ffff77f63a08  0d 00 00 14  b 0xffff77f63a3c <+0x13c>
  0x0000ffff77f63a0c  eb 03 1f aa  mov x11, xzr
  0x0000ffff77f63a10  01 a2 83 d2  mov x1, #0x1d10
  0x0000ffff77f63a14  a1 fd bf f2  movk x1, #0xffed, lsl #16
  0x0000ffff77f63a18  01 00 c0 f2  movk x1, #0x0, lsl #32
  0x0000ffff77f63a1c  2a 50 00 91  add x10, x1, #0x14
  0x0000ffff77f63a20  8e 23 41 39  ldrb w14, [x28, #0x48]
  0x0000ffff77f63a24  0e 03 00 35  cbnz w14, 0xffff77f63a84 <+0x184>
  0x0000ffff77f63a28  4b 01 00 b9  str w11, [x10]
  0x0000ffff77f63a2c  fd 7b 42 a9  ldp x29, x30, [sp, #0x20]
  0x0000ffff77f63a30  ff c3 00 91  add sp, sp, #0x30
  0x0000ffff77f63a34  83 04 ee 17  b 0xffff77ae4c40
  ...
```

The tests cost little here: they are predicted, and the loop ran at the same speed. The
finding is the evidence that nulls reach this code.

### The rule

`FailedCheckRule` looks for conditional branches to a failure path: a throw (code that
pops the frame and jumps out of the method without returning) or an uncommon trap
(`mov w1, #request` with a negative request, then a call). A `cbz` or `cbnz` is a null
check if the register it tests is then used as an address, directly or once decoded.
Branches inside the failure paths, like the GC barrier's `cbnz` on the throw path, are
ignored. Checks that share a throw are one bytecode's check, copied by unrolling, so
they are one finding:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<FailedCheck> checks = new FailedCheckRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
FailedCheck check = checks.get(0);
check.kind();         // NULL_CHECK
check.failure();      // THROW
check.checks();       // the 6 cbz, at +0x03c, +0x088, +0x090, +0x098, +0x0a0 and +0x0f0
check.failurePath();  // the address of +0x10c
check.inLoop();       // true
check.samples();      // 95 of the method's 97, on the loops containing the checks
```

Null checks that branch to an uncommon trap rather than a throw aren't reported: C2
emits those where it can't fold the check into a load, such as before a cast, which lets
null through, speculating that the reference is never null. The `LinkedList` iteration
above has one, before each `item`'s class check; no null was ever seen there.

## Finding range checks that have failed

C2 removes the range checks from a counted loop: it checks once, before the loop, that
every index the loop will use is in bounds (loop predication), or splits the loop so the
main part needs no checks. When a check has failed, C2 recompiles the method with
explicit checks where it can't rule out failure, branching to a throw like the null
check's. Where it can't eliminate the checks from a loop at all, each iteration checks,
branching to an uncommon trap.

### Summing a prefix

```java
static int sumRange(int[] a, int n) {
    int sum = 0;
    for (int i = 0; i < n; i++) {
        sum += a[i];
    }
    return sum;
}
```

Called only with `n == a.length`, C2 on JDK 25 checks the bounds before the loop with
predicates that trap if they fail, and the loop has no checks. After one call with
`n == a.length + 1` has thrown `ArrayIndexOutOfBoundsException` from compiled code, it
compiles the method differently. The main loop, unrolled 16 times, is still unchecked,
but it now runs to the smaller of `n` and the length (the `csel` at `+0x03c`). An empty
array is tested before it (`cbz w15` at `+0x05c`), and the loop after it, which finishes
the remainder one element at a time, checks every index (`cmp w16, w15` and `b.hs` at
`+0x13c`). Both branch to the throw at `+0x174`:

```
Trap.sumRange([II)I
  tier 4 (C2), compile id 313, 568 bytes at 0x0000ffff8bf63d80, 98 samples

  pc                  bytes        instruction                          count        %
  ...
  0x0000ffff8bf63dac  2f 0c 40 b9  ldr w15, [x1, #0xc]
  0x0000ffff8bf63db0  ea 7d 40 93  sxtw x10, w15
  0x0000ffff8bf63db4  4b 7c 40 93  sxtw x11, w2
  0x0000ffff8bf63db8  7f 01 0a eb  cmp x11, x10
  0x0000ffff8bf63dbc  6a b1 8a 9a  csel x10, x11, x10, lt
  0x0000ffff8bf63dc0  4a 3d 00 d1  sub x10, x10, #0xf
  0x0000ffff8bf63dc4  e8 83 61 b2  mov x8, #-0x80000000
  0x0000ffff8bf63dc8  5f 01 08 eb  cmp x10, x8
  0x0000ffff8bf63dcc  eb 83 61 b2  mov x11, #-0x80000000
  0x0000ffff8bf63dd0  6a b1 8a 9a  csel x10, x11, x10, lt
  0x0000ffff8bf63dd4  f0 03 00 32  orr w16, wzr, #0x1
  0x0000ffff8bf63dd8  f2 03 0a 2a  mov w18, w10
  0x0000ffff8bf63ddc  cf 08 00 34  cbz w15, 0xffff8bf63ef4 <+0x174>
  0x0000ffff8bf63de0  20 10 40 b9  ldr w0, [x1, #0x10]
  0x0000ffff8bf63de4  5f 06 00 71  cmp w18, #0x1
  0x0000ffff8bf63de8  2d 06 00 54  b.le 0xffff8bf63eac <+0x12c>
  0x0000ffff8bf63dec  0e d0 87 52  mov w14, #0x3e80
  0x0000ffff8bf63df0  5f 02 10 6b  cmp w18, w16
  0x0000ffff8bf63df4  4b 02 10 4b  sub w11, w18, w16
  0x0000ffff8bf63df8  eb b3 8b 1a  csel w11, wzr, w11, lt
  0x0000ffff8bf63dfc  08 d0 87 52  mov w8, #0x3e80
  0x0000ffff8bf63e00  7f 01 08 6b  cmp w11, w8
  0x0000ffff8bf63e04  cb 81 8b 1a  csel w11, w14, w11, hi
  0x0000ffff8bf63e08  63 01 10 0b  add w3, w11, w16
  0x0000ffff8bf63e0c  1f 20 03 d5  nop
  0x0000ffff8bf63e10  31 c8 30 8b  add x17, x1, w16, sxtw #2
  0x0000ffff8bf63e14  2a 32 42 29  ldp w10, w12, [x17, #0x10]
  0x0000ffff8bf63e18  0a 00 0a 0b  add w10, w0, w10
  ...                                          (16 loads and adds, no checks)
  0x0000ffff8bf63e7c  2d 4a 40 b9  ldr w13, [x17, #0x48]
  0x0000ffff8bf63e80  4a 01 00 0b  add w10, w10, w0
  0x0000ffff8bf63e84  2c 4e 40 b9  ldr w12, [x17, #0x4c]                   93   94.90%
  0x0000ffff8bf63e88  4b 01 0d 0b  add w11, w10, w13
  0x0000ffff8bf63e8c  10 42 00 11  add w16, w16, #0x10
  0x0000ffff8bf63e90  60 01 0c 0b  add w0, w11, w12
  0x0000ffff8bf63e94  1f 02 03 6b  cmp w16, w3
  0x0000ffff8bf63e98  cb fb ff 54  b.lt 0xffff8bf63e10 <+0x90>
  0x0000ffff8bf63e9c  8a 1b 40 f9  ldr x10, [x28, #0x30]                    1    1.02%
  0x0000ffff8bf63ea0  5f 01 40 b9  ldr wzr, [x10]
  0x0000ffff8bf63ea4  1f 02 12 6b  cmp w16, w18
  0x0000ffff8bf63ea8  4b fa ff 54  b.lt 0xffff8bf63df0 <+0x70>
  0x0000ffff8bf63eac  1f 02 02 6b  cmp w16, w2
  0x0000ffff8bf63eb0  2a 01 00 54  b.ge 0xffff8bf63ed4 <+0x154>
  0x0000ffff8bf63eb4  2a c8 30 8b  add x10, x1, w16, sxtw #2                1    1.02%
  0x0000ffff8bf63eb8  1f 02 0f 6b  cmp w16, w15
  0x0000ffff8bf63ebc  c2 01 00 54  b.hs 0xffff8bf63ef4 <+0x174>
  0x0000ffff8bf63ec0  4b 11 40 b9  ldr w11, [x10, #0x10]
  0x0000ffff8bf63ec4  10 06 00 11  add w16, w16, #0x1
  0x0000ffff8bf63ec8  00 00 0b 0b  add w0, w0, w11
  0x0000ffff8bf63ecc  1f 02 02 6b  cmp w16, w2
  0x0000ffff8bf63ed0  2b ff ff 54  b.lt 0xffff8bf63eb4 <+0x134>
  0x0000ffff8bf63ed4  fd 7b 41 a9  ldp x29, x30, [sp, #0x10]
  0x0000ffff8bf63ed8  ff 83 00 91  add sp, sp, #0x20
  0x0000ffff8bf63edc  88 17 40 f9  ldr x8, [x28, #0x28]
  0x0000ffff8bf63ee0  ff 63 28 eb  cmp sp, x8
  0x0000ffff8bf63ee4  08 05 00 54  b.hi 0xffff8bf63f84 <+0x204>
  0x0000ffff8bf63ee8  c0 03 5f d6  ret
  0x0000ffff8bf63eec  e0 03 1f 2a  mov w0, wzr
  0x0000ffff8bf63ef0  f9 ff ff 17  b 0xffff8bf63ed4 <+0x154>
  0x0000ffff8bf63ef4  eb 03 1f aa  mov x11, xzr
  0x0000ffff8bf63ef8  01 c8 83 d2  mov x1, #0x1e40
  0x0000ffff8bf63efc  a1 fd bf f2  movk x1, #0xffed, lsl #16
  0x0000ffff8bf63f00  01 00 c0 f2  movk x1, #0x0, lsl #32
  0x0000ffff8bf63f04  2a 50 00 91  add x10, x1, #0x14
  0x0000ffff8bf63f08  8e 23 41 39  ldrb w14, [x28, #0x48]
  0x0000ffff8bf63f0c  4e 01 00 35  cbnz w14, 0xffff8bf63f34 <+0x1b4>
  0x0000ffff8bf63f10  4b 01 00 b9  str w11, [x10]
  0x0000ffff8bf63f14  fd 7b 41 a9  ldp x29, x30, [sp, #0x10]
  0x0000ffff8bf63f18  ff 83 00 91  add sp, sp, #0x20
  0x0000ffff8bf63f1c  c9 d9 ed 17  b 0xffff8bada640
  ...
```

Here the checks are outside the hot loop, so they cost almost nothing (1 of 98 samples);
again the finding is the evidence that out-of-bounds indexes reach this code.

### Range checks a loop kept

A loop C2 can't treat as counted keeps its range checks, and each fails to an uncommon
trap with reason `range_check`. `addWithCallInCondition`, in
[Finding loops that aren't unrolled](#finding-loops-that-arent-unrolled), checks each of
its 3 arrays on every iteration (the `b.hs` at `+0x090`, `+0x09c` and `+0x0ac`), and so
does a loop counted by a `long` that indexes with `(int) i`, after each of its unrolled
copies. `FailedCheckRule` reports these too, with the trap request:

```java
check.kind();         // RANGE_CHECK
check.failure();      // TRAP
check.trapRequest();  // -0x1c
check.trapReason();   // 3, range_check
check.trapAction();   // 3, make_not_entrant
check.checks();       // the 3 b.hs
```

A range check is a compare followed by an unsigned branch (`b.hs`, `b.hi`, `b.ls` or
`b.lo`), or a `cbz` of a length such a compare uses. Range checks that trap are only
reported in loops: one outside a loop is a single access that nothing could eliminate.
Traps for other reasons, such as the predicates that stand in for eliminated checks,
aren't reported, and neither are the reasons' numbers above 3, which change between JDK
versions.

## Finding unpredictable branches

A branch the CPU predicts wrongly costs a pipeline's worth of work: everything fetched
down the wrong path is thrown away, and the CPU starts again at the right one. A branch
whose direction depends on the data a loop reads, such as a `switch` over the elements
of an array, is only as predictable as the data.

The agent samples on a CPU timer, so it can't see mispredictions directly, only where
the time goes. But a misprediction leaves a mark there: while the CPU refills its
pipeline from the right path, the samples land on that path's first instructions.

### A switch over random data

Each element is hashed, with the hash carried from one element to the next, and then a
`switch` picks one of four operations:

```java
static long dispatch(int[] data) {
    long a = 0, b = 1, c = 2, d = 3, h = 0;
    for (int i = 0; i < data.length; i++) {
        int x = data[i];
        h = (h ^ x) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        switch (x & 3) {
            case 0 -> a += x * 3L;
            case 1 -> b ^= (long) x << 7;
            case 2 -> c -= x;
            default -> d = Long.rotateLeft(d, x);
        }
    }
    return a + b + c + d + h;
}
```

On 65,536 random values, and on the same values sorted by `x & 3` so the switch goes
the same way for long runs (JDK 25 on an Apple M2):

| | ns/element | samples on the first 2 instructions of each case |
|---|---|---|
| random | 4.72 | 51% |
| sorted | 1.76 | 6% |

With sorted values the hash dominates the loop; with random ones the mispredictions do.
C2 compiles the switch as a decision tree of compares and branches. Here is the loop
with random values: the samples are on the branches and the first instruction of each
case, `lsl x17, x17, #7` (case 1), `neg w8, w3` (the rotate) and `sub x13, x13, x17`
(case 2), not on the hash's `mul`:

```
Work.dispatch([I)J
  tier 4 (C2), compile id 370, 416 bytes at 0x0000000116e6dfc0, 93 samples

  pc                  bytes        instruction                      count        %
  ...
  0x0000000116e6e030  ad 01 11 cb  sub x13, x13, x17                    9    9.68%
  0x0000000116e6e034  42 04 00 11  add w2, w2, #0x1                     7    7.53%
  0x0000000116e6e038  d0 00 10 ca  eor x16, x6, x16
  0x0000000116e6e03c  5f 00 00 6b  cmp w2, w0
  0x0000000116e6e040  0a 03 00 54  b.ge 0x116e6e0a0 <+0xe0>
  0x0000000116e6e044  31 c8 22 8b  add x17, x1, w2, sxtw #2
  0x0000000116e6e048  23 12 40 b9  ldr w3, [x17, #0x10]
  0x0000000116e6e04c  71 7c 40 93  sxtw x17, w3
  0x0000000116e6e050  10 02 11 ca  eor x16, x16, x17
  0x0000000116e6e054  10 7e 0c 9b  mul x16, x16, x12
  0x0000000116e6e058  65 04 00 12  and w5, w3, #0x3
  0x0000000116e6e05c  06 fe 5d d3  lsr x6, x16, #29
  0x0000000116e6e060  bf 04 00 71  cmp w5, #0x1                        11   11.83%
  0x0000000116e6e064  81 00 00 54  b.ne 0x116e6e074 <+0xb4>
  0x0000000116e6e068  31 e2 79 d3  lsl x17, x17, #7                    22   23.66%
  0x0000000116e6e06c  2e 02 0e ca  eor x14, x17, x14
  0x0000000116e6e070  f1 ff ff 17  b 0x116e6e034 <+0x74>
  0x0000000116e6e074  bf 04 00 71  cmp w5, #0x1                         2    2.15%
  0x0000000116e6e078  aa 00 00 54  b.ge 0x116e6e08c <+0xcc>
  0x0000000116e6e07c  c5 00 00 35  cbnz w5, 0x116e6e094 <+0xd4>        23   24.73%
  0x0000000116e6e080  31 06 11 8b  add x17, x17, x17, lsl #1
  0x0000000116e6e084  2a 02 0a 8b  add x10, x17, x10
  0x0000000116e6e088  eb ff ff 17  b 0x116e6e034 <+0x74>
  0x0000000116e6e08c  bf 08 00 71  cmp w5, #0x2                         3    3.23%
  0x0000000116e6e090  00 fd ff 54  b.eq 0x116e6e030 <+0x70>
  0x0000000116e6e094  e8 03 03 4b  neg w8, w3                          16   17.20%
  0x0000000116e6e098  ef 2d c8 9a  ror x15, x15, x8
  0x0000000116e6e09c  e6 ff ff 17  b 0x116e6e034 <+0x74>
  ...
```

With sorted values, 46% of the samples are on the `and w13, w14, #0x3` straight after
the hash, and few on the cases.

### The rule

`UnpredictableBranchRule` looks, in each hot innermost loop, for conditional branches
whose condition depends on a value loaded in the same iteration: for a `b.cond`, the
flag-setting instruction before it (here `cmp w5, #0x1`), for a `cbz` or `tbnz` the
register it tests, traced back through the dependency graph to a load (here
`ldr w3, [x17, #0x10]`, `data[i]`). Only branches choosing between paths inside the
loop count: a range check branching to a trap, or a `break` out of a search, goes one
way until the last time. It then adds up the samples on the first two instructions of
each path those branches lead to, leaving out paths that are just the next compare and
branch of the same decision tree or chain, since those cost the same whether the
branches mispredict or not. It reports the loop if that's at least 25% of its samples:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<UnpredictableBranches> loops = new UnpredictableBranchRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
UnpredictableBranches loop = loops.get(0);
loop.branches();       // b.ne, b.ge, cbnz, b.eq: each with its condition (cmp w5, #0x1...) and load
loop.samples();        // 93
loop.landingSamples(); // 47, on the cases' first instructions
loop.landingShare();   // 0.51
```

The sorted loop has the same branches, but only 6% of its samples land on the cases, so
it isn't reported.

### If/else chains

The same rule finds an `if`/`else if`/`else` chain over the data, which C2 compiles to
the same kind of code: a compare and a conditional branch per condition.

```java
if (x < 1 << 18) {
    a += x * 3L;
} else if (x < 2 << 18) {
    b ^= (long) x << 7;
} else if (x < 3 << 18) {
    c -= x;
} else {
    d = Long.rotateLeft(d, x);
}
```

| after the same hash | ns/element | samples landing after the branches | reported |
|---|---|---|---|
| four-way chain, random values | 3.94 | 51% | yes: 6 branches (3 conditions, unrolled twice), all on `data[i]` or `data[i + 1]` |
| four-way chain, sorted values | 1.77 | 3% | no |
| `if (x < 1 << 19) a += x; else a -= x;`, random values | 2.55 | 55% | yes |
| `a += x < 1 << 19 ? x : -x;`, random values | 1.79 | | no: on JDK 25 C2 compiles it to `csel`, so there's no branch |

The ternary shows what a conditional select is worth when the data is unpredictable: it
runs as fast as the sorted chain. C2 keeps the branch for the equivalent `if`/`else`,
which costs it 0.76 ns per element here. On JDK 17 and 21 C2 keeps the ternary's branch
too (2.6 ns per element), and the rule reports it; `Math.min` is a `csel` on all three.

### Where the threshold comes from

Across the switch and the four-way chain on JDK 17, 21 and 25 (Linux, in Docker, and
macOS, all on M2 cores), the loops over random data land 37% to 76% of their samples on
the paths their branches choose between, and the same loops over sorted data 3% to 14%,
so the rule reports a loop at 25% or more (`DEFAULT_MINIMUM_LANDING_SHARE`). Counting
the decision tree's own compares as landing, as a first version did, put a sorted chain
on JDK 17 at 40%: the hash's samples skid onto the chain's first compare.

That threshold is calibrated on M2 cores, and it doesn't carry over to every CPU. On a
GitHub Actions arm64 runner, the same switch over random data puts 42% of its samples on
the hash's first `eor` and only 19% on the cases, so the rule doesn't report it. Which
instruction a timer sample blames for a pipeline refill depends on the core, so on other
CPUs treat the structure the rule finds (the branches on loaded data, which is the same
everywhere) as the finding, and the share as a hint. Sampling the CPU's branch-miss
events would remove the guesswork, where the hardware and the OS allow it.

The share shows that branches mispredict, not that it costs much. With three rounds of
hashing instead of one, the hash's latency hides the mispredictions, so random and
sorted values run equally fast, yet 40% of the random loop's samples still land on the
cases (6% sorted). How far samples skid past a stall also depends on the CPU, and these
measurements are all from an Apple M2, so the threshold may need tuning on other
machines.

## Finding megamorphic calls

A virtual or interface call is cheap while its call site sees one or two receiver types:
C2 inlines the targets behind a check of the receiver's class. With more, C2 calls through
an *inline cache* that ends up at a vtable or itable stub, which looks the target up in
the receiver's class on every call and jumps to it indirectly. That jump goes somewhere
different each time, so it is often mispredicted.

A sampling profiler sees the stub, and a stub doesn't say which call reached it. The agent
does (see [How it works](#how-it-works)): it learns the stubs' addresses from JVMTI, and a
sample taken in one is attributed to the call instruction in the method that called it, so
the cost of the dispatch shows up at the call site in your code.

### An interface call over four types

```java
interface Shape { long area(long x); }
// four final implementations: x * 3, x ^ 0x5555, x + 17, Long.rotateLeft(x, 5)

static long sum(Shape[] shapes) {
    long sum = 0;
    for (int i = 0; i < shapes.length; i++) {
        sum += shapes[i].area(i);
    }
    return sum;
}
```

Over 16,384 shapes drawn at random from one, two or all four of the types, with `sum` kept
out of line:

| receiver types | JDK 17 (Linux) | JDK 21 (Linux) | JDK 25 (Linux) | JDK 25 (macOS) |
|---|---|---|---|---|
| 1 | 0.41 ns/call | 0.41 | 0.41 | 0.40 |
| 2 | 1.01 ns/call | 0.60 | 1.59 | 2.19 |
| 4 | 12.06 ns/call | 12.09 | 11.45 | 11.87 |

With one or two types the call is inlined, and `sum` has all the samples. With four, the
time goes to the dispatch and the callees. Without the attribution, a second of samples
on JDK 25 (Linux) put 3 in `sum`, 7 in stubs and 89 in the four `area` methods: nothing
says which loop the stubs' 7 belong to. With it, there are none left in stubs, and `sum`
has 12:

```
Virtual.sum([LVirtual$Shape;)J
  tier 4 (C2), compile id 331, 344 bytes at 0x0000ffff93f64540, 12 samples

  pc                  bytes        instruction                        count        %
  ...
  0x0000ffff93f645a0  ea 0b 00 f9  str x10, [sp, #0x10]
  0x0000ffff93f645a4  a2 7f 40 93  sxtw x2, w29
  0x0000ffff93f645a8  ea 03 40 f9  ldr x10, [sp]
  0x0000ffff93f645ac  4a 69 22 8b  add x10, x10, x2, uxtx #2
  0x0000ffff93f645b0  4b 11 40 b9  ldr w11, [x10, #0x10]
  0x0000ffff93f645b4  e1 03 0b aa  mov x1, x11
  0x0000ffff93f645b8  09 f4 89 d2  mov x9, #0x4fa0
  0x0000ffff93f645bc  09 80 a4 f2  movk x9, #0x2400, lsl #16
  0x0000ffff93f645c0  e9 ff df f2  movk x9, #0xffff, lsl #32
  0x0000ffff93f645c4  65 d5 ed 97  bl 0xffff93ad9b58                      9   75.00%
  0x0000ffff93f645c8  1f 20 03 d5  nop
  0x0000ffff93f645cc  1f 30 80 f2  movk xzr, #0x180
  0x0000ffff93f645d0  1f 00 80 f2  movk xzr, #0x0
  0x0000ffff93f645d4  ea 0b 40 f9  ldr x10, [sp, #0x10]
  0x0000ffff93f645d8  bd 07 00 11  add w29, w29, #0x1
  0x0000ffff93f645dc  4a 01 00 8b  add x10, x10, x0
  0x0000ffff93f645e0  eb 0b 40 b9  ldr w11, [sp, #0x8]                    3   25.00%
  0x0000ffff93f645e4  bf 03 0b 6b  cmp w29, w11
  0x0000ffff93f645e8  cb fd ff 54  b.lt 0xffff93f645a0 <+0x60>
  ...
```

Each iteration loads `shapes[i]` (`+0x70`), loads the inline cache's data into `x9`
(`mov` and two `movk`s), and calls the stub with the `bl` at `+0x84`, which has 9 of the
12 samples. The `nop` and `movk xzr` after it aren't executed; they record the call
site's metadata. The sum and the length live on the stack across the call
(`str x10, [sp, #0x10]` before, the `ldr`s after), since a call can overwrite every
register that isn't saved.

Not all of the dispatch's cost moves to the call. Each `area` method has most of its
samples on the first instruction the stub jumps to (on macOS, 12 of `A.area`'s 14, with
none on the `add` that does its work): that is where the CPU resumes after the mispredicted jump.

### The rule

`MegamorphicCallRule` finds inline cache calls: a call straight after moves of immediates
into `x9`, the register HotSpot passes the cache's data in on AArch64. With one receiver
type (and the callee kept out of line) C2 checks the class and calls the method directly,
with no `x9` setup, and a static call has none either. An inline cache call can still go
straight to one callee, which checks the receiver's class at its entry; then the call
instruction hardly ever has samples of its own, since the callee's time is the callee's.
So a call is reported when it has at least 10% of the method's samples, which only the
stub's attributed samples give it (without samples, every inline cache call is
reported). For the profile above:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<MegamorphicCall> calls = new MegamorphicCallRule().apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
MegamorphicCall call = calls.get(0);
call.call();         // bl at +0x84
call.setup();        // mov x9, #0x4fa0; movk x9, #0x2400, lsl #16; movk x9, #0xffff, lsl #32
call.loopHeader();   // +0x64, where C2 enters its rotated loop
call.samples();      // 9, the dispatch's
call.loopSamples();  // 12
```

On JDK 17, 21 and 25 the call had 70-85% of its loop's samples (11 of 13, 7 of 10, 15 of
18 in the recorded listings). The loops over one and two types have no finding. The rule
reports calls outside loops too, with `loopHeader` -1: a hot megamorphic call matters
wherever the loop driving it is.

## Finding missed vectorisation

C2 vectorises a loop in its superword pass, which works on the unrolled loop: it looks
for copies of the same operations on adjacent elements and packs them into vector
instructions. It does that only if it can prove the copies independent: a store in one
copy mustn't change what a later copy loads. Two arrays of the same type might be the
same array, so when they're indexed at offsets C2 can't compare, it has to assume a
store to one may overlap a load from the other, and the copies stay scalar. The loop is
unrolled, so [Finding loops that aren't unrolled](#finding-loops-that-arent-unrolled)
has nothing to say about it, but each iteration does one element's work per copy where
a vector instruction would do two or more.

### DAXPY, with and without offsets

```java
static void daxpy(double a, double[] x, double[] y) {
    for (int i = 0; i < y.length; i++) {
        y[i] += a * x[i];
    }
}

static void daxpyWithOffsets(double a, double[] x, int xOffset, double[] y, int yOffset, int n) {
    for (int i = 0; i < n; i++) {
        y[yOffset + i] += a * x[xOffset + i];
    }
}
```

On 4,096-element arrays, with `daxpyWithOffsets` called with offsets 1 and 0, on an
Apple M2 (best of 20 rounds):

| | JDK 17 (Linux) | JDK 21 (Linux) | JDK 25 (Linux) | JDK 25 (macOS) |
|---|---|---|---|---|
| `daxpy` | 0.136 ns/element | 0.123 | 0.128 | 0.119 |
| `daxpyWithOffsets(a, x, 1, y, 0, n)` | 0.282 ns/element | 0.301 | 0.314 | 0.297 |
| `daxpyWithOffsets(a, x, 0, y, 0, n)` | 0.271 ns/element | 0.300 | 0.312 | 0.296 |
| `y[i] += a * x[i + 1]` | 0.249 ns/element | 0.242 | 0.132 | 0.123 |

Offsets of 0 don't help: C2 compiles the method once for any offsets, so the
code can't depend on their values. A constant offset is different: C2 can compare
`x[i + 1]` with `y[i]`, and JDK 25 vectorises that loop. JDK 17 and 21 don't, because
their superword pass also needs the loads aligned with the stores.

`daxpy` loads and stores two doubles at a time (`ldr q`, `str q`). `daxpyWithOffsets` is
unrolled four times, and each copy loads `x` and `y`, multiplies, adds and stores one
double. Its main loop on JDK 25 (Linux):

```
MissedVectorisationTest.daxpyWithOffsets(D[DI[DII)V
  tier 4 (C2), compile id 1689, 544 bytes at 0x0000ffff93fb01c0, 99 samples

  pc                  bytes        instruction                     count        %
  ...
  0x0000ffff93fb02a0  4f 7d 40 93  sxtw x15, w10
  0x0000ffff93fb02a4  30 02 0f 8b  add x16, x17, x15
  0x0000ffff93fb02a8  30 6c 30 8b  add x16, x1, x16, uxtx #3
  0x0000ffff93fb02ac  10 0a 40 fd  ldr d16, [x16, #0x10]
  0x0000ffff93fb02b0  cf 01 0f 8b  add x15, x14, x15
  0x0000ffff93fb02b4  6f 6c 2f 8b  add x15, x3, x15, uxtx #3
  0x0000ffff93fb02b8  f1 09 40 fd  ldr d17, [x15, #0x10]
  0x0000ffff93fb02bc  10 0a 60 1e  fmul d16, d16, d0
  0x0000ffff93fb02c0  10 2a 71 1e  fadd d16, d16, d17
  0x0000ffff93fb02c4  f0 09 00 fd  str d16, [x15, #0x10]
  0x0000ffff93fb02c8  10 0e 40 fd  ldr d16, [x16, #0x18]
  0x0000ffff93fb02cc  f1 0d 40 fd  ldr d17, [x15, #0x18]
  0x0000ffff93fb02d0  10 0a 60 1e  fmul d16, d16, d0
  0x0000ffff93fb02d4  10 2a 71 1e  fadd d16, d16, d17
  0x0000ffff93fb02d8  f0 0d 00 fd  str d16, [x15, #0x18]
  0x0000ffff93fb02dc  10 12 40 fd  ldr d16, [x16, #0x20]              20   20.20%
  0x0000ffff93fb02e0  f1 11 40 fd  ldr d17, [x15, #0x20]               8    8.08%
  0x0000ffff93fb02e4  10 0a 60 1e  fmul d16, d16, d0
  0x0000ffff93fb02e8  10 2a 71 1e  fadd d16, d16, d17
  0x0000ffff93fb02ec  f0 11 00 fd  str d16, [x15, #0x20]               1    1.01%
  0x0000ffff93fb02f0  50 00 0a 0b  add w16, w2, w10
  0x0000ffff93fb02f4  2f cc 30 8b  add x15, x1, w16, sxtw #3
  0x0000ffff93fb02f8  f0 15 40 fd  ldr d16, [x15, #0x28]              14   14.14%
  0x0000ffff93fb02fc  8f 00 0a 0b  add w15, w4, w10
  0x0000ffff93fb0300  6f cc 2f 8b  add x15, x3, w15, sxtw #3           2    2.02%
  0x0000ffff93fb0304  f1 15 40 fd  ldr d17, [x15, #0x28]               1    1.01%
  0x0000ffff93fb0308  10 0a 60 1e  fmul d16, d16, d0
  0x0000ffff93fb030c  4a 11 00 11  add w10, w10, #0x4
  0x0000ffff93fb0310  10 2a 71 1e  fadd d16, d16, d17
  0x0000ffff93fb0314  f0 15 00 fd  str d16, [x15, #0x28]              53   53.54%
  0x0000ffff93fb0318  5f 01 0c 6b  cmp w10, w12
  0x0000ffff93fb031c  2b fc ff 54  b.lt 0xffff93fb02a0 <+0xe0>
  ...
```

Four copies of `ldr d`, `ldr d`, `fmul`, `fadd`, `str d`, each 8 bytes further on
(`#0x10`, `#0x18`, `#0x20`, `#0x28`), with `a` in `d0` shared by all of them. The last
copy computes its addresses again from the offsets (`w2`, `w4`), but still at the next
element. Each copy loads after the copy before it stored. If `x` and `y` were one array
with `xOffset = yOffset - 1`, copy 2's load of `x` would read the element copy 1 had just
stored, making the loop `y[j] += a * y[j - 1]`, a recurrence. That is the dependence C2
can't rule out.

### On x86

The same method on x86-64, JDK 25 (Linux, SSE4.2: recorded under Rosetta, which has no
AVX), is also unrolled four times and kept scalar. x86 folds each load into the
arithmetic, and SSE's two-operand forms overwrite their destination, so every copy first
copies `a` from `xmm0`:

```
  bytes                     count  offset  instruction
  66 0f 28 d8                   0  +0x104  movapd xmm3, xmm0
  66 49 0f 7e d3                0  +0x108  movq r11, xmm2
  f2 43 0f 59 5c f3 10          5  +0x10d  mulsd xmm3, qword ptr [r11 + 8*r14 + 0x10]
  f2 0f 58 5c f1 10             5  +0x114  addsd xmm3, qword ptr [rcx + 8*rsi + 0x10]
  f2 0f 11 5c f1 10             1  +0x11a  movsd qword ptr [rcx + 8*rsi + 0x10], xmm3
  66 0f 28 d8                   0  +0x120  movapd xmm3, xmm0
  f2 43 0f 59 5c f3 18          3  +0x124  mulsd xmm3, qword ptr [r11 + 8*r14 + 0x18]
  f2 0f 58 5c f1 18             8  +0x12b  addsd xmm3, qword ptr [rcx + 8*rsi + 0x18]
  f2 0f 11 5c f1 18             9  +0x131  movsd qword ptr [rcx + 8*rsi + 0x18], xmm3
  66 0f 28 d8                   0  +0x137  movapd xmm3, xmm0
  f2 43 0f 59 5c f3 20          6  +0x13b  mulsd xmm3, qword ptr [r11 + 8*r14 + 0x20]
  f2 0f 58 5c f1 20             9  +0x142  addsd xmm3, qword ptr [rcx + 8*rsi + 0x20]
  f2 0f 11 5c f1 20             4  +0x148  movsd qword ptr [rcx + 8*rsi + 0x20], xmm3
  66 0f 28 d8                   0  +0x14e  movapd xmm3, xmm0
  f2 43 0f 59 5c eb 28          9  +0x152  mulsd xmm3, qword ptr [r11 + 8*r13 + 0x28]
  f2 42 0f 58 5c c9 28          6  +0x159  addsd xmm3, qword ptr [rcx + 8*r9 + 0x28]
  f2 42 0f 11 5c c9 28         27  +0x160  movsd qword ptr [rcx + 8*r9 + 0x28], xmm3
  41 83 c2 04                   2  +0x167  add r10d, 0x4
  44 3b d0                      1  +0x16b  cmp r10d, eax
  0f 8c 6f ff ff ff             0  +0x16e  jl <+0xe3>
```

`movq r11, xmm2` reloads `x`'s address, which C2 has spilled to a vector register. Like
`movapd xmm3, xmm0`, it moves a scalar, so neither makes the loop count as vectorised:
on x86 that takes a `ymm` or `zmm` register, a whole vector in memory (`xmmword ptr`...)
or a packed operation. With AVX, the copies use three-operand forms instead
(`vmulsd xmm3, xmm0, qword ptr [...]`), with no copy of `a`. An `int` kernel is compiled
to read-modify-writes of memory, `add dword ptr [rcx + 4*rdi + 0x10], edx`, each a load,
an add and a store in one instruction.

### The rule

`MissedVectorisationRule` looks for the copies a superword pass would pack, starting from
the stores, as superword does. In each innermost loop with at least 10% of the method's
samples and no vector instructions, it follows each plain store's data register back
through the iteration to the expression computing it, made of loads (on x86, also the
memory operands folded into arithmetic), values from outside the loop (`d0`, `xmm0`), and
operations with vector equivalents: floating-point arithmetic, fused multiply-adds and
conversions, integer add, subtract, logic, shifts and extensions, and 32-bit multiplies
(Advanced SIMD has no 64-bit multiply, and x86 only has one with AVX-512). An x86
read-modify-write of memory is a store of its result. Stores whose expressions match,
operation by operation and immediate by immediate, are copies if:

- they store adjacent elements, and the loads in each position of their expressions load
  adjacent elements: their immediate offsets step by the element's size;
- they share no instructions, and none uses a value another computed, in the same
  iteration or the one before;
- each loads something.

So it doesn't report a recurrence such as `y[i] = a * y[i - 1] + x[i]`, which C2 also
unrolls (eight times on JDK 25) but whose copies each take the previous copy's result in a
register; a floating-point sum, which stores nothing and which C2 can't vectorise without
changing its rounding; or a `long` multiply.

What is an operation, a load, a store or a vector instruction differs by architecture, so
the rule takes a `VectorisationModel`: `Aarch64VectorisationModel` or
`X86VectorisationModel`, or `VectorisationModel.forArchitecture(System.getProperty("os.arch"))`.
For the AArch64 profile above:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<MissedVectorisation> findings = new MissedVectorisationRule(new Aarch64VectorisationModel()).apply(
        code, new Aarch64RegisterModel(), i -> profile.count((int) (i.address() - profile.address())));
MissedVectorisation found = findings.get(0);
found.header();       // +0xe0
found.lanes();        // 4: the copies
found.elementSize();  // 8
found.copies();       // each copy's ldr, ldr, fmul, fadd, str, the stores at +0x104, +0x118, +0x12c, +0x154
found.operations();   // fmul d16, d16, d0; fadd d16, d16, d17
found.samples();      // 99, all of the method's
```

On x86 (`new MissedVectorisationRule(new X86VectorisationModel())` with an
`X86Disassembler` and `X86RegisterModel`), the listing above gives the loop at `+0xe3`,
4 lanes of 8 bytes, each copy `movapd`, `mulsd`, `addsd`, `movsd`.

The fix is usually to give C2 code it can compare: index both arrays from zero (copy a
slice, or pass the arrays without offsets where the offsets are always zero), or write
the kernel with the Vector API.

## Finding atomics in hot loops

`AtomicLong`, `AtomicInteger`, `AtomicBoolean`, `LongAdder` and volatile fields compile
to atomic read-modify-writes, fences, and acquire loads and release stores. Each atomic
read-modify-write has to own its cache line and, in the orderings Java gives it, keeps
later accesses from starting until it completes, so the CPU can't overlap one iteration's
update with the next. On one thread, with no contention, the measurements below put that
at several times an ordinary add; across threads, each update also moves the cache line
between cores. Volatile reads and writes (`get()`, `set()`) cost less, but still constrain
reordering on AArch64.

### Updating an AtomicLong per element

```java
static long addAndGet(AtomicLong counter, int[] data) {
    long sum = 0;
    for (int i = 0; i < data.length; i++) {
        sum += counter.addAndGet(data[i]);
    }
    return sum;
}
```

On 4,096-element arrays, uncontended, one thread, JDK 25 on an Apple M2 (best of 10 rounds):

| loop body | macOS | Linux (Docker) | what C2 emits per element (AArch64; x86) |
|---|---|---|---|
| `box[0] += data[i]` | 0.58 ns/element | 0.85 | loads and adds: C2 keeps `box[0]` in a register |
| `sum += data[i]`, then `counter.addAndGet(sum)` once | 0.56 | 0.78 | nothing atomic in the loop |
| `counter.addAndGet(data[i])` | 3.75 | 3.78 | `ldaddal`; `lock xadd` |
| `counter.incrementAndGet()`, result unused | 1.87 | 1.97 | `ldaddal` into `xzr`; `lock add` |
| `AtomicInteger.getAndAdd(data[i])` | 3.76 | 4.38 | `ldaddal`; `lock xadd` |
| `AtomicBoolean.compareAndSet(...)` | 3.72 | 7.73 | `casal`; `lock cmpxchg` |
| `LongAdder.add(data[i])` | 4.87 | 8.75 | `ldar`, `ldar`, `dmb ish`, `casl`; `lock cmpxchg` |
| `AtomicInteger.get() ^ data[i]` | 0.31 | 0.32 | `ldar`; a plain `mov` |
| `AtomicBoolean.set(...)` | 0.29 | 0.60 | `stlr`; a plain `mov`, and a fence per unrolled iteration |

An uncontended `LongAdder` is no cheaper than an `AtomicLong`: until it sees contention it
CASes its base, after reading its `cells` and `base` fields. Its cells only help when
threads collide.

The loop on JDK 25 (Linux, AArch64) is unrolled four times, with an `ldaddal` (an atomic
add, with acquire and release semantics) per element. The samples land mostly on the
instruction after each `ldaddal`, where the CPU waits for it:

```
  bytes                     count  offset  instruction
  50 c8 2d 8b                   1  +0x090  add x16, x2, w13, sxtw #2
  11 12 80 b9                   1  +0x094  ldrsw x17, [x16, #0x10]
  72 02 f1 f8                   6  +0x098  ldaddal x17, x18, [x19]
  01 16 80 b9                  14  +0x09c  ldrsw x1, [x16, #0x14]
  83 02 e1 f8                   5  +0x0a0  ldaddal x1, x3, [x20]
  04 1a 80 b9                  11  +0x0a4  ldrsw x4, [x16, #0x18]
  a5 02 e4 f8                   6  +0x0a8  ldaddal x4, x5, [x21]
  10 1e 80 b9                  12  +0x0ac  ldrsw x16, [x16, #0x1c]
  e6 00 f0 f8                   0  +0x0b0  ldaddal x16, x6, [x7]
  51 02 11 8b                  18  +0x0b4  add x17, x18, x17
  ...
  ad 11 00 11                   2  +0x0d0  add w13, w13, #0x4
  00 02 11 8b                   1  +0x0d4  add x0, x16, x17
  bf 01 0f 6b                   0  +0x0d8  cmp w13, w15
  ab fd ff 54                   0  +0x0dc  b.lt <+0x90>
```

On x86 it is a `lock xadd` per element, and the samples land the same way:

```
  bytes                     count  offset  instruction
  4a 63 6c 82 10                0  +0x090  movsxd rbp, dword ptr [rdx + 4*r8 + 0x10]
  48 8b dd                      0  +0x095  mov rbx, rbp
  f0 48 0f c1 5e 10             0  +0x098  lock xadd qword ptr [rsi + 0x10], rbx
  4e 63 74 82 14               13  +0x09e  movsxd r14, dword ptr [rdx + 4*r8 + 0x14]
  49 8b fe                      0  +0x0a3  mov rdi, r14
  f0 48 0f c1 7e 10             4  +0x0a6  lock xadd qword ptr [rsi + 0x10], rdi
  4e 63 6c 82 18               24  +0x0ac  movsxd r13, dword ptr [rdx + 4*r8 + 0x18]
  ...
```

Without LSE (`-XX:-UseLSE`, or a CPU without it), each update is instead a small loop of
its own: `ldaxr`, `add`, `stlxr`, and `cbnz` back to the `ldaxr` if another core got in
between.

### The rule

`FetchInLoopRule` takes a `MemoryOrderingModel` (`Aarch64MemoryOrderingModel`,
`X86MemoryOrderingModel`, or `MemoryOrderingModel.forArchitecture(os.arch)`), which
classifies instructions as:

| | AArch64 | x86-64 |
|---|---|---|
| acquire loads | `ldar`, `ldapr` and their forms | none: volatile reads are plain `mov`s |
| release stores | `stlr` and its forms | none: volatile writes are plain `mov`s |
| atomic updates | LSE atomics in every ordering (`ldaddal`, `casal`, `casl`, `swpal`, `ldsetal`...), load/store-exclusives | `lock`-prefixed instructions, `xchg` with memory |
| fences | `dmb` (`ish`, `ishld`, `ishst`) | `mfence`, a locked add or or of 0 to the stack (`lock add dword ptr [rsp - 0x40], 0x0`) |

Each such instruction belongs to the smallest loop containing it, skipping the retry loop
of a load/store-exclusive pair. A loop is reported if it has at least 10% of the method's
samples (or always, without samples). The finding lists the accesses with their kind and
samples, counting the instruction after each one too, since that's where a stalled
access's samples land. For the AArch64 profile above:

```java
List<Instruction> code = new Aarch64Disassembler().decodeAll(profile.machineCode().orElseThrow(), profile.address());
List<FetchInLoop> findings = new FetchInLoopRule(new Aarch64MemoryOrderingModel())
        .apply(code, i -> profile.count((int) (i.address() - profile.address())));
FetchInLoop loop = findings.get(0);
loop.header();                                // +0x90
loop.accesses();                              // the four ldaddal, each ATOMIC_UPDATE
loop.accesses(Ordering.ATOMIC_UPDATE).size(); // 4
loop.samples();                               // 82, all of the method's
loop.accessSamples();                         // 72, on the ldaddal and the instructions after them
```

Findings are ranked by `accessSamples`. Uncontended, acquire loads and release stores
have few of their loop's samples (`AtomicInteger.get()`: 45 of 97; `AtomicBoolean.set`:
21 of 95) and atomic updates most of them (`AtomicLong.incrementAndGet()`: 95 of 99),
so a loop with an atomic update ranks above one that only reads a volatile field. An
atomic updated once after the loop isn't reported.

What the rule reports is where these instructions execute and how many samples they and
the instructions after them have. It can't tell whether the program needs them on every
iteration, and timer samples can't tell an uncontended update from one whose cache line is
moving between cores. A stack profile that resolves inlined frames also shows calls such
as `AtomicLong.addAndGet` as hot; volatile field accesses and barriers the compiler
inserts have no frame of their own, and show up here only.

## Using it

Load the native library, either with the Java agent (`-javaagent:agent.jar`) or by
using `instructive.agent.Sampler` directly, which loads it on first use. Then:

```java
Sampler.start();                       // start CPU sampling
// ... run the workload ...
Sampler.stop();

// The k hottest compiled methods, hottest first.
Sampler.forEachHottestMethod(10, profile -> {
    // `profile` is a flyweight over live native memory, valid only inside this lambda.
    // Keep a snapshot if you need it afterwards.
    InstructionProfile snapshot = profile.snapshot();
    // name(), compLevel(), compileId(), address(), total(), count(offset), counts(),
    // machineCode(), hasMissingCounts(), classSignatureCode(), methodNameCode() ...
});

Sampler.forEachMethod(profile -> { /* every loaded compiled method */ });
Sampler.kindCounts();                  // samples in compiled code, the interpreter, stubs, native code
Sampler.resetCounts();                 // start a new window, e.g. once a minute
```

Per-instruction counts are 16 bits and saturate after about 11 minutes of samples on
a single instruction, so continuous profiling should reset regularly.

## How it works

- **Sampling:** a CPU-time timer delivers `SIGPROF` every 10 ms of CPU time: one POSIX
  timer per thread on Linux, and a process-wide `setitimer` on macOS. The signal
  handler takes the program counter from the signal context. A `SIGPROF` that isn't the
  agent's, such as another profiler's timer signal, is passed on to the handler that was
  registered before it, so the agent can run alongside another profiler. On Linux the
  agent's timers tag their signals, so the two never confuse each other's samples.
- **Tracking compiled code:** JVMTI `CompiledMethodLoad`/`CompiledMethodUnload` events
  keep a concurrent radix tree, keyed by code address, of every loaded compiled
  method. Its leaf records are 64 bytes each: the compile tier and id, a total sample
  count and where the method's per-PC counters are. The handler looks the PC up there,
  without locks or allocation. Freed records are reclaimed safely using epochs.
- **Virtual dispatch at the call site:** a call that has seen too many receiver types
  to inline goes through a vtable or itable stub, and a profiler usually shows the time
  spent there as a stub, with no way to tell which call it belongs to. The agent learns
  the stubs' addresses from JVMTI, and attributes a sample taken in one to the call
  instruction that reached it, in the calling method: the stub builds no frame, so the
  return address is still in the link register (or on top of the stack). The cost of the
  dispatch shows up where it was paid for, at the call in the user's code. A loop calling
  an interface method on four types of receiver: before, 2 of its 92 samples were in the
  loop and 19 in stubs; with the attribution, 21 of its samples are on the `bl`, and none
  are left in stubs.
- **Counters only for sampled methods:** most compiled methods are never sampled, so a
  method's per-PC counters are claimed from a separate region on its first sample, by
  the signal handler, with a compare-and-swap, and all of them are released when
  `resetCounts()` starts a new window. Records and counters together come to about
  5-8% of the code cache's size on AArch64. If the region fills up, a method's total
  still counts, and `hasMissingCounts()` says its per-instruction counts are
  incomplete.
- **Names, once each:** a record doesn't hold its method's name. Each distinct class
  signature, method name and method signature is kept once, in a concurrent dictionary
  that numbers strings in the order it first sees them, and records hold the three
  codes. `name()` looks them up and formats `package.Class.name(signature)` only when
  asked, which is usually just for the hottest methods.
- **Zero-copy reads:** the records live in one native memory region that Java reads
  in place, through the `InstructionProfile` flyweight. Selecting the top k is a
  single pass with a bounded heap.
- **Machine code on demand:** `machineCode()` copies a method's code from the JVM's
  code cache, using layout information from HotSpot's VMStructs. It only does so
  after checking that the nmethod found there has exactly the profile's address, size
  and compile id.
- **Disassembly:** the `parse` module has AArch64 and x86-64 decoders, written in Java,
  whose output matches `llvm-objdump` (Intel syntax on x86-64). The x86-64 one covers
  the general purpose, x87, SSE, AVX and AVX-512 instructions; its vector instruction
  table is generated from LLVM. `InstructionProfileFormatter` uses them to print the
  listings above.

## Building

```
./gradlew build                  # builds everything and runs the tests
scripts/test-linux.sh            # the same on Linux x86-64 and AArch64, in Docker
DISTRO=alpine JDK=17 scripts/test-linux.sh arm64   # musl, JDK 17, AArch64
```

Requires JDK 17 or newer and a C++17 compiler. The native library is packaged into the
agent jar under `META-INF/native-libs/<os>-<arch>/`. CI tests on Ubuntu (glibc) and
Alpine (musl), on JDK 17, 21 and 25, on x86-64 and AArch64. It publishes a jar with the
Linux libraries for all four combinations of libc and architecture. macOS isn't
published: build it locally.

## Status and limitations


- **Platforms:** developed on macOS on Apple Silicon. Linux is tested on x86-64 and
  AArch64, with glibc and musl, in Docker (`scripts/test-linux.sh`), both locally and in
  CI. Locally on an Apple Silicon host the x86-64 runs are emulated; in CI they run on
  x86 hardware.
- **Disassembly** covers AArch64 (scalar; vector SIMD is only classified) and x86-64
  (APX, XOP and 3DNow! aren't decoded).
- **JDK versions:** 17, 21 and 25 are tested. The code cache layout comes from
  VMStructs, whose entries change between releases, and the reader handles the
  differences between these versions. On macOS, JDKs before 25 don't export the
  VMStructs symbols at all.
- **Interpreted code** is counted, but not attributed to methods.
- **Analysis rules** (`LongDependencyChainRule`, `NotUnrolledLoopRule`, `PointerChasingRule`, `FailedCheckRule`, `UnpredictableBranchRule`) work on AArch64
  only so far: they need a register model, and only `Aarch64RegisterModel` exists. They
  track registers, not memory.
