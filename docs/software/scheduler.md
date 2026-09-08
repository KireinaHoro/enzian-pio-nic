# Scheduler and Linux integration

Scope: source inspection, 2026-09-08. Implemented mechanisms are not evidence of measured isolation, fairness, or working fault recovery.

## Confirmed execution policy

Maintainer decision (2026-09-08): RPC tasks run to finish on isolated worker cores; these cores are not shared with ordinary Linux tasks. Cooperative nested-call waits may yield a fiber. A timer should kill a task that cannot clear the 2F2F receive critical section, or an application timeout should apply; timer scope and recovery semantics remain open. Current kernel `BUG_ON(killed)` does not implement graceful task-kill recovery.

## Division of responsibility

- FPGA [`Scheduler.scala`](../../hw/src/lauberhorn/Scheduler.scala) queues requests per process, tracks core assignments and maximum process parallelism, and requests process switches. Linux selects an available registered thread for that process and performs affinity/wakeup operations.
- A hardware PID represents the application TGID, not a fiber or Linux worker TID. Process index zero is reserved for IDLE; physical worker slot zero is the kernel bypass path. These are different namespaces.
- [`chrdev.c`](../../sw/kmod/chrdev.c) registers processes through `/dev/lauberhorn` open, services through ioctls, and threads through datapath `mmap`. Each thread gets a persistent address prefix; routing maps its cachelines to whichever FPGA worker slot currently serves it. The mapped-thread count programs `maxThreads`.
- [`worker.c`](../../sw/kmod/worker.c) reserves the highest numbered `LAUBERHORN_NUM_WORKER_CORES` online CPUs. It checks housekeeping configuration and isolation, configures SGI 8, and promotes their `ksoftirqd` tasks to FIFO priority above RPC workers. See the build/deployment configuration before choosing CPU numbers.

## Dispatch protocol

1. `prepare_worker_thread` changes a mapped worker to `SCHED_FIFO` priority 1 and parks it in `TASK_IDLE`. Its `mmap` can therefore remain blocked until hardware schedules it.
2. FPGA preemption control clears READY, coordinates BUSY and RX/TX cacheline retirement, then sends SGI 8. Bootstrap from IDLE skips fetching an unmapped old thread's control line.
3. `worker_fpi_handler` masks the interrupt, reads the next PID, chooses the first enabled thread with `worker_idx == -1`, parks/unroutes the old worker, and schedules deferred work on that CPU.
4. `_sched_worker_thread` pins the new thread, routes its prefix, restores RX/TX parity from its shared parity page, fetches/locks the preemption cacheline in L2, wakes the thread, and reenables the FPGA interrupt. Reenabling is the hardware completion handshake.
5. Teardown restores `SCHED_NORMAL`, housekeeping affinity, and wakefulness. A parked worker is deliberately not signal-wakeable; runtime SIGINT uses a cleanup ioctl.

Sources: [`sched.c`](../../sw/kmod/sched.c), [`worker.c`](../../sw/kmod/worker.c), [`EciPreemptionControlPlugin.scala`](../../hw/src/lauberhorn/host/eci/EciPreemptionControlPlugin.scala), [`cs.h`](../../sw/rt/include/cs.h).

## Actual policy and limits

- On arrival, a process without a core can claim an idle core; a queue at least half full can also claim a ready core, subject to `maxThreads`. Ready means able to receive a request, not arbitrary involuntary interruption of a running handler. The pop path also searches pending processes when a worker has no work.
- Selection uses first-set choices; fairness is explicitly TODO. Do not describe this as a completed fair-share or latency-SLO scheduler.
- `force` victim selection is TODO. The preemption-control FSM has a critical-section timeout/`killed` path, but the Linux handler executes `BUG_ON(killed)`. Safe termination of a misbehaving process is unfinished.
- No available target thread causes a kernel panic. Thread selection and shared registration state deserve concurrency review before claiming robust multi-process operation.
- Source-review concerns, not reproduced failures: `register_app` uses inclusive array indexing against `proc_defs[LAUBERHORN_NUM_PROCS]`; open records `current->pid` while later operations find `current->tgid`. The supported lifecycle assumes initialization on the process leader.

## Where to change things

| Change | Read first |
|---|---|
| Core allocation, queue fairness, readiness | `hw/src/lauberhorn/Scheduler.scala` |
| Coherent preemption handshake | `hw/src/lauberhorn/host/eci/EciPreemptionControlPlugin.scala`, `sw/rt/include/cs.h` |
| Linux migration, parking, wakeup | `sw/kmod/sched.c`, `sw/kmod/worker.c` |
| Process/thread ownership and lifetime | `sw/kmod/chrdev.c`, `sw/kmod/common.h` |

Open design decisions: allocation fairness/SLOs within the RPC worker pool; receive-critical-section versus whole-application timeout and safe cleanup/recovery. Run-to-finish and isolation from ordinary Linux work are settled intent, not open alternatives.
