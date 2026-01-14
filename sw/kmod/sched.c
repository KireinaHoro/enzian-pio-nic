#include "common.h"

// defined in worker.c
extern int worker_lo, worker_hi;

int prepare_worker_thread(struct thr_def *thr)
{
	pr_info("Registering thread %d from Lauberhorn\n", thr->task->pid);

	// Change scheduler class to SCHED_FIFO with priority 1
	sched_set_fifo_low(thr->task);
	thr->enabled = true;

	// Wait for HW to wake us up
	desched_worker_thread(thr);

	return 0;
}

/**
 * Deregister an application thread from Lauberhorn and restore it to be
 * a normal Linux user thread.
 *
 * This will only be called from process context (munmap, rmmod) so we
 * can safely set affinity and wake up processes.
 */
void clean_worker_thread(struct thr_def *thr)
{
	if (!thr->enabled) {
		// Thread woke up via ioctl and cleaned up already, skipping
		pr_debug("Thread %d already deregistered\n", thr->task->pid);
		return;
	}

	pr_info("Deregistering thread %d from Lauberhorn\n", thr->task->pid);

	thr->enabled = false;

	// Reset scheduler class to SCHED_NORMAL
	sched_set_normal(thr->task, 0);

	// Reset affinity to all cores
	set_cpus_allowed_ptr(thr->task, housekeeping_cpumask(HK_TYPE_DOMAIN));

	// Wake up task again for it to finish clean-up
	wake_up_process(thr->task);
}

struct sched_thread_work {
	struct work_struct work;
	struct thr_def *thr;
	struct worker_fpi_data *fpi_priv;
};
static DEFINE_PER_CPU(struct sched_thread_work, sched_work_percpu);

/**
 * The deferred function that does the actual job of scheduling the new
 * thread, but in process context.
 */
static void _sched_worker_thread(struct work_struct *ws)
{
	struct sched_thread_work *w =
		container_of(ws, struct sched_thread_work, work);
	struct thr_def *thr = w->thr;

	u8 *parity_page = w->thr->parent->parity_page;
	u8 rx_parity = parity_page[thr->idx * 2];
	u8 tx_parity = parity_page[thr->idx * 2 + 1];

	int me = smp_processor_id();

	// Update affinity to this core only
	set_cpus_allowed_ptr(thr->task, cpumask_of(smp_processor_id()));

	// Route CL
	thr->worker_idx = me - worker_lo;
	route_prefix_to_core(thr->prefix, thr->worker_idx + 1);

	// Program parity bits into HW
	lauberhorn_eci_worker_ctrl_rx_curr_cl_idx_wr(&w->fpi_priv->worker_dev,
						     rx_parity);
	lauberhorn_eci_worker_ctrl_tx_curr_cl_idx_wr(&w->fpi_priv->worker_dev,
						     tx_parity);

	// Lock preemption control CL in L2 -- will be unlocked when the FPGA
	// tries to preempt this thread
	cl_fetch_and_lock(thr->dp_phys_base +
			  LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET);

	// Wake up the task
	wake_up_process(thr->task);

	// Ack interrupt
	lauberhorn_eci_preempt_irq_en_wr(&w->fpi_priv->preempt_dev, 1);
}

/**
 * Schedule the given thread on the current CPU core.
 *
 * This is called inside interrupt context, but we cannot safely change
 * the CPU mask with set_cpus_allowed_ptr, since thread migration might
 * happen and it will try to acquire locks.  Instead we launch a deferred
 * task to change affinity and migrate the thread, as well as wake it up.
 */
void sched_worker_thread(struct thr_def *thr, struct worker_fpi_data *fpi_priv)
{
	struct sched_thread_work *w = this_cpu_ptr(&sched_work_percpu);
	int me = smp_processor_id();
	int err;

	printk("Scheduling deferred work to dispatch thread %d on CPU %d",
	       thr->task->pid, me);

	BUG_ON(!thr->enabled);

	w->thr = thr;
	w->fpi_priv = fpi_priv;
	INIT_WORK(&w->work, _sched_worker_thread);

	err = schedule_work_on(me, &w->work);
	BUG_ON(err);
}

/**
 * Deschedule the current (running) thread.  Two cases possible:
 * - we are running in process context (called by mmap handler for thread
 *   to enter Lauberhorn control)
 * - we are running in interrupt context (called by FPI handler)
 *
 * In any case, we mark the current task as not runnable and set the
 * "resched needed" flag.  We also unroute the datapath CLs to make
 * sure no spurious wake-ups can allow the task to read data from
 * another application (that is now running on the core).
 *
 * We need to use TASK_UNINTERRUPTIBLE -- a signal will circumvent
 * the interruptible sleep and eventually lead to the thread continuing
 * and thus hitting a SIGBUS, since the datapath CLs are still unrouted.
 */
void desched_worker_thread(struct thr_def *thr)
{
	BUG_ON(!thr->enabled);

	// We can only safely deschedule the current thread
	BUG_ON(thr->task != current);

	// Set to TASK_UNINTERRUPTIBLE and wait for ISR to wake us up
	set_current_state(TASK_UNINTERRUPTIBLE);

	// Set the "need resched" flag -- rescheduling happens when we
	// return from either the interrupt handler or syscall
	set_tsk_need_resched(current);

	// Unroute CL: find entry and disable
	if (thr->worker_idx >= 0) {
		unroute_prefix_on_core(thr->worker_idx + 1);
		thr->worker_idx = -1;
	}
}
