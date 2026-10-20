# Scheduler and Linux integration

ECI uses physical-worker address decoding with cooperative DESCHED/yield.
The [control contract](scheduling-control-interface.md) defines the detailed
handoff protocol; this page describes Linux responsibilities and the failure
boundary. [ECI evaluation](../research/eci-progress.md) records finite board
coverage separately from implementation.

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
`DeschedInfo` snapshot. Duplicate interrupts cannot authorize duplicate reads.
The dispatcher sleeps while an application owns the worker; unassigned CPUs
use Linux idle/WFI.

For an ordinary handoff, idle RX returns payload-free DESCHED. Runtime calls
`lh_yield()` before further RX/TX. Kernel fences the outgoing task inside the
syscall, checks that relinquishment is requested, and reads `DeschedInfo`.
Acceptance acknowledges relinquishment; autonomous NIC cleanup precedes the
response. No scheduling IRQ is generated. Shared AXI-Lite accesses can wait
behind this read, so cleanup must not require another control access.

Both paths use one snapshot/claim/affinity helper. Claims use a short spinlock;
no claim lock crosses MMIO, affinity installation or sleep. A thread waits for
its own fresh activation, regardless of unrelated wakeups. The returned worker
index selects its mapped alias, and both local parity bits are replaced before
transport access. Ordinary blocking syscalls retain assignment.

## Failure and reset boundary

Complete kill/exit recovery is deferred in the cooperative ECI implementation.
This stage kills and fences failed domains; it does not reclaim unresolved
resources or recycle their identities. Historical recovery campaigns describe
older ABIs and do not establish acceptance of this implementation.

An unauthorized fresh read is attributed to the requesting PA lifetime. Kernel
fault handling kills that domain and forbids later activation. It must not kill
the current owner merely because an old domain addressed the same physical
worker. Legitimate old-address writebacks and LCIA remain serviceable.

Unsupported exit, individual pool withdrawal, fatal activation failure and a
failed activation-result copy after relinquishment have the same reset-required
boundary. Claims are fenced, task and resource references retained, and unresolved
slots, mappings and endpoint identities are not offered to another domain.
The persistent lifetime allocator resets only at full coherence reset. Application
reset or module reload cannot silently restart the PA namespace.

Ordinary scheduling does not use recovery machinery. Idle activation uses its
sticky notification and pinned dispatcher; ordinary DESCHED uses `lh_yield()`
and one autonomous-cleanup `DeschedInfo` transaction with no scheduling IRQ.

Per-call cancellation/timeout retirement remains a separate existing operation.
Parked-domain deadline activation and execution-watchdog integration are outside
this stage; their control ioctls return `EOPNOTSUPP`. No HOST_WORK interface is
implemented; the [new design](../proposals/host-work.md) uses that name instead
of HOST_WAKE and does not change recovery. See the [control contract](scheduling-control-interface.md) and
[evidence ledger](../hardware/eci-implementation.md).

`lauberhorn_stop()` requests failure fencing, not a successful drain/reclaim
operation. `STOP_PROCESS` and the legacy `WAKE_ALL_WORKERS` ioctl call
`fail_domain` and return `ESHUTDOWN`. `KICK_PROCESS` and `WATCHDOG` return
`EOPNOTSUPP`; even a zero watchdog limit is rejected by the current kernel.
Per-call retirement remains available through `RETIRE_CALL`.

Sources: [scheduler](../../hw/src/lauberhorn/Scheduler.scala),
[ECI handoff](../../hw/src/lauberhorn/host/eci/EciDeschedulingControlPlugin.scala),
[kernel activation](../../sw/kmod/sched.c), [IRQs and lifetimes](../../sw/kmod/worker.c),
[registration and mapping](../../sw/kmod/chrdev.c).
