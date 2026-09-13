# Nested RPC integration blockers

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
- [ ] Run Sven's nested adder (two futures plus callback) to sum 10; compare
  completion XIDs with the software pending calls. Track any B3 lifetime failure
  separately rather than treating correct packet fields as full completion.
- [ ] Check RPC session/in-flight CSR update and readback in host order against
  the captured traffic; verify neighbor miss/ARP and bypass still use Linux's
  wire-native addresses and packet bytes.
- [ ] Re-run the initial Dandelion 1×1 matmul smoke test with the matched batch.
  Broader composition/placement checks remain under B6.

Retain manifests, reset/program logs, packet captures and failed trials with the
[hardware test results](../development/hardware-test-results.md).

## B2 — Nested calls lack complete real-hardware transport metadata

**Evidence: source review; round trip blocked by B1.** `sw/rt/rpc.c` does not
populate the required PID, completion cookie and source port correctly, and
the byte-order repair is covered by B1. The real kernel also
needs the nested-reply listening port configured. Do not conflate Linux CPU
IDs, NIC datapaths, process identity and completion identity.

**Closure checks:** capture valid nested UDP calls on the peer, verify endpoint
and RPC fields, return replies through the FPGA and validate PID/cookie
completion routing. Run Sven's nested adder with the external peer responder:
two future calls plus one callback call, producing sum 10. Check the response
request ID separately: Sven's current handler leaves it uninitialized, and the
probe currently reports rather than validates it.

Use two physical machines for this gate. Local short-circuit/loopback remains
a TODO in [nested RPC](nested-rpc.md); switch self-reflection is unproven.
Correcting B1 alone cannot establish this round trip.

## B3 — Future/callback ownership and codec error paths are unsafe

**Evidence: source review; affected completion paths not reached by the failed
hardware nested test.** Coalesced fiber wakeups can prematurely release a live
pending slot; the fiber callback handler is uninitialized. Allocation and
codec failures are insufficiently checked, including negative marshal lengths
being assigned to unsigned packet lengths and uninitialized XDR objects.

Start at `sw/rt/{rpc,pending_call_table,future,oncrpc}.c` and `sw/rt/fiber/`.
Define ownership of pending slots, futures and results through completion,
timeout and reuse. Define bounded behavior when pending/fiber pools exhaust.

**Closure checks:** targeted software tests for coalesced wakeups, callback
execution, slot reuse, allocation/codec failure and pool exhaustion; then
hardware tests of both future and callback completion with B1/B2 fixed.
Verify that failures cannot send an invalid length or free live state, and
rerun the passing add/mul baselines.

## B4 — Worker shutdown and service cleanup do not complete reliably

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
