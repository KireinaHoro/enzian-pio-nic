# Cooperative scheduling implementation checks

The sole ECI contract is [cooperative activation](scheduling-control-interface.md).
The RTL, kernel and runtime implement that contract; [the evidence ledger](../hardware/eci-implementation.md)
separates completed checks from outstanding acceptance. Earlier designs are available in Git history.

## Kernel boundaries

- Declare an immutable initialized thread pool before worker creation. Register each thread after its callback and runtime setup; publish NIC capacity only when all declared threads are ready. Mapping aliases does not register or park a thread.
- Serialize side-effecting info reads with one reader mutex per worker. Idle dispatch requires a sticky notification and no application owner. Ordinary yield requires the calling owner and a pending yield request. Never poll DeschedInfo.
- Establish the outgoing task's no-return fence before read acceptance. Keep claim and lifetime locks outside blocking MMIO, affinity changes and waits.
- Claim a waiting thread atomically under lifetime protection. Concurrent dispatchers cannot claim the same thread. Capacity reservations must guarantee an eventual backing thread.
- Deliver one immutable activation snapshot containing worker index and both parities. Recheck the activation predicate after unrelated wakeups. Ordinary syscall sleep retains ownership.
- Fail closed after relinquishment if activation, affinity or result copying fails. Unsupported exit kills/fences the domain, retains unresolved resources and identities, and requires reset.

## Hardware boundaries

- Preserve packet-driven allocation, idle-first selection, the additional-core watermark and background discovery. Registration alone cannot activate a worker.
- Only idle activation produces a scheduling IRQ. Keep its notification sticky until authorized consumption; duplicate IRQs cannot cause duplicate info reads.
- DESCHED contains no payload or destination and consumes no packet. Runtime yields before further transport access.
- Info read acceptance acknowledges relinquishment. Its response may await autonomous cleanup, but cleanup cannot depend on another control access, a later kernel command or an interrupt on the blocked CPU.
- Shared AXI-Lite accesses may wait behind that read. Check eventual completion under explicit cleanup fairness, rather than claiming ordinary handoff never delays control traffic.
- Preserve full accepted addresses and ownership through AXI responses, invalidations, writebacks and LCIA. Current ownership cannot retag outstanding obligations. Reject unauthorized fresh access without blocking old cleanup.
- Validate TX descriptor and reply-context ownership against the trusted assignment. Complete final control invalidation before reuse, including repeated A→B→A activation.
- Allocate 19-bit PA lifetime codes monotonically; exhaust without wrap. Preserve allocation across driver reload, and reset only at a full coherence-reset boundary.

## Verification boundaries

`IdleDispatch` checks notification/consumption races and interrupt-free handoff.
`ThreadClaim` checks fixed-pool claims and reservations. Existing transport,
capacity and activation models provide separate obligations; model composition
and concrete implementation correspondence must be reported explicitly.

Directed simulations must cover delayed yield and cleanup, shared control
contention, both parities, all overflow lines, high address bits, isolation,
exhaustion, migration and full-address completion through actual DCS slices.
Kernel/runtime tests must cover initialization failure, concurrent claims,
unrelated wakeups and failed activation delivery.

For RX-stall acceptance, independently find the highest tested exception-free
RX hold setting, preserve fault traces, inspect every exposed L2C_TAD unit and
repeat under contention across two cold boots. Include stalled peers while
independent workers and simultaneous bypass RX/TX progress. Finite observations
do not prove infinite-stall progress.

Complete exit recovery, deadline activation and PCIe scheduling are deferred.
HOST_WORK is a [separate design](../proposals/host-work.md); it is not part of this implemented contract.
