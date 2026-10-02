// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
#include "common.h"
#include "trace.h"
#include <linux/sched/signal.h>

DEFINE_SPINLOCK(claim_lock);

int register_worker_thread(struct proc_def *proc, u32 index)
{
    struct thr_def *thr;
    unsigned long flags;
    int i;
    if (!proc->declared_workers || index >= proc->declared_workers) return -EINVAL;
    spin_lock_irqsave(&claim_lock, flags);
    if (proc->failed) { spin_unlock_irqrestore(&claim_lock, flags); return -ESHUTDOWN; }
    for (i = 0; i < proc->declared_workers; ++i)
        if (proc->thr_defs[i].task == current) {
            spin_unlock_irqrestore(&claim_lock, flags);
            return -EALREADY;
        }
    thr = &proc->thr_defs[index];
    if (thr->enabled) { spin_unlock_irqrestore(&claim_lock, flags); return -EALREADY; }
    init_waitqueue_head(&thr->wait);
    thr->idx = index;
    thr->parent = proc;
    thr->worker_idx = -1;
    get_task_struct(current);
    thr->task = current;
    thr->enabled = true;
    ++proc->num_rdy_thrs;
    spin_unlock_irqrestore(&claim_lock, flags);
    sched_set_fifo_low(current);
    return 0;
}

static bool waiting_thread(struct proc_def *proc)
{
    int i;
    if (READ_ONCE(proc->failed)) return true;
    for (i = 0; i < proc->declared_workers; ++i)
        if (READ_ONCE(proc->thr_defs[i].waiting)) return true;
    return false;
}

/* Called with only this worker's reader mutex held. No claim/lifetime lock
 * crosses blocking MMIO, affinity installation or thread wait. */
static int activate(struct worker_fpi_data *worker, struct thr_def *outgoing)
{
    u64 info = lauberhorn_eci_preempt_preempt_info_rawrd(&worker->preempt_dev);
    u32 code = lauberhorn_eci_preempt_preempt_info_domain_code_extract(info);
    u32 slot = lauberhorn_eci_preempt_preempt_info_domain_slot_extract(info);
    struct proc_def *proc = find_proc_slot(slot);
    struct thr_def *next = NULL;
    unsigned long flags;
    int i, ret;
    if (!proc || proc->domain_code != code) {
        pr_err("Invalid committed activation slot=%u code=%u\n", slot, code);
        // A malformed committed snapshot cannot identify a safe successor.
        // Fence every retained domain; none may return to an old assignment.
        for (i = 1; i <= LAUBERHORN_NUM_PROCS; ++i)
            fail_domain(find_proc_slot(i));
        return -EPROTO;
    }
    if (outgoing) {
        // The accepted handoff has finished transport cleanup. Publish the
        // outgoing thread before waiting for another domain's thread: otherwise
        // simultaneous A->B and B->A handoffs could wait on one another.
        spin_lock_irqsave(&claim_lock, flags);
        outgoing->worker_idx = -1;
        outgoing->waiting = !outgoing->parent->failed;
        worker->thr = NULL;
        spin_unlock_irqrestore(&claim_lock, flags);
        wake_up_all(&outgoing->parent->claims);
    }
    for (;;) {
        spin_lock_irqsave(&claim_lock, flags);
        if (proc->failed) { spin_unlock_irqrestore(&claim_lock, flags); return -ESHUTDOWN; }
        for (i = 0; i < proc->declared_workers; ++i) {
            struct thr_def *thr = &proc->thr_defs[i];
            if (thr->enabled && thr->waiting && thr->worker_idx == -1) {
                next = thr;
                next->waiting = false;
                next->worker_idx = worker->index;
                break;
            }
        }
        spin_unlock_irqrestore(&claim_lock, flags);
        if (next) break;
        ret = wait_event_killable(proc->claims, waiting_thread(proc));
        if (ret) { fail_domain(proc); return ret; }
    }
    ret = set_cpus_allowed_ptr(next->task, cpumask_of(worker->cpu));
    if (ret) { fail_domain(proc); return ret; }
    spin_lock_irqsave(&claim_lock, flags);
    if (proc->failed) { spin_unlock_irqrestore(&claim_lock, flags); return -ESHUTDOWN; }
    next->activation = (struct lh_activation) {
        .worker_index = worker->index,
        .rx_next = lauberhorn_eci_preempt_preempt_info_rx_next_extract(info),
        .tx_next = lauberhorn_eci_preempt_preempt_info_tx_next_extract(info),
    };
    worker->thr = next;
    next->activation_ready = true;
    spin_unlock_irqrestore(&claim_lock, flags);
    trace_worker_switch(worker->cpu, outgoing ? outgoing->parent->lifetime : 0,
        outgoing ? outgoing->parent->tgid : 0, outgoing ? outgoing->task->pid : 0,
        proc->lifetime, proc->tgid,
        next->task->pid, 2, false);
    wake_up(&next->wait);
    return 0;
}

