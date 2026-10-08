# Cooperative scheduling and worker addresses

**October 8 status:** the original payload-isolation blocker has source
corrections and a matched functional baseline. Corrected 516232 bring-up,
GSYNC early NACK and hot/cold measurements passed within their recorded scope.
Expanded adversarial isolation and four-worker acceptance remain open; see the
[consolidated claims and results](../research/roadmap.md#current-status--october-8-consolidation).

The selected design is the sole ECI implementation. The implementation originated on
`feat/cooperative-yield`; software builds, focused simulations and finite board checks have passed,
but full acceptance remains outstanding. See the [control contract](../software/scheduling-control-interface.md),
[current scheduler](../software/scheduler.md) and [evidence ledger](../hardware/eci-implementation.md).
Superseded router, polling and activation proposals remain in Git history.

October 3 board evidence now includes two cold boots at a 133.281505 ms RX hold,
with concurrent stalled peers and bypass traffic. A loss-free measured interval
contains 5,926 ordinary handoffs without scheduling IRQs and matching activation
parities. Maximum-timeout refinement and the documented baseline-lockup question
remain open; see the linked evidence ledger for the finite scope.

## Execution boundary

RX means the calling worker has exhausted runnable local work. Handlers run to
finish or cooperatively wait for external results; applications release locks
before blocking in RX/yield. Ordinary syscall sleep retains the assignment.
Self-sustaining batch work is outside this cooperative execution contract.

At idle RX the NIC supplies a packet, stalls, returns a finite NACK, or returns
PREEMPT. PREEMPT carries no destination or application payload and consumes no
packet. Runtime immediately enters `lh_yield()` without further transport access.
Kernel establishes its no-return fence and accepts one blocking `PreemptInfo`
read. Autonomous transport cleanup completes before the destination/parity
snapshot returns. No ordinary scheduling IRQ, kernel transport access, cache
flush or activation commit write is involved.

Initialized threads bootstrap inside the same yield syscall. Initial/idle
reservation sets a sticky dispatch notification and sends a kick to a pinned
kernel dispatcher. Reader serialization and notification consumption tolerate
duplicate kicks. Linux idle/WFI handles CPUs without assignments. Packet-driven
allocation and existing watermark/background-discovery policy remain intact.

## Addresses and lifetime

The [selected 8 KiB layout](../../model/data/selected-8k.json) assigns immutable
physical-worker geometry. Each domain gets a monotonically allocated 19-bit PA
lifetime code and stable aliases for those workers. Threads may migrate between
workers; activation supplies the worker index and authoritative RX/TX parities.
Domain codes are not thread indices, Linux PIDs, scheduler slots or CPU numbers.
There is no mutable thread-prefix routing table.

Accepted AXI and coherence traffic retains its original address and owner until
completion. Final control invalidation precedes worker reuse, including A→B→A.
Unauthorized fresh access kills the requesting domain and prevents reactivation;
old cleanup still completes. Allocator state survives driver/application reset;
only a full coherence-reset boundary permits lifetime reset or resource reuse.

## Deferred scope and evidence

HOST_WAKE and its interface design belong to the next round. This stage has no
host-work fields, cookies, subscriptions or pseudo-packet interface. Existing
deadline kicks are not integrated as parked-domain activation. Full kill/exit
recovery, PCIe scheduling and timing optimization are also outside this stage.
Fatal lifecycle failures fence claims and retain unresolved resources until reset.

The [bounded scheduling composition](../hardware/scheduling-composition.md) and
[coherence progress analysis](../hardware/coherence-progress.md) retain their
stated abstraction boundaries. Focused RTL checks do not establish silicon
infinite-stall progress. Acceptance includes a fresh Enzian RX-stall boundary
probe, contention, two cold boots and every exposed L2C_TAD status unit, using
matched artifacts and preserved fault traces.
