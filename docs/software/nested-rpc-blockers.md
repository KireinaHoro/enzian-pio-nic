# Nested RPC integration blockers

See the [registered endpoint and internal-loopback contract](nested-rpc-endpoints.md) for the B2/B6 batch.

Working queue for `feat/nested-rpc`, recorded 2026-09-10 from the campaign
documented in `e4eeaa7`. Work through B1–B4 in order; investigate B5 separately. B6 is an
additional Dandelion integration gate. B1 includes compiler, RTL alignment and byte-order repairs
as of 2026-09-13; its hardware check remains pending. B2–B6 are **open**. Close an
item with its fixing commit and validation evidence; retain failed trials.

The tested hardware/runtime revision is
`aa32b1af04f2fcdd9191c94784eba5bbad1ea25f`, checkpoint job 2812450.
[Hardware results](../development/hardware-test-results.md) retain exact source
revisions, image hashes, manifests, captures and machine state. Upstream add,
mul and Sven's standalone adder each passed 100 calls from a second Enzian;
one Dandelion 1×1 matmul invocation passed. These results do not establish
nested completion, clean shutdown, startup reliability or composition support.

## Status after master refresh — 2026-09-17

Master `685b1f4` adds CI image/dependency maintenance, native-host deployment
helper construction, shared reachable Vivado license defaults, application recipe
placement and the blocks trace-DMA reset-startup test fix. It does not change
this branch's nested runtime, kernel endpoint implementation or datapath RTL;
none of B1–B6 is closed by this merge. Timing candidates remain on their separate
branches, as recorded in the [physical experiment ledger](../hardware/physical-experiments.md).

The September 16 integration `f827a73` removed the app-contract packaging gap:
Dandelion and its explicitly selected interactive test harness use installed
assets, wrapped entry points, configurable writable state, and shared deployment
images. Both images and their extracted closures passed packaging validation;
see the [package validation record](../../sw/apps/dandelion/README.md).
This is not current-batch board evidence.

| Blocker | Remaining work |
| --- | --- |
| B1 | Validate repaired/aligned descriptors and host/wire byte order on a matching new checkpoint. |
| B2 | Validate registered remote endpoints, completion routing and internal loopback on hardware; overload progress remains unvalidated. |
| B3 | Validate explicit reply/fail/defer/forward, fiber and transport admission, errors and forwarding on matching hardware; callbacks are removed. |
| B4 | Recovery, watchdog controls and deadline activation are implemented in source; verify matching-board shutdown, cancellation and resource reuse. |
| B5 | Diagnose intermittent module-load external aborts and demonstrate repeated reliable startup. |
| B6 | Verify actual isolated-worker CPU placement and run single functions, DAGs, sharding and concurrent nested compositions. |

Per-request CSV tracing and hardware in-flight capacity/backpressure/timeout
retirement remain pre-merge requirements. Prioritize B3 admission and B4 lifecycle
work before the bundled checkpoint campaign; investigate B5 separately. The old
checkpoint 2812450 still cannot validate this branch's new ABI.

Merge validation: `runtimeTests`, `checks.x86_64-linux.runtime-interface` and
`checks.x86_64-linux.trace-tests` built successfully (five DMA plus seven dump
cases). Interactive/workflow helper checks were satisfied from the Nix cache.
Dandelion, both deployment images and `ciDependencies` evaluated successfully;
images were not rebuilt in this refresh. Evidence: ignored
`out/master-refresh-20260917/{checks.log,outputs.json}`. No implementation or
board run was launched; the merge is pushed with `ci.skip`.

## B1 — Nested descriptor truncation and wire byte order

**Update, 2026-09-13: compiler repair and ECI ABI cleanup implemented.**
Mackerel commit `29f286545fc9b6e8292d6edd06ec02e1c13a8fab` on
`fix/datatype-boundary-access` fixes C and Rust datatype accessors. Both the
platform submodule and Nix input now select that revision. The original dtype already
matched RTL; the compiler repair did not require a layout change. A subsequent
ABI cleanup aligns the nested scalar fields to avoid split accesses.

