# Scheduler and Linux integration

The sole ECI design is physical-worker address decoding with cooperative
PREEMPT/yield. Source integration is in progress on `feat/cooperative-yield`;
[implementation evidence](../hardware/eci-implementation.md) separates passing
checks from outstanding board acceptance. The [control contract](scheduling-control-interface.md)
is authoritative. Superseded implementations are available in Git history.

## Responsibilities and identities

The FPGA scheduler preserves packet-driven allocation, idle-first selection,
the additional-core watermark and background discovery of queued domains.
Linux chooses a waiting initialized thread and installs its CPU affinity.
Selection is not a completed fairness or latency-SLO policy.

Physical NIC worker indices, Linux CPU numbers, scheduler slots, Linux PIDs and
19-bit PA lifetime codes are distinct. Bypass is datapath zero; four physical
workers and sixteen active domain slots are the defaults. The selected address
geometry supports up to seven workers. Kernel workers use isolated CPUs and
FIFO scheduling; ordinary tasks do not share those CPUs.

A domain maps stable aliases for physical workers. Address decoding identifies
the worker and domain; admission checks its trusted installed owner. No thread
router, prefix reassignment, preemption control cache line or shared parity page
participates. Kernel scheduling never touches transport cache lines.

## Pool and activation

`lauberhorn_set_worker_count()` declares an immutable pool before creation.
Each thread completes its callback and runtime setup, explicitly registers,
and enters bootstrap `lh_yield()`. Capacity is published after all declared
threads are ready. Mapping neither registers nor parks a thread.

An initial packet reserves an idle worker and sets a sticky notification.
SGI 8 only wakes its pinned kernel dispatcher. Under the worker's reader mutex,
the dispatcher reconciles notification and ownership before consuming one
`PreemptInfo` snapshot. Duplicate interrupts cannot authorize duplicate reads.
The dispatcher sleeps while an application owns the worker; unassigned CPUs
use Linux idle/WFI.

For an ordinary handoff, idle RX returns payload-free PREEMPT. Runtime calls
`lh_yield()` before further RX/TX. Kernel fences the outgoing task inside the
syscall, checks that relinquishment is requested, and reads `PreemptInfo`.
Acceptance acknowledges relinquishment; autonomous NIC cleanup precedes the
response. No scheduling IRQ is generated. Shared AXI-Lite accesses can wait
behind this read, so cleanup must not require another control access.

Both paths use one snapshot/claim/affinity helper. Claims use a short spinlock;
no claim lock crosses MMIO, affinity installation or sleep. A thread waits for
its own fresh activation, regardless of unrelated wakeups. The returned worker
index selects its mapped alias, and both local parity bits are replaced before
transport access. Ordinary blocking syscalls retain assignment.

## Failure boundary

Unauthorized fresh reads kill the requesting domain, not the installed owner
of the addressed worker. SGI 9 reports sticky domain faults to housekeeping.
Old-address writebacks and LCIA remain cleanup obligations. Unsupported exit,
fatal activation failure and failed result copying after relinquishment fence
claims and require a coherence reset; unresolved identities and resources are
retained. Full recovery and parked-domain deadline activation are deferred.
See [failure policy](process-recovery.md).

Sources: [scheduler](../../hw/src/lauberhorn/Scheduler.scala),
[ECI handoff](../../hw/src/lauberhorn/host/eci/EciPreemptionControlPlugin.scala),
[kernel activation](../../sw/kmod/sched.c), [IRQs and lifetimes](../../sw/kmod/worker.c),
[registration and mapping](../../sw/kmod/chrdev.c).
