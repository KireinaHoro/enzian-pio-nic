# ECI implementation

ECI is the active Enzian transport. The [queue-pair design](queue-pair-design.md)
defines address-space encoding and DCU coloring; the
[HW/SW interface](../architecture/hw-sw-interface.md) defines cache-line contents,
payload placement, ownership cleanup and replay. The
[scheduling contract](../software/scheduling-control-interface.md) defines
cooperative DESCHED and activation. These contracts require matching generated
headers, software and FPGA artifacts.

## Address ownership and integration

`EciAddressMap` and `eci_address_map.h` are generated from the selected 8 KiB
configuration. Seven physical-worker aliases share a color-neutral 19-bit
lifetime namespace. Four workers and sixteen active slots remain defaults.
Slots, lifetime codes, Linux PIDs, thread indices and CPU IDs are distinct.

`EciAxiIngress` checks fresh access ownership and retains accepted full addresses
in AXI USER metadata. Outstanding R/B responses remain charged to their physical
worker. RX writeback has an independent replay-shadow path. LCI commands retain their
full addresses through LCIA matching; owner changes cannot retag them. Final
control invalidation completes before transport-slot reassignment; lifetime codes
are not recycled. TX descriptors and reply-context
tokens are checked against the trusted assignment.

`EciDomainAllocator` saturates after 524288 codes. FPGA initialization owns its
reset boundary; application reset and driver reload cannot recycle codes.
Application reset after allocation or a fatal lifetime fault requires a full
coherence reset. Unauthorized fresh reads fault the requesting domain; DCS does not propagate AXI errors as a safe application
fault. Sticky domain faults fence claims and trigger domain termination. Old
cleanup traffic is still serviced. Complete resource-recycling recovery is deferred.

`EciDeschedulingControlPlugin` sends an IRQ only for idle activation. Ordinary
DESCHED consumes no packet and sends no scheduling IRQ. The serialized info
read acknowledges relinquishment and waits for autonomous cleanup. Other
AXI-Lite accesses may wait behind it. `DeschedInfo` has no valid bit and is never
polled. A read-only yield-request check rejects unauthorized ordinary calls.

The kernel shares activation handling between pinned idle-dispatch threads and
ordinary yield. The immutable pool is declared before creation and advertised
only after every thread registers following initialization. Mapping only maps
aliases. Runtime yield replaces the physical alias and both local parity bits.
The kernel does not program parity or flush caches for an ordinary handoff;
there is no separate activation commit write.

After the committed response, the outgoing thread becomes claimable before the
helper waits for an incoming thread. This prevents simultaneous A→B/B→A claim
waits. Unrelated wakeups cannot return an old activation; failed delivery kills
and fences the affected domain. Ordinary syscall sleep retains assignment.

## RX writeback and permission changes

An exclusive RX load can return data when invalidated even without a CPU store.
The selected R12→RA3 path grants exclusive permission (`PEMD`); its invalidation
uses `FLDX_EH` and can receive data-bearing `HAKD`. The earlier shared RX path
used `PSHA` / RA2 and `SINV_H` with no returned data. A data-bearing acknowledgment
does not by itself establish that software modified the line.

DCS waits for AXI write completion before issuing WDDA/LCIA. RX must therefore
complete AW/W/B independently of its read FSM: the next RX control read can be
waiting for the very invalidation whose writeback is arriving. Blocking writes
behind that read creates a circular wait. The
[historical failure and intervention](../../agents/hardware-state.md#rx-writeback-regression-evidence)
record this boundary with board traces and a directed DCU/router reproducer.

The [cache-line interface](../architecture/hw-sw-interface.md#rx-contiguous-payload-and-replay)
defines the independent writeback service and mutable replay shadow. Returned
payload bytes are private replay data, not commands or writes to the backing RX
packet allocation. See [validation](../development/validation.md#rx-writeback-regression-reproducer)
for the directed DCU/router checks.

## One-shot early NACK implementation

The GSYNC monitor observes accepted VC6/VC7 requests from **all PPVIDs** without
changing either immediate GSDN responder. A coalescing request/acknowledgement
CDC transfers notifications from the system clock to the application clock;
each delivery emits one application-clock pulse. Arrivals during a CDC
handshake are retained/coalesced for a subsequent delivery, not counted as
individual GSYNC responses. The handshake never backpressures the responder.

All RX routers (bypass and four workers in the default configuration) use that pulse alongside their
normal timeout condition, **only in `waitDesc`**. An available descriptor still
wins, just as at timeout. Otherwise the router selects an ordinary NACK with
zero payload, retaining the existing invalidation and AXI response sequencing.
No per-router sticky release is retained: idle notifications, accepted
responses and same-line replays are unaffected, and later RX reads retain the
configured timeout. DESCHED/fatal-release priority is unchanged.

This addresses the observed case of peers already waiting for RX descriptors.
It does not promise to catch a read that only reaches `waitDesc` after the pulse,
or bound DCS/AXI/invalidation backpressure. CPU global synchronization retirement
is still not directly observed. The [controlled before/after experiment](../research/eci-progress.md#corrected-module-early-nack-measurement--october-5)
observed the hold-dependent millisecond waits disappear on its tested requests.
That result covers one/two simultaneously observed stalled peers, not every
possible timing of a GSYNC pulse or CPU synchronization event.

## Calibrating the RX stall timeout before NACK

The NIC can hold a receive request while waiting for work, then return NACK
when its programmable stall timer expires. Calibrate that setting against the
actual CPU/ECI timeout behavior on matched hardware: an overly long pending
coherent read can trigger a ThunderX L2C_TAD/LFB timeout before the response
completes. The programmable NIC timer is not the CPU's architectural timeout.
Never disable or extend architectural protection to obtain a passing result.

The [RX router](../../hw/src/lauberhorn/host/eci/DcsRxAxiRouter.scala) can leave
the wait early for a descriptor, yield, fatal release or GSYNC release. Its timer
covers waiting for work; invalidation and response delivery can add latency.
Therefore measure the actual request-to-NACK interval and verify that a trial
exercised the intended stall rather than an early-release path. A register
setting alone is not an end-to-end coherence deadline guarantee.

Use the [validation procedure](../development/validation.md#eci-receive-stall-calibration)
for parameter readback, fault preservation, trace pairing, contention and TAD
assessment. The [progress evaluation](../research/eci-progress.md) records finite
operating points and their limits; no historical screening bracket is an
architectural timeout constant or a setting transferable to another image.

## Verification boundaries

[Validation](../development/validation.md#cooperative-scheduling-and-slice-routing)
covers generated address collateral, ownership/fault handling, cooperative
handoff, worker-count interfaces and production slice routing. The selected map
was checked across all 524288 lifetime codes on seven workers (3,670,016
combinations). Production slice control-array tests exercise 812 address trials
and held reads alongside independent worker/bypass progress. Their RAM and trace
sinks are test models; they do not instantiate the full payload datapath or
ThunderX. Generating and cross-building worker counts 1–7 is an interface check,
not seven-worker board acceptance.

The [formal models](../formal/README.md) state resource, fairness, ownership and
endpoint-service assumptions separately. Concrete delayed-LCIA, retained-address,
final-control invalidation and A→B→A tests check corresponding boundaries; they
do not establish full-system refinement or infinite-stall silicon progress.
[Board evaluation](../research/eci-progress.md) preserves actual finite contention
and GSYNC coverage, with matched artifacts and trace limitations. Remaining
application-driven validation and recovery work are tracked in the
[roadmap](../research/roadmap.md#evidence-and-remaining-claims).