The compiler selected an access width using `offset % 8`, but rounded the
actual access address using `offset % access_width`. It now uses byte accesses
when a field crosses the chosen scalar boundary, and widens C operands before
shifting into a 64-bit access. Existing scalar access locations remain intact
where the field fits. Descriptor ownership and the 2F2F barrier/doorbell
sequence are unchanged; byte accessors do not promise atomic updates.

Verification:

- The old runtime derivation explicitly used the generated header directory
  reproduced by the failing probe; this was not inferred from a stale local
  generation directory.
- New Mackerel regressions execute generated C and Rust at two optimization
  levels against independent bit-level reference operations. They cover 180
  offset/width combinations, upper-bit patterns, adjacent-bit preservation,
  and unaligned 64-bit fields. C runs include undefined-behavior sanitization.
- A one-off probe against the old and fixed Nix-generated headers confirmed
  the diagnosis and repair. Regression coverage lives in Mackerel
  (`tests/datatype_access.rs`); the downstream probe has been removed.
- `nix build .#devHdrs .#runtime .#deployFs` passed, including the AArch64 runtime,
  kernel-module vermagic check, microbenchmark and squashfs closure.
- Ten Mackerel tests pass. The full suite still fails `module_system`; that
  failure was reproduced on unmodified `8c744d3` and was already documented in
  Mackerel's flake. It is not silently treated as a passing full suite.

Evidence: `out/b1/{tested-runtime-inputs,old-probe,test-before-nested,
original-module-system,mackerel-suite,mackerel-suite-filtered,old-full-probe,
nix-probe,platform-build}.log`. No new Enzian test or bitstream build was run.
The build was performed with a dirty-tree version marker; it is build evidence,
not a deployment image paired with an existing checkpoint.

