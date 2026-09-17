# Scheduler and Linux integration

Scope: source inspection, 2026-09-08. Implemented mechanisms are not evidence of measured isolation, fairness, or working fault recovery.

## Confirmed execution policy

Maintainer decision (2026-09-08): RPC tasks run to finish on isolated worker cores; these cores are not shared with ordinary Linux tasks. Cooperative nested-call waits may yield a fiber. An independent preemption critical-section timeout and an optional execution watchdog terminate the whole application through the [B4 recovery path](process-recovery.md). Execution monitoring defaults to disabled; hardware acceptance remains pending.

## Division of responsibility

- FPGA [`Scheduler.scala`](../../hw/src/lauberhorn/Scheduler.scala) queues requests per process, tracks core assignments and maximum process parallelism, and requests process switches. Linux selects an available registered thread for that process and performs affinity/wakeup operations.
- A hardware PID represents the application TGID, not a fiber or Linux worker TID. Process index zero is reserved for IDLE; physical worker slot zero is the kernel bypass path. These are different namespaces.
- [`chrdev.c`](../../sw/kmod/chrdev.c) registers processes through `/dev/lauberhorn` open, services through ioctls, and threads through datapath `mmap`. Each thread gets a persistent address prefix; routing maps its cachelines to whichever FPGA worker slot currently serves it. The mapped-thread count programs `maxThreads`.
- [`worker.c`](../../sw/kmod/worker.c) reserves the highest numbered `LAUBERHORN_NUM_WORKER_CORES` online CPUs. It checks housekeeping configuration and isolation, configures SGI 8, and promotes their `ksoftirqd` tasks to FIFO priority above RPC workers. See the build/deployment configuration before choosing CPU numbers.

## Dispatch protocol

1. `prepare_worker_thread` changes a mapped worker to `SCHED_FIFO` priority 1 and parks it in `TASK_IDLE`. Its `mmap` can therefore remain blocked until hardware schedules it.
2. FPGA preemption control clears READY, coordinates BUSY and RX/TX cacheline retirement, then sends SGI 8. Bootstrap from IDLE skips fetching an unmapped old thread's control line.
3. `worker_fpi_handler` masks the interrupt, reads the next PID, atomically claims an enabled thread with `worker_idx == -1`, parks the old worker while retaining its mapping, and schedules deferred work on that CPU.
4. `_sched_worker_thread` waits for hardware drain, unroutes the old thread, pins the new thread, routes its prefix, restores RX/TX parity from its shared parity page, fetches/locks the preemption cacheline in L2, wakes the thread, and reenables the FPGA interrupt. Reenabling is the hardware completion handshake.
5. Whole-process teardown first drains hardware, then restores `SCHED_NORMAL`, housekeeping affinity and wakefulness. A kernel monitor detects fatal signals on parked workers. IRQs interrupting kernel code park the old thread through return-to-user task work, after its syscall releases locks.

Sources: [`sched.c`](../../sw/kmod/sched.c), [`worker.c`](../../sw/kmod/worker.c), [`EciPreemptionControlPlugin.scala`](../../hw/src/lauberhorn/host/eci/EciPreemptionControlPlugin.scala), [`cs.h`](../../sw/rt/cs.h).

September 17 B4 implementation update: deferred dispatch now waits for the
`datapathDrained` acknowledgement before unmapping the old thread prefix. RX
retires its retained packet and invalidates its cachelines; TX finishes traffic
or flushes an uncommitted buffer without transmitting it. The acknowledgement
also waits for router quiescence and queued unlock traffic. Thread selection
claims `worker_idx` atomically before deferred dispatch. The generated IPI killed
field now follows `PID_WIDTH` rather than a hard-coded 32-bit PID, and runtime
critical-section entry reloads READY while waiting. Whole-process termination is now implemented in source;
board acceptance remains open; see [the B4 design](process-recovery.md).

## Actual policy and limits

- On arrival, a process without a core can claim an idle core; a queue at least half full can also claim a ready core, subject to `maxThreads`. Ready means able to receive a request, not arbitrary involuntary interruption of a running handler. The pop path also searches pending processes when a worker has no work.
- Selection uses first-set choices; fairness is explicitly TODO. Do not describe this as a completed fair-share or latency-SLO scheduler.
- Process disable and watchdog expiry force every assigned core through stop-to-idle without requiring RX readiness. The kernel handles `killed` by requesting whole-process recovery. Ordinary preemption, explicit stop and critical-section timeouts remain enabled when execution monitoring is off.
- No available target thread causes a kernel panic. Thread selection and shared registration state deserve concurrency review before claiming robust multi-process operation.
- Process storage includes the reserved IDLE slot and registration uses TGID. Reuse waits for final file/VMA release and verified hardware retirement.

## Where to change things

| Change | Read first |
|---|---|
| Core allocation, queue fairness, readiness | `hw/src/lauberhorn/Scheduler.scala` |
| Coherent preemption handshake | `hw/src/lauberhorn/host/eci/EciPreemptionControlPlugin.scala`, `sw/rt/cs.h` |
| Linux migration, parking, wakeup | `sw/kmod/sched.c`, `sw/kmod/worker.c` |
| Process/thread ownership and lifetime | `sw/kmod/chrdev.c`, `sw/kmod/common.h` |

Open work: allocation fairness/SLOs and matched-hardware recovery acceptance. Run-to-finish and isolation from ordinary Linux work are settled intent, not open alternatives.
