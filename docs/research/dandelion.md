# Dandelion student port

See the [registered endpoint and internal-loopback contract](../software/nested-rpc-endpoints.md) for the B2/B6 batch.

## Active integration — October 8

The maintainer has resumed Dandelion after closing the dedicated multi-worker
campaign. HOST_WAKE remains on hold; additional four-worker coverage belongs to
application testing as concrete cases arise. Earlier B6 reservation notes are
superseded by this direction, not by a claim of completed application acceptance.

The project now lives at [the transferred Dandelion repository](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/dandelion).
The integration based on `69c3088` now lives directly in that repository,
including cooperative worker-pool registration and its root `package.nix`.
The platform consumes the pinned source recipe without a downstream patch stack. This is an
existing adapter, not a new import of the student's old C runtime. First run the
fixture reference calculator, cross-build the app and packaged server tests,
and execute the bounded codec tests. Then use matching runtime/module/FPGA
artifacts for atomic matmul/matmac, DAG, sharding and concurrent nested cases.
Check actual isolated CPU placement and request progress, retaining traces and
correctness results; only then collect application performance distributions.
Build success and the old single-function smoke do not establish composition
support on the current address/PREEMPT implementation.

The later sections distinguish the historical student/mock design from the
managed-outcome adapter. Runtime recovery limitations remain those of the
[current programming contract](../software/programming-model.md), not blanket
assumptions copied from the thesis. See the [build/run recipe](../../sw/apps/dandelion/README.md).

Surveyed 2026-09-08. External sibling workspace: `../sven-dandelion/` from the platform root; it is not vendored here. Report: **Sven Glinz, _Porting Dandelion to the Lauberhorn smart NIC_**, bachelor thesis, February–August 2026, supervised by Pengcheng Xu and Timothy Roscoe. Inspected commits: report `ec49e8a`, platform `9e25181`, Dandelion `69c3088`. The report identifies upstream Dandelion `98c4968` (2026-03-09) as its port base; these are distinct revision identities.

**Hardware update, 2026-09-10:** the external `69c3088` application builds for
AArch64 against this branch's real imported runtime. A peer-driven 1×1 matmul
smoke test passed on Enzian, including HTTP registration and an FPGA UDP RPC
reply. This is separate from the thesis's mock evaluation; compositions and
performance remain unvalidated. See [recorded hardware results](../development/hardware-test-results.md).
The inspected port initializes process engines with logical worker indices
0–3, which `mmu_worker` treats as Linux CPU IDs, whereas the NIC RPC workers
use isolated CPUs 44–47 in this setup. Reconcile that mapping before claiming
the intended isolated-core execution policy or measuring performance.

**Integration update, 2026-09-14:** the main flake now pins `69c3088` and applies
tracked patches from `sw/apps/dandelion/`. The shared endpoint is initialized
before workers, with stable C/Rust storage and the local NIC address. Process
execution samples the actual RPC worker CPU; engine indices remain logical
slots. C/Rust layout assertions and application/test-harness builds pass.
The fixture reference calculator independently checks the DAG, sharding and
nested expected answers. The packaged nested test includes concurrent distinct
inputs. These tests have **not** been executed against this batch on Enzian;
composition support and actual isolated placement remain unvalidated. See the
[build/run recipe](../../sw/apps/dandelion/README.md).

## Direct repository integration — October 8