**ECI ABI cleanup:** byte-order conversion now happens in hardware. All RPC
XIDs, nested endpoint fields and RPC session/in-flight control-table numbers
are host-order at the CPU boundary. The runtime passes numeric fields directly
and parses the destination with `inet_network`. Wire-native bypass packets and
Linux neighbor/address values retain their existing representation, avoiding
unnecessary software conversions. See the complete
[boundary audit](nested-rpc.md#endianness-at-the-host-boundary).

Nested TX and completion RX place 16 bits of padding after PID so cookie and
following 32-bit fields are naturally aligned. Existing trailing padding is
reduced/moved; inline payload offsets and capacities are unchanged. RTL,
Mackerel declarations and the simulation host model are updated together.

Final-ABI software validation (2026-09-13):

- `nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcNestedSim lauberhorn.host.eci.OncRpcSim -- -l org.scalatest.tags.Slow`
  passed: 10 registered cases, including three pre-existing empty TODO bodies.
  The substantive cases check nested wire packets/neighbor resolution, reply
  completion fields, host-order in-flight CSR readback, normal server replies,
  and receive/scheduling behavior.
- After adding session CSR update/readback assertions, the two targeted server
  TX cases passed with `nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcSim -- -z tx-rpc-`.
  These install host-order session fields, read them back and check the emitted
  reply packet independently.
- `nix build .#devHdrs .#runtime .#deployFs --no-link --print-out-paths -L`
  passed. Generated nested cookie/XID/address/port/program accessors use aligned
  scalar accesses; cookie/XID are at bytes 8/12 on both TX and completion RX.
  Header inspection is retained as local evidence, not a downstream Mackerel test.

Logs: `out/b1/{eci-endian-rpc,eci-endian-session,eci-endian-build,eci-accessor-inspection}.log`.
Build outputs: headers `/nix/store/0f23c99p3mib1bmr8v58nrlfjir1j256-lauberhorn-dev-hdrs`,
runtime `/nix/store/n97nmjldzgr6mkp7lg6bklvkp3vbilxq-lauberhorn-rt`,
closure `/nix/store/86vx7a3q4hx4jj3qjhkg5xs7mvlq6yi9-squashfs.img`.
The build uses a dirty-tree marker and is software-build evidence only.

B1 remains **open** pending matched-artifact
Enzian validation. A new hardware checkpoint is required; the September 10
checkpoint is incompatible with these headers and RPC XID semantics. B2 still
supplies PID/cookie/source-port/reply-listener integration for a full round trip.

**Evidence: hardware observation and host-side reproduction.** The peer saw
three DUT ARP requests for `129.129.0.0` instead of the configured
`192.168.129.129`. The matched generated destination accessor shifts a 32-bit
value by 16 and stores through a 32-bit pointer, losing half the field.
The archived host-side probe reproduced
`input=c0a88181 rtl_daddr=00008181`. Cookie, XID and program fields use the same
problematic access pattern.

Start at `hw/src/lauberhorn/host/eci/EciHostTxCtrlInfo.scala`, its Mackerel
definitions/generator, and `sw/core/eci/core.h`. Audit RX completion fields too.
For future changes, fix the generator or coordinate a layout change with RTL; never hand-edit
generated headers. Read the ECI progress/routing notes before changing layouts
or access ordering. Preserve 2F2F RX/TX: no userspace fast-path ioctls.

### Pending bundled implementation / Enzian checks

Per maintainer instruction on 2026-09-13, accumulate hardware changes and run
one implementation/test campaign for the batch. Push intermediate commits with
`ci.skip`; do not start a separate bitstream build for this B1 cleanup. Keep B1
open until the relevant checks below pass and link their evidence here.

- [ ] Build the final batch checkpoint and review implementation timing. Record
  the exact commit, configuration, checkpoint job and bitstream hash.
- [ ] Rebuild the software closure from that same batch revision; verify generated
  headers, kernel vermagic and hardware version before deployment. Existing
  checkpoint 2812450 is incompatible with this ABI.
- [ ] Re-run basic add/mul and Sven's standalone adder from the second Enzian.
  Check server call/reply XIDs and packet bytes with asymmetric values.
- [ ] With B2 transport metadata in place, capture nested calls resolving the
  intended peer IP, with correct UDP ports, XID and program/version/procedure.
  Exercise inline and overflow payloads, ensuring cookie/PID/XID survive the
  aligned TX/RX descriptors and route the completion correctly.
- [ ] Run Sven's migrated nested adder (three futures, first two summed) to sum 10; compare
  completion XIDs with the software pending calls. Track any B3 lifetime failure
  separately rather than treating correct packet fields as full completion.
- [ ] Check RPC session/in-flight CSR update and readback in host order against
  the captured traffic; verify neighbor miss/ARP and bypass still use Linux's
  wire-native addresses and packet bytes.
- [ ] Re-run the initial Dandelion 1×1 matmul smoke test with the matched batch.
  Broader composition/placement checks remain under B6.

Retain manifests, reset/program logs, packet captures and failed trials with the
[hardware test results](../development/hardware-test-results.md).

## B2 — Registered endpoint transport and internal loopback

**Update, 2026-09-14: implementation and software/RTL checks complete; hardware
gate remains open.** `9e0d568`, `11c9d24`, `4ccf721`, `c3af126` implement endpoint
registration, Linux socket reservations, FPGA reply listeners, context/PID
ownership, cookie lookup, cached descriptors and control rollback. `22639fd`
adds internal loopback with separate whole-packet call/reply buffers,
round-robin ingress selection, packet/ack association and a registration
handshake before call visibility. No FPGA bitstream was built for this batch.

`nix build .#runtimeTests` executes five native regressions covering the public
API, control fault injection, pending slots, codecs and RPC dispatch. They cover
invalid/duplicate initialization, ownership, namespace rejection, simulated
Linux/FPGA port collisions, capacity and rollback, busy destruction, stale
cookies/unexpected XIDs, immediate replies, callback pool exhaustion, coalesced
futures and generation retirement before wraparound. Kernel socket/CSR tests
use fault-injection shims; they do not establish behavior on the real NIC.

`nix develop -c mill gen.test.testOnly lauberhorn.host.eci.OncRpcNestedSim lauberhorn.host.eci.OncRpcSim -- -l org.scalatest.tags.Slow`
passed 11 registered cases, including three pre-existing empty TODO bodies.
The new local case checks asymmetric fields, inline/overflow data, immediate
replies, queued calls, reversed completions, mixed physical/local ingress,
receiver backpressure and no nested CMAC output. Multi-process saturation and
general overload progress remain unvalidated.

**Hardware closure checks:** deploy matching final-batch artifacts; capture
remote calls with valid endpoint/program fields and PID/cookie completion
routing. Run the adapted nested adder against the second Enzian responder
(three futures, sum 10 from the first two and request ID 7). Also verify internal local
traces and absence of nested physical traffic. The new software cannot be used
with checkpoint 2812450. See the [endpoint contract](nested-rpc-endpoints.md).

## B3 — Bounded execution, request ownership and error delivery

**Implementation batch, 2026-09-17 (hardware acceptance pending):** incoming
handlers now start inline and return `REPLY`, `FAIL`, `DEFER` or `FORWARD`.
Service execution modes and the public nested-callback API are removed. The
320-fiber pool (64 KiB stacks, shared across workers) only limits deferrals;
exhaustion destroys owned context/request and emits `SYSTEM_ERR`, without a
separate waiting-request queue. No stack migration or hardware reinjection is
involved. The separate pending-operation table has 640 slots.

Future submission/await/select/join reject default-stack use before side effects
in debug and release builds. Serializers receive output capacities. Forwarding
retains parent reply metadata in a tagged child operation and relays completion
without a parent fiber. Dandelion classifies once in its existing handler:
atomic functions run inline, compositions retain the resolved entry and defer;
clients and envelopes are unchanged.

Hardware reserves nested routing state before TX admission and returns a definite
capacity rejection. Accepted/error status crosses generated ECI descriptors and
ONC-RPC encoder/decoder paths; rejected calls do not wait behind a full table.
The TX acknowledgement remains stable across voluntary cache-line reloads and
releases buffer ownership only after consumption. New runtime/header/kernel and
bitstream must be deployed together. Timeout/DMA uncertainty still quarantines
endpoint state; no claim of B4 closure is made.

See the [API contract](programming-model.md). Raft service events/timers and
resident memcached transport integration are [design-only follow-ons](service-execution-design.md).
Their implementations and harnesses are intentionally excluded from this run.

**Incoming admission follow-up, September 17:** the old session table is now a
per-request UDP reply-context table. Hardware allocates before host dispatch,
retains the original XID/return tuple, and retires a slot once its terminal reply
has captured the routing metadata. Host RX/reply TX carry an opaque 32-bit
slot/generation token in the former XID field; descriptor sizes and 48-byte inline
payloads are unchanged. Different clients using the same XID no longer collide.
Full capacity rejects without allocating or dispatching, with a hardware
`SYSTEM_ERR` reply. The bounded rejection queue drops excess error replies under
congestion, counted separately, so it cannot block completion ingress. Token
generations wrap; authentication and reset/abnormal-exit cleanup are not added.
See [layout, lifecycle and deferred metadata queries](reply-contexts.md).

The default incoming and outgoing hardware tables each hold 16 entries. The
320-fiber exhaustion regression remains an injected-transport test, not evidence
that hardware can retain 320 parent requests. B3 still needs matched-hardware
acceptance; requests abandoned without replies remain B4 lifecycle work.

The entries below preserve historical findings and fixes; callback acceptance
is superseded by future/forward validation for this batch.

**Update, 2026-09-14:** `f9221dc` preserves future ownership through coalesced
wakeups, initializes fiber callback handlers, retries callbacks when the fiber
pool is full, checks codec/allocation errors, initializes XDR storage, retains
callback endpoint ownership until execution and serializes timeout timestamp
access. `822f6b1` drains retried callbacks before blocking on receive.
Generation wrap retires a software slot. Native tests exercise these
paths, including immediate replies and exhaustion. The Dandelion patch also
repairs typed Rust response freeing and initialized decode storage. B3 remains
open for hardware completion acceptance and broader admission/error handling;
handler-fiber exhaustion can still drop incoming requests.

**Evidence: source review; affected completion paths not reached by the failed
hardware nested test.** Coalesced fiber wakeups can prematurely release a live
pending slot; the fiber callback handler is uninitialized. Allocation and
codec failures are insufficiently checked, including negative marshal lengths
being assigned to unsigned packet lengths and uninitialized XDR objects.

Start at `sw/rt/{rpc,pending_call_table,future,oncrpc}.c` and `sw/rt/fiber/`.
Define ownership of pending slots, futures and results through completion,
timeout and reuse. Define bounded behavior when pending/fiber pools exhaust.

**Closure checks:** targeted software tests for coalesced wakeups, deferred
execution, forwarding, slot reuse, allocation/codec failure and pool exhaustion;
then hardware tests of futures, forwarding, explicit errors and overload with
B1/B2 fixed. Confirm atomic dispatch still works at full fiber occupancy.
Verify that failures cannot send an invalid length or free live state, and
rerun the passing add/mul baselines.

## B4 — Worker shutdown and service cleanup do not complete reliably

The approved [process recovery and watchdog design](process-recovery.md) includes
whole-process forced termination, explicit-kill reclamation with the execution
watchdog disabled, and a matching-bitstream Enzian campaign. Implementation is
in progress. The source now contains stop-to-idle worker drain, PID-wide table
retirement, signal recovery, internal deadline activation, optional execution
monitoring and trace hooks. Ownership metadata or passing RTL tests alone do
not close B4; the matching-board campaign below remains required.

**Evidence: hardware observation plus source-review gaps.** After SIGTERM the
standalone adder leader remained `Zl`; three workers remained `Il` in
`do_notify_resume`. Logging stopped, but the process did not fully exit. The
DUT was subsequently powered down. This observation does not isolate the root
cause to either the runtime or kernel scheduler.

Review the real service-deregistration ioctl argument and service-ID handling,
worker shutdown, cancellation, and timeout progress while blocked in RX.
Existing service-control ioctls are distinct from the forbidden RX/TX ioctl
fast path. Check kernel scheduling and 2F2F ownership before changing wakeups.

**Closure checks:** stop an idle service and a service with outstanding work;
verify all threads exit, resources/registrations are released, and a new
service can operate without a power cycle. Exercise timeout/cancellation and
retain kernel logs. A zombie leader or parked remaining workers is a failure.

## B5 — Hardware initialization is intermittent

**Evidence: hardware observation; cause unresolved.** A verified full reset
and successful programming were followed by a synchronous external abort
`0000000096000210` in `probe_versions+0xa4/0x258` during module loading. The
application did not start. The immediate full-reset retry passed module/revision
checks and 100 adder calls. Do not attribute this to the runtime import without
additional evidence or count it as an arithmetic failure.

**Closure checks:** investigate shell/ECI startup and reset/program sequencing
using the matched checkpoint and retained logs. Repeat full boot/program/module
verification trials, recording every attempt and rail readings. Demonstrate
the proposed correction across repeated trials; one passing retry is not
evidence that the reliability issue is resolved. Keep this investigation
separate from the deterministic descriptor failure.

## B6 — Dandelion process-engine placement and compositions need validation

**Update, 2026-09-14:** the flake pins the Dandelion snapshot and builds the
tracked endpoint/FFI/placement patch, `server_tests` harness and deployment
squashfs. The original recipe silently built application binaries instead of
`server_tests`; the integration fixes its Cargo target and checks that the
harness is installed. Rust/C layout assertions compile. Fixture arithmetic is
independently checked by `sw/apps/dandelion/check-fixture-results.py`; the nested
fixture submits four concurrent, distinct invocations. No current-batch
Dandelion service test has run on Enzian. B6 therefore remains open.

**Evidence: source review and limited hardware smoke result.** The inspected
port initializes engines with worker indices 0–3; `mmu_worker` treats those
as Linux CPU IDs. This setup's isolated NIC RPC workers use CPUs 44–47. A
successful 1×1 matmul does not validate the intended isolated-core policy.

Start at the external Dandelion snapshot's
`dandelion_lauberhorn/src/runtime.rs` and
`machine_interface/src/{bin/mmu_worker.rs,function_driver/compute_driver/mmu.rs}`.

**Closure checks:** reconcile engine placement with isolated Linux worker CPUs
and verify actual process/thread affinities. Rerun single-function matmul, then
exercise compositions and sharding after nested transport/completion works.
Choose an explicitly supported route for composition calls; do not assume
local loopback or switch hairpin support. The full Rust suite, performance,
concurrent DAGs and failure recovery remain unvalidated.

## Remaining integration requirements

Before merging, also restore per-request CSV tracing
(`LAUBERHORN_SERVER_TRACE_CSV` is currently ignored), and resolve/test hardware
in-flight-table capacity, acceptance/backpressure and timeout retirement.
These are retained requirements from the earlier review, not failures newly
isolated by this campaign. See [integration scope](nested-rpc-integration.md)
and [Dandelion provenance](../research/dandelion.md).

For each fix, record which software tests and real Enzian cases ran. Keep
generated headers, kernel mappings, cache-line accesses and RTL in agreement.
Push with `-o ci.skip` when no new checkpoint is needed; hardware changes need
a normal CI push or an explicitly launched build on `enzian-ba2`.

## B2/B3/B6 software batch evidence (2026-09-14)

Local logs: `out/b2-b6/b2-loopback-regression.log`,
`runtime-tests-complete.log`, `batch-build.log`. The latter built Dandelion,
its actual `server_tests` harness, matching runtime/kernel/generated headers,
and both deployment filesystems. These intermediate builds carry a dirty-tree
marker; use the final batch revision for the later checkpoint/software pair.
No timing, real socket namespace behavior, real worker placement, shutdown,
B1 hardware acceptance or composition execution is claimed by these builds.

## B3 outcome/admission validation (2026-09-17)

The native runtime suite passed, including default-stack rejection before side
effects, fiber/pending exhaustion, owned outcome cleanup, forwarding, timeout
and immediate-completion races. Dispatch tests ran with and without assertions;
codec tests ran with AddressSanitizer and UndefinedBehaviorSanitizer (leak
tracing disabled because the Nix sandbox prevents its process attachment).
The Rust bounded-encoder regression executed under target emulation and passed.
The migrated student fixtures passed compilation against the new interface.

All three nested ECI simulation tests passed. The server/bypass suites passed
13 registered tests; existing TODO cases without test bodies are not coverage.
Matching generated interfaces, runtime, kernel, Dandelion application and smoke
harness, and both deployment images built. Extracted image checks passed for
3,892 links/four ELF entries (Dandelion) and 3,497 links/three ELF entries
(microbenchmarks). Logs and image reports are in ignored `out/b3-20260917/`.

These are dirty-tree source validation builds, not a deployable checkpoint pair.
No Vivado implementation or board campaign ran. B3 remains open for matched
hardware acceptance. At that validation point incoming server-session overflow
was still unresolved; the reply-context follow-up above supersedes that finding. B4/B5 remain
open. Service-owned Raft work and resident memcached adoption are documented in
[the service execution design](service-execution-design.md), without runtime
implementation or application harnesses in this batch.

## Incoming reply-context follow-up validation (2026-09-17)

Native runtime tests passed, including opaque-token preservation through deferred
execution and forwarding, release-build checks, and sanitized codec/accessor
checks. Generated call RX/reply TX headers remain 16 bytes with the token at
byte 4 and the inline payload at byte 16. Dandelion and its emulated bounded-codec
regression passed; the migrated student fixtures compiled against the new API.

The context-table simulation passed 1,024 reuse cycles, stale-token rejection,
full capacity, identical XIDs across peers and simultaneous allocation/retirement.
The server suite passed nine registered cases (three existing TODO-only bodies),
including remote full-table rejection for empty/inline/overflow calls, unchanged
wire XIDs, different clients sharing XIDs, slot reuse and draining stale overflow
replies. Its receive stress cases now send terminal replies and bound outstanding
requests instead of relying on session overwrite. The extended local-loopback
case passed rejection at full incoming capacity and successful admission after
retirement; earlier remote nested descriptor/completion cases also passed.

Evidence is under ignored `out/reply-context-20260917/`: `build.log`,
`student-fixed.log`, `rtl.log`, `server-fixed.log`, and `local-final.log`.
Initial fixture failures are retained: randomly addressed clients needed configured
reply neighbors, and the temporary student compile tree initially omitted its
RPC schemas. A combined follow-up simulation lost its Mill worker connection
(`nested-final.log`); the extended local test passed on an isolated rerun.

All five bypass TX regressions passed (`bypass-final.log`), including the
multi-core cache-invalidation case.

The matching kernel module, application smoke harness and both deployment
images built successfully (`images.log`). These dirty-tree build checks do not
establish a deployable bitstream/software pair.

No new bitstream or Enzian campaign ran. The incoming overwrite implementation
gap is addressed; B3 remains open for matched-hardware acceptance, while abandoned
requests, reset recovery and shutdown remain B4 work. Metadata query APIs and
TCP reply-context variants are documented only.
