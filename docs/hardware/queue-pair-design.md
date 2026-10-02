# ECI worker endpoints and domain aliases

The selected ECI design uses physical-worker RX/TX endpoints and stable
per-domain aliases. There is one bypass endpoint plus the configured workers.
A thread's endpoint is selected by its activation's worker index; a thread is
not permanently attached to one worker. Domains declare an immutable pool before
registration, and initialized threads wait for packet-driven activation.

The [8 KiB map](../../model/data/selected-8k.json) places RX controls at
`0x300/0x380`, TX controls at `0x1300/0x1380`, and twelve overflow lines in each
direction. The color-neutral 19-bit domain code changes physical identity while
preserving the worker's selected coherence placement. See [encoding and capacity](coherence-progress.md#concrete-domain-qualified-pa-encoding).

The decoder identifies physical worker and domain without consulting a thread
routing table. Trusted ownership gates fresh traffic. Accepted transactions
retain their original address through AXI responses and coherence completion;
owner changes never retag them. A→B→A must invalidate the last control line before
reuse. Rejected fresh reads kill their requesting domain because AXI errors alone
do not prevent DCS cache allocation. Late writeback and LCIA still complete.

Default capacity is four workers and sixteen active domain slots; the geometry
supports one through seven workers. Scheduler slots and PA lifetime allocation
are separate capacities. Codes allocate monotonically, survive driver reload,
and are not recycled before a full coherence reset. Unsupported fatal lifetimes
retain resources and prevent reactivation.

This is the only selected implementation. Superseded thread-owned routing
alternatives are available in Git history. See the [control contract](../software/scheduling-control-interface.md)
and [implementation evidence](eci-implementation.md) for remaining validation.
