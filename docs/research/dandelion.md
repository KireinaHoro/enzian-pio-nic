# Dandelion student port

Surveyed 2026-09-08. External sibling workspace: `../sven-dandelion/` from the platform root; it is not vendored here. Report: **Sven Glinz, _Porting Dandelion to the Lauberhorn smart NIC_**, bachelor thesis, February–August 2026, supervised by Pengcheng Xu and Timothy Roscoe. Inspected commits: report `ec49e8a`, platform `9e25181`, Dandelion `69c3088`. The report identifies upstream Dandelion `98c4968` (2026-03-09) as its port base; these are distinct revision identities.

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

- Reconcile the sibling's rewritten ABI, descriptors, generated headers, packet ownership and core mappings with this tree's simpler `sw/rt/{eci,oncrpc}.c` and hardware nested-RPC path. The student extensions are not already integrated here merely because they exist in the sibling.
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
separately. This establishes packaging requirements only: the sibling's nested
RPC/fiber API changes remain unmerged and its application has not been built
against this checkout's simpler runtime.