static int dispatch_thread(void *arg)
{
    struct worker_fpi_data *worker = arg;
    sched_set_fifo(current);
    while (!kthread_should_stop()) {
        wait_event_interruptible(worker->dispatch_wait,
            kthread_should_stop() || atomic_read(&worker->kick));
        if (kthread_should_stop()) break;
        mutex_lock(&worker->reader);
        atomic_set(&worker->kick, 0);
        if (!READ_ONCE(worker->thr) &&
            lauberhorn_eci_preempt_idle_notification_rd(&worker->preempt_dev))
            activate(worker, NULL);
        mutex_unlock(&worker->reader);
    }
    return 0;
}

int start_worker_dispatch(struct worker_fpi_data *worker)
{
    mutex_init(&worker->reader);
    init_waitqueue_head(&worker->dispatch_wait);
    atomic_set(&worker->kick, 0);
    worker->dispatcher = kthread_create(dispatch_thread, worker, "lh_dispatch/%d", worker->index);
    if (IS_ERR(worker->dispatcher)) return PTR_ERR(worker->dispatcher);
    kthread_bind(worker->dispatcher, worker->cpu);
    wake_up_process(worker->dispatcher);
    return 0;
}
void stop_worker_dispatch(struct worker_fpi_data *worker)
{
    if (worker->dispatcher && !IS_ERR(worker->dispatcher)) kthread_stop(worker->dispatcher);
}

long worker_yield(struct proc_def *proc, struct lh_activation __user *result)
{
    struct thr_def *thr = NULL;
    struct worker_fpi_data *worker;
    struct lh_activation activation;
    unsigned long flags;
    int i, index, ret;
    if (!access_ok(result, sizeof(*result))) return -EFAULT;
    for (i = 0; i < proc->declared_workers; ++i)
        if (proc->thr_defs[i].enabled && proc->thr_defs[i].task == current) thr = &proc->thr_defs[i];
    if (!thr) return -EPERM;
    spin_lock_irqsave(&claim_lock, flags);
    if (proc->failed) { spin_unlock_irqrestore(&claim_lock, flags); return -ESHUTDOWN; }
    index = thr->worker_idx;
    // No unrelated wake can deliver the previous activation again.
    thr->activation_ready = false;
    spin_unlock_irqrestore(&claim_lock, flags);
    if (index >= 0) {
        worker = worker_by_index(index);
        mutex_lock(&worker->reader);
        if (worker->thr != thr ||
            !lauberhorn_eci_preempt_yield_requested_rd(&worker->preempt_dev)) {
            mutex_unlock(&worker->reader);
            fail_domain(proc);
            return -EPROTO;
        }
        // The calling task is fenced inside this syscall before read acceptance.
        ret = activate(worker, thr);
        mutex_unlock(&worker->reader);
        if (ret) { fail_domain(proc); return ret; }
    }
    if (index < 0) {
        spin_lock_irqsave(&claim_lock, flags);
        thr->waiting = !proc->failed;
        spin_unlock_irqrestore(&claim_lock, flags);
        wake_up_all(&proc->claims);
    }
    ret = wait_event_killable(thr->wait,
        READ_ONCE(proc->failed) || READ_ONCE(thr->activation_ready));
    if (ret || READ_ONCE(proc->failed)) { fail_domain(proc); return -ESHUTDOWN; }
    spin_lock_irqsave(&claim_lock, flags);
    activation = thr->activation;
    spin_unlock_irqrestore(&claim_lock, flags);
    if (copy_to_user(result, &activation, sizeof(activation))) {
        fail_domain(proc);
        return -EFAULT;
    }
    return 0;
}