The active evaluation target is a correlated nested-composition waterfall;
see the [roadmap goal](roadmap.md#active-goal-dandelion-nested-composition-waterfall--october-8).
Use Sven's actual evaluation workloads after the atomic/composition correctness
gates: 2×2 atomic matmul, depth 2 (three independent matrix squares feeding a
matmac), and depth 4 (four sequential matrix squares). The report uses 100 warmup
and 1,000 measured iterations. In the packaged latency source the depth-2 graph
is named `graph`, and the depth-4 chain is named `graph_2`; do not infer graph
depth from those names. The existing integration-test DAG is a different fixture.
Show measured parallel overlap rather than assuming it from DAG independence.

The first October 8 launch on the existing DUT session stopped before any RPC:
terminating the old benchmark domains asserted the ownership reset-required
gate, and `lauberhorn_init` returned `ENOSPC`. `allocate_domain_code` explicitly
rejects that state. This is the documented process-lifetime recovery limitation,
not an atomic-function or loopback result. The prior full frozen buffer and the
410,681,920-byte newly appended startup window were preserved; the latter's
SHA-256 is `b40614c0e3588a09d340fff0a3c35fe14446458775e5cb35024867ba8af96138`.
Continue with Dandelion as the first application after a matched cold boot.
Evidence and the test manifest are under `out/dandelion-resume/`; the cold-boot
trial has not yet supplied an acceptance result at this checkpoint.

Dandelion `ccf1a36072b9933d0dceb10b90b9ac69917d3d62` incorporates the five former
platform patches and the installed-runtime `package.nix` directly. The platform
now calls the pinned input's recipe and supplies `lauberhornRuntime`, `crane` and
the target package set. No Dandelion patch files or package recipe remain here.
The historical student replay helper resolves its old adapter from an archived
platform revision only; it is not used by the maintained application build.

The direct-source AArch64 application and packaged server-test harness built
against saved pipeline 516232 generated headers. The bounded codec capacity test
passed under target emulation (one test); the independent DAG/sharding/concurrent
fixture reference calculation passed. The filtered source contained 167 files,
11,170,406 bytes, and no `.git`, `target`, `out` or `result` trees. Build logs and
exact outputs are retained in `out/dandelion-resume/`. No application composition
was run on the board in this migration check; atomic/DAG/sharding/nested hardware
acceptance remains the next campaign. Reservations were rechecked for zuestoll14
and zuestoll07, and the DUT was inspected without reset or service replacement.

The follow-up `b5dcd7c` replaces numeric outcome tags and accepted-error codes
with named Rust ABI variants and typed `reply`/`defer`/`fail` constructors.
`SystemError` is ONC-RPC status 5 and `ProcedureUnavailable` is status 3;
non-failure outcomes no longer use a dummy failure initializer. Protocol helper
version/authentication/reply tags are named as well. The platform C runtime
uses its existing named accepted-error enum at the same failure sites. Target
application/harness builds, existing Rust layout assertions, the codec capacity
check and native runtime regressions passed; no hardware behavior change or
new board validation is claimed.

## September 17 managed request integration

The platform adapter now uses the generic outcome API. Its single incoming
handler resolves and retains the registry entry once: atomic functions execute
inline, compositions return `DEFER` with owned execution context. Clients retain
the same procedure and BJSON/XDR envelope and need no atomic/composition metadata.
Codec output capacity is explicit across the Rust/C boundary. The pinned student
source descriptions below remain historical; its `EXEC_FIBER` and callback API
are not the current platform contract.

The handler's former XID argument is now an opaque request-context token; the
adapter does not inspect it. Hardware preserves the original XID for wire replies.
Possible token-based peer-information queries remain documentation-only; see
[reply contexts](../software/reply-contexts.md).

`FORWARD` implements local reply relay without a parent fiber for compatible
response encodings. It is available to applications but is not automatically
selected by Dandelion's composition optimizer. Direct replies from downstream
to the original client remain unimplemented. The earlier report search below
records that no student tail-call design was recovered, not a claim that the
new outcome design is absent. See [programming model](../software/programming-model.md)
and [blockers](../software/nested-rpc-blockers.md).

## What exists and where

All following paths are relative to `../sven-dandelion/`, not this repository.

| Question | Source entry points |
| --- | --- |
| Design, rationale, results | `report/main.tex`; `report/chapters/{Background,LauberhornKmod,LauberhornRuntime,Dandelion,Evaluation,ScalabilityLimits,Conclusion,Appendix}.tex` |
| Rust application and worker/engine ownership | `dandelion/dandelion_lauberhorn/src/runtime.rs`, `src/dispatcher/{execution,function,composition}.rs` |
| Rust/C API, serialization and async bridge | `dandelion/dandelion_lauberhorn/src/lauberhorn/{ffi,codec,marshal,laub_async,lauberhorn}.rs` |
| Extended C runtime | `platform/sw/include/lauberhorn.h`, `platform/sw/include/lauberhorn/{rpc,future}.h`; `platform/sw/rt/{worker,rpc,future,pending_call_table}.c`, `platform/sw/rt/fiber/` |
| Software NIC/scheduler mock | `platform/sw/kmod_simulation/src/{nf_hook,lauberhorn_hw,sched,chardev,netdev}.c`, `setup.sh`; backend switch in `platform/sw/core/eci/core.h` |
| Application checks and measurements | `dandelion/dandelion_lauberhorn/tests/`, `benchmarks/`; `platform/sw/apps/benchmarks/` |

## Architecture and execution contract

- Development and evaluation use a **kernel software mock**, with real Linux processes and network traffic. This is neither RTL simulation nor evidence of operation on the coherent FPGA datapath. RX/TX ioctls stand in for cache-line exchanges; netfilter interception, kernel networking and software scheduling remain on its critical path.
- Dandelion removes its original worker-placement scheduler. One ONC RPC endpoint accepts a function/composition name plus serialized input; this avoids allocating one scarce NIC endpoint per function. HTTP retains function/composition registration.
- Each worker has an isolation engine; engines retain upstream process/KVM-style backends. Evaluation uses process isolation. The shared software registry resolves names. Do not interpret “scheduler removed” as removal of all scheduling logic: a composition coordinator still evaluates DAG dependencies, shards inputs, dispatches ready nodes as async RPCs, joins shard results and releases successors.
- The endpoint uses `EXEC_FIBER` because compositions await nested calls. Simple handlers may use `EXEC_INLINE`. Fibers yield while awaiting replies, preventing all workers from blocking on local nested calls. This is cooperative runtime suspension, not arbitrary OS preemption of an executing handler.
- Rust codecs use explicit allocation/free hooks through `rpc_codec_t`/`rpc_ops_t`, avoiding a requirement that foreign-language objects be released by libtirpc. Handlers with suspended invocations must be reentrant; static response storage is generally unsuitable.

## Nested calls, continuations and timeouts

`lauberhorn_call_async` returns through futures (individual await, select/join and await-any sets); `lauberhorn_call_cb` delivers replies to inline/fiber callbacks. A fixed stackful `ucontext` fiber pool and pending-call table retain state. XIDs encode a slot and generation to reject stale replies; the mock reserves some generation bits for its own routing. Wakeups use generation-tagged fiber states to address reuse/lost-wakeup races.

The maintainer clarifies that tail calls were proposed near the project end, with implementation uncertain. **No written tail-call design or implementation was found in the inspected report and source revisions; leave this for later maintainer work.** This conclusion includes searches across every report chapter and the appendix for reply forwarding, continuation transfer and direct replies, plus inspection of the runtime call/reply lifecycle and Rust async bridge:

- `LauberhornRuntime.tex`, “Hardware-Managed Continuation State” (lines 433–438), proposes storing an opaque fiber/continuation pointer in the NIC and returning it with a nested reply. It explicitly says the idea was not pursued. This accelerates matching a reply to the waiting caller; it does not specify transferring responsibility for the original reply to a downstream callee.
- `RelatedWork.tex` (line 24) describes eRPC sending the original response later from a continuation. That is related-work description, not a Lauberhorn tail-call design. `Appendix.tex`'s nested adder awaits both child results and then returns a newly assembled parent response.
- `sw/rt/rpc.c` keeps the original descriptor in `rpc_call_fiber_ctx`; `rpc_call_fiber_done` sends the parent reply after the handler returns. Both public outgoing-call APIs allocate a pending-call slot and deliver completion locally through a fiber or callback. No reply-ownership transfer path was identified. The Rust bridge exposes async futures; its callback wrapper is explicitly unimplemented.

There is therefore no recovered tail-call design to assess. Do not treat the separate NIC continuation-pointer proposal or callback API as evidence of tail-call support.

Nested RPCs currently have a one-second timeout. This is a reply-completion timeout, **not** the proposed mechanism to kill a task that holds the 2F2F receive critical section too long. The report's measurements used timeout sweeps every 100 worker-loop iterations; inspected `worker.c` instead sweeps at approximately one-second intervals. Historical latency tails do not describe that later implementation exactly.

## Evaluation interpretation and gaps

Report results: approximately 120–140 kRPS for one adder service across four isolated cores; approximately 60 kRPS for seven applications multiplexed over four cores. Dandelion single-function median latency improves about 9% versus upstream HTTP; compositions are about 2%/4% slower at depths 2/4. These are **mock-only, workload-specific results**, with network protocol and scheduler changes confounded. There is no instrumented attribution separating all Dandelion execution, scheduling and network costs. Estimated hardware throughput is a projection, not a measurement. The full-system baseline requested by the maintainer is **Demikernel**; the thesis does not supply that comparison.

Port/evaluation checklist:

- Complete reconciliation of the imported runtime with the real descriptors,
  generated headers, packet ownership and core mappings. The import and
  successful single-function probe do not establish nested-path support; see
  [integration staging](../software/nested-rpc-integration.md).
- Validate actual 2F2F progress and receive-critical-section release across fiber suspension, nested replies, timeout and shutdown. Mock scheduling fidelity cannot establish DCU/queue-pair safety.
- Define bounded admission/backpressure: fixed fiber/pending-call pools exhaust, some incoming requests are silently dropped, fiber stacks are fixed at 64 KiB without overflow detection, and cancellation remains unfinished. Service deregistration with in-flight work has undefined semantics; registration operations require external serialization.
- Extend transport/application support as needed: the report uses small UDP ONC RPC messages, without retransmission, multi-packet framing or ONC RPC authentication. HTTP communication functions were not ported; the wait API currently handles RPC completions. OS/process isolation alone does not establish network authentication or full-system security guarantees.
- Rebase/adapt Dandelion interfaces deliberately; report says upstream changed after its selected base. Local composition edges currently incur serialization and loopback RPCs. Shared intermediate-result tables and a separate inline function endpoint are proposals, not established optimizations.
- Reproduce on hardware, separate protocol/scheduler/engine contributions, exercise concurrent DAGs and failures, and compare with Demikernel under matching isolation and resource assumptions. See [paper goals](paper-goals.md) and [roadmap](roadmap.md).

## Packaging interface (2026-09-15)

The platform now supplies `lib.mkPlatform { pkgs; }`, target application
`callPackage`, and installed runtime headers/library/pkg-config; see
[interactive testing](../development/interactive-testing.md). Source inspection
confirms the sibling's `dandelion_lauberhorn/build.rs` links `lauberhorn` and
`tirpc`, while `src/config.rs` accepts `DANDELION_LAUBERHORN_CONFIG` and otherwise
reads checkout-relative `config.toml`. Its eventual package should use the
installed runtime's pkg-config metadata, install default config/assets under
`$out/share`, and default that variable to the installed config while respecting
user overrides. Keep writable function/state directories and logs external.
The root recipe can expose the latency executable or runnable test packages
separately. These were the September 15 packaging requirements. The directly integrated managed-outcome
and cooperative-worker adapters now build against the installed runtime; current
composition/placement acceptance is the active work above.
