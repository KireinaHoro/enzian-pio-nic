# Static hot RPC instruction count — October 5

For the successful inline `add` path, the pre-PMU deployed AArch64 binaries
execute **1,865 instructions with runtime tracing disabled**, or **3,434 with
buffered latency tracing enabled**, under the steady-state conditions below.
These count selected executed branches and callees, not function sizes or both
sides of a conditional. They are nominal static path counts, not PMU results or
an assertion that every measured request executed exactly this many instructions.

The traced variant corresponds to the buffered latency baseline. The disabled
variant selects the trace-off branches in the **same binary**; it is not a
separately compiled trace-free runtime. Even disabled, trace checks, TLS lookups,
record copies and clearing remain in the code.

| Exclusive attribution | Trace disabled | Trace enabled |
| --- | ---: | ---: |
| Worker loop, timeout check, empty fiber queue and execution hook | 222 | 232 |
| RX transport, excluding timestamp callee | 61 | 81 |
| Runtime dispatch and outcome handling | 313 | 313 |
| Decode, including allocation and XDR | 367 | 367 |
| Handler, including add and correctness check, excluding trace helpers | 56 | 56 |
| Encode, including XDR | 202 | 202 |
| Reply wrapper, excluding separately attributed callees | 94 | 97 |
| TX transport, including 8-byte copy | 95 | 95 |
| Argument cleanup, including XDR_FREE and free | 228 | 228 |
| Trace helper calls, timestamps, CPU lookup and buffer append | 227 | 1,763 |
| **Total per successful loop iteration** | **1,865** | **3,434** |

Each executed instruction appears once. A call instruction belongs to its caller;
its PLT stub and callee are counted separately. Nested callees inherit the
category unless explicitly attributed elsewhere. Thus these rows are not the
same boundaries as the timestamp latency chart. In particular the trace-helper
row is not the complete cost of all tracing-related instructions: inlined
record copying and call-site setup remain in their callers' rows.

## Latency-relevant boundaries

| Inclusive boundary, unless stated otherwise | Trace disabled | Trace enabled |
| --- | ---: | ---: |
| Before RX function entry | 212 | 407 |
| RX function entry through TX function return | **1,300** | **2,498** |
| After blocking RX load through TX doorbell load | **1,275** | **2,473** |
| After TX function return through loop-back | 353 | 529 |

The middle doorbell boundary excludes the RX load itself, includes the TX load,
and excludes TX's return sequence. The blocking RX instruction is
`core_eci_rx_traced+0x28` (`0x8120`); the TX doorbell is
`core_eci_tx+0xd4` (`0x5b44`). A blocked load counts as one instruction however
long it waits. This is not a cycle or cache-miss estimate.

The handler's 56 instructions comprise 37 in `bench_handler`, 5 in
`compute_add` (one actual `add`, frame setup/teardown and return), and 14 in
`add_check`. Trace-ID/result helpers are separate. The application does not just
perform the addition: the runtime allocates the decoded 12-byte object,
decodes three XDR scalars, constructs a managed reply, encodes two scalars,
and later calls XDR_FREE on the three arguments before freeing the object.
The decode allocation itself is 70 instructions (allocation wrapper, PLT,
and 59-instruction warmed `calloc` path).

Buffered tracing adds 1,569 instructions per iteration in this binary. It makes
11 RAW timestamp reads: RX entry, RX descriptor arrival, RX exit, and before/
after decode, handler, encode and TX. The worker loop also makes one MONOTONIC
read for its timeout check, in both variants. Timestamp calls enter the vDSO,
not the kernel, on the selected fast path. Return-register clearing, stack
canary checks, frame operations, barriers, PLT stubs and TLS descriptor callbacks
are all counted. No claim of instruction/cycle equivalence follows.

## Branch selection and limits

The workload determines the application/runtime branches:

- RX valid, type ONCRPC call (3), no overflow beyond the 48-byte inline area;
  packet dispatch takes the call arm, not PREEMPT or response handling.
- Allocation succeeds; three scalar decodes succeed. Current fiber equals the
  main fiber, so tracing uses the inline request record.
- `flush_spin_us == 0`; `compute_add` and the successful `add_check` are selected.
  Outcome is synchronous REPLY, with no destructor or deferred continuation.
- Two response scalars encode successfully; length is 8. TX takes the reply
  arm (4), copies 8 bytes, has no remaining payload and returns ACCEPTED.
- Cleanup uses XDR_FREE (scalar no-op success branches) and frees the allocation.
- Watchdog disabled; empty ready queue; its installed execution-flush hook
  returns without sending anything. Ordinary iteration does not sweep timeouts.

Additional steady-state library conditions are explicit assumptions: resolved
PLT/static TLS, warmed allocator tcache, valid stack canaries, available trace
buffer, registered rseq CPU lookup, and a vDSO read with no seqlock retry or
nanosecond normalization carry. Warmups and synchronous allocate/free support
the tcache selection, but the latency trace is not an instruction branch trace.
The rare vDSO normalization arm adds 8 instructions for a single carry; seqlock
retries, the once-per-second timeout sweep, NACK-only iterations, interrupts,
faults and allocator initialization are outside this nominal successful RPC.
They must not be assigned these counts without selecting their own paths.

A cross-compiled read-only probe run through the board harness verified the
**same Nix glibc** selects `memcpy` at ELF offset `0xb1800`, `memset` at
`0xb2240`, DCZID=5, and has registered rseq. The generic 288-byte zeroing path
runs four 64-byte loop iterations for its stack alignment. An initial Python
probe used Ubuntu libc and was explicitly discarded for libc selection; only
its public vDSO ELF was retained. No compiler was deployed to the board, no
traffic was run and hardware state was not reset.

## Reproduction and validation

The inputs are the exact baseline app (SHA256 `9a74fff6…`), runtime from
`7bc9302b` (`d4cd4ed5…`, before PMU instrumentation), mapped libtirpc and Nix
glibc, matching loader, and public vDSO from the DUT kernel. Full hashes and
paths are pinned in the manifests:

- [Traced path](../../data/eci/board-evidence/hot-static-516232.json)
- [Trace-disabled path](../../data/eci/board-evidence/hot-static-516232-untraced.json)
- [Counts and resolver validation](../../data/eci/board-evidence/hot-static-516232-summary.json)

The exact app was retrieved from
`enzian-gateway:/scratch/pengxu/buffered-latency-516232/microbenchmarks`;
the similarly named older deployment app has a different hash and was not used.
Local artifacts are in `out/static-hot-count/`; public vDSO and probe output
are also retained at `/scratch/pengxu/static-hot-count/` on the shared board
scratch volume. Do not substitute the current PMU runtime for these binaries.

```sh
python3 tools/enzian/static_path_count.py \
  data/eci/board-evidence/hot-static-516232.json \
  --objdump aarch64-unknown-linux-gnu-objdump \
  --output out/static-hot-count/count.json
python3 -m unittest discover -s tools/enzian -p test_static_path_count.py
```

Repeat with the `-untraced.json` manifest for the disabled variant. The tool
checks input hashes, validates every selected control-flow edge, requires every
executed call to be resolved, and emits an ordered instruction ledger plus
branch decisions. Both complete manifests passed this audit; four focused
counter tests passed. The selected-path audit cannot prove its predicates from
machine state; those are justified above rather than silently inferred by the
tool. This completes the static-count substitute for the unreliable
[PMU retired-instruction measurement](pmu-profiling.md#instruction-counter-blocker).
