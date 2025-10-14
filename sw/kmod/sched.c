#include "common.h"

// defined in worker.c
extern int worker_lo, worker_hi;

int prepare_worker_thread(struct thr_def *thr)
{
	// Change scheduler class to SCHED_FIFO with priority 1
	sched_set_fifo_low(thr->task);

	// Wait for HW to wake us up
	desched_worker_thread(thr);

	thr->enabled = true;
	return 0;
}

void clean_worker_thread(struct thr_def *thr)
{
	thr->enabled = false;

	// Reset scheduler class to SCHED_NORMAL
	sched_set_normal(thr->task, 0);

	// Reset affinity to all cores

	// Set to TASK_RUNNABLE again for it to finish clean-up
}

void sched_worker_thread(struct thr_def *thr, u32 cpu)
{
	BUG_ON(!thr->enabled);

	// Update affinity to this core only

	// Set to TASK_RUNNABLE

	// Route CL
	thr->worker_idx = cpu - worker_lo;
	route_prefix_to_core(thr->prefix, thr->worker_idx + 1);

	// Lock preemption control CL in L2
	// Preemption
}

void desched_worker_thread(struct thr_def *thr)
{
	BUG_ON(!thr->enabled);

	// Set to TASK_UNINTERRUPTIBLE and wait for ISR to wake us up

	// Unroute CL: find entry and disable
	unroute_prefix_on_core(thr->worker_idx + 1);
	thr->worker_idx = -1;
}