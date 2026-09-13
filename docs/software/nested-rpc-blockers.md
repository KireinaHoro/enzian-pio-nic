# Nested RPC integration blockers

Working queue for `feat/nested-rpc`, recorded 2026-09-10 from the campaign
documented in `e4eeaa7`. Work through B1–B4 in order; investigate B5 separately. B6 is an
additional Dandelion integration gate. B1's compiler repair is software-verified
as of 2026-09-13; its hardware check remains pending. B2–B6 are **open**. Close an
item with its fixing commit and validation evidence; retain failed trials.

The tested hardware/runtime revision is
`aa32b1af04f2fcdd9191c94784eba5bbad1ea25f`, checkpoint job 2812450.
[Hardware results](../development/hardware-test-results.md) retain exact source
revisions, image hashes, manifests, captures and machine state. Upstream add,
mul and Sven's standalone adder each passed 100 calls from a second Enzian;
one Dandelion 1×1 matmul invocation passed. These results do not establish
nested completion, clean shutdown, startup reliability or composition support.

## B1 — Generated nested descriptor accessors truncate fields

**Update, 2026-09-13: compiler repair implemented and software-verified.**
Mackerel commit `29f286545fc9b6e8292d6edd06ec02e1c13a8fab` on
`fix/datatype-boundary-access` fixes C and Rust datatype accessors. Both the
platform submodule and Nix input now select that revision. The dtype already
matched RTL; no padding or RTL layout change was needed.

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

**Hardware follow-up remains pending.** B1 now preserves `0xc0a88181` exactly;
on little-endian CPUs its bytes are still `81 81 a8 c0`. The nested sender's
extra `ntohl` is the separate B2 endianness defect. The wire-order
representation is `0x8181a8c0`, whose bytes are `c0 a8 81 81`. Do not
interpret correct bit preservation as a working runtime-driven nested round trip.
See the [endianness contract](nested-rpc.md#endianness-at-the-host-boundary).

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

**Remaining closure check:** verify correct destination resolution on hardware
using matched artifacts, together with the B2 runtime corrections. Compiler
regressions and the software closure build are covered above.

## B2 — Nested calls lack complete real-hardware transport metadata

**Evidence: source review; round trip blocked by B1.** `sw/rt/rpc.c` does not
populate the required PID, completion cookie and source port correctly, and
outgoing endpoint/RPC fields need wire-order conversion. The real kernel also
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
