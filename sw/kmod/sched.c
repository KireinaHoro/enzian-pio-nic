#include "common.h"
#include "trace.h"
#include <linux/iopoll.h>
#include <asm/irq_regs.h>
#include <asm/ptrace.h>

// defined in worker.c
extern int worker_lo, worker_hi;

/* IRQs may interrupt an ioctl or housekeeping task on the worker CPU.
 * Never sleep a service in the middle of kernel code: task work runs before
 * its next return to userspace (and before exit tears down its mappings). */
static void park_at_user_return(struct callback_head *cb)
{
	struct thr_def *thr = container_of(cb, struct thr_def, park_work);
	atomic_set(&thr->park_queued, 0);
	set_current_state(TASK_IDLE);
	if (READ_ONCE(thr->enabled) && READ_ONCE(thr->park_requested))
		schedule();
	__set_current_state(TASK_RUNNING);
}

int prepare_worker_thread(struct thr_def *thr)
{
	pr_info("Registering thread %d from Lauberhorn\n", thr->task->pid);

	// Change scheduler class to SCHED_FIFO with priority 1
	sched_set_fifo_low(thr->task);
	thr->enabled = true;
	thr->park_requested = false;
	atomic_set(&thr->park_queued, 0);
	init_task_work(&thr->park_work, park_at_user_return);

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
	WRITE_ONCE(thr->park_requested, false);

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
	struct thr_def *previous;
	struct worker_fpi_data *fpi_priv;
};
static DEFINE_PER_CPU(struct sched_thread_work, sched_work_percpu);

static void trace_switch(struct sched_thread_work *w, int phase)
{
	struct thr_def *old = w->previous, *next = w->thr;
	trace_worker_switch(smp_processor_id(), old ? old->parent->lifetime : 0,
		old ? old->parent->tgid : 0, old ? old->task->pid : 0,
		next ? next->parent->lifetime : 0, next ? next->parent->tgid : 0,
		next ? next->task->pid : 0, phase, w->fpi_priv->killed);
}

/**
 * The deferred function that does the actual job of scheduling the new
 * thread, but in process context.
 */
static void _sched_worker_thread(struct work_struct *ws)
{
	struct sched_thread_work *w =
		container_of(ws, struct sched_thread_work, work);
	struct thr_def *thr = w->thr;
	int affinity_ret;
	int wake_ret;
	unsigned long drained;
	int ret;

	/* Keep the old prefix routed until its RX/TX invalidations and responses
	 * have completed. IRQ acknowledgement only establishes CPU quiescence. */
	ret = read_poll_timeout(lauberhorn_eci_preempt_datapath_drained_rd,
		drained, drained, 10, 100000, false, &w->fpi_priv->preempt_dev);
	if (ret) {
		pr_err("ECI datapath failed to drain on CPU %d: %d\n", smp_processor_id(), ret);
		return;
	}
	trace_switch(w, 1);
	if (w->previous) {
		unroute_prefix_on_core(w->previous->worker_idx + 1);
		w->previous->worker_idx = -1;
	}

	w->fpi_priv->thr = NULL;
	if (!thr) {
		trace_switch(w, 2);
		lauberhorn_eci_preempt_irq_en_wr(&w->fpi_priv->preempt_dev, 1);
		return;
	}

	u8 *parity_page = w->thr->parent->parity_page;
	u8 rx_parity = parity_page[thr->idx * 2];
	u8 tx_parity = parity_page[thr->idx * 2 + 1];

	int me = smp_processor_id();

	// Update affinity to this core only
	affinity_ret = set_cpus_allowed_ptr(thr->task,
					     cpumask_of(smp_processor_id()));
	pr_info("Worker CPU %d dispatching thread %d prefix %#x: affinity_ret=%d\n",
		me, thr->task->pid, thr->prefix, affinity_ret);

	// Allow next FPI handler to deschedule this thread
	w->fpi_priv->thr = thr;

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

	// A pending return-to-user callback must not park a newly dispatched thread.
	WRITE_ONCE(thr->park_requested, false);
	// Wake up the task
	wake_ret = wake_up_process(thr->task);
	pr_info("Worker CPU %d woke thread %d for prefix %#x: wake_ret=%d state=0x%x\n",
		me, thr->task->pid, thr->prefix, wake_ret,
		READ_ONCE(thr->task->__state));

	trace_switch(w, 2);
	// Ack interrupt
	lauberhorn_eci_preempt_irq_en_wr(&w->fpi_priv->preempt_dev, 1);
	pr_info("Worker CPU %d re-enabled preempt IRQ for thread %d\n", me,
		thr->task->pid);
}

void init_worker_schedule(int cpu)
{
	INIT_WORK(&per_cpu(sched_work_percpu, cpu).work, _sched_worker_thread);
}

void flush_worker_schedules(void)
{
	int cpu;
	for (cpu = worker_lo; cpu < worker_hi; ++cpu)
		flush_work(&per_cpu(sched_work_percpu, cpu).work);
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

	pr_info("Scheduling deferred work to dispatch thread %d on CPU %d\n",
		thr ? thr->task->pid : 0, me);

	if (thr) {
		BUG_ON(!thr->enabled);
		BUG_ON(thr->worker_idx != me - worker_lo);
	}

	w->thr = thr;
	w->previous = fpi_priv->thr;
	w->fpi_priv = fpi_priv;

	err = schedule_work_on(me, &w->work);
	BUG_ON(!err);
}

/**
 * Deschedule the current (running) thread.  Two cases possible:
 * - we are running in process context (called by mmap handler for thread
 *   to enter Lauberhorn control)
 * - we are running in interrupt context (called by FPI handler)
 *
 * In any case, we mark the current task as not runnable and set the
 * "resched needed" flag. Deferred dispatch work retains the old mapping until
 * the hardware confirms drain, then unroutes it before assigning the next task.
 *
 * Use TASK_IDLE, which is TASK_UNINTERRUPTIBLE | TASK_NOLOAD.  Signals
 * must not wake a parked worker because the datapath CLs are unrouted,
 * but these sleeps are intentional and should not be reported by the
 * kernel hung-task detector as stuck D-state tasks.
 */
void desched_worker_thread(struct thr_def *thr)
{
	BUG_ON(!thr->enabled);

	WRITE_ONCE(thr->park_requested, true);
	if (!in_interrupt() || (thr->task == current && get_irq_regs() && user_mode(get_irq_regs()))) {
		BUG_ON(thr->task != current);
		set_current_state(TASK_IDLE);
		set_tsk_need_resched(current);
	} else if (atomic_cmpxchg(&thr->park_queued, 0, 1) == 0) {
		int ret = task_work_add(thr->task, &thr->park_work, TWA_RESUME);
		if (ret) {
			/* An exiting task can no longer return to userspace. */
			atomic_set(&thr->park_queued, 0);
			WARN_ON_ONCE(ret != -ESRCH);
		}
	}
	// Retain worker_idx and routing until deferred dispatch observes HW drain.
}
