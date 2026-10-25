/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Included by chrdev.c under control_lock, including snapshot/staging accesses. */
#if !LH_BACKEND_VM
#include <linux/math64.h>
#include <linux/ktime.h>

static long deadline_control(struct file_priv *fp, unsigned int op, void __user *arg)
{
    lauberhorn_eci_sched_t *dev = &fp->dev->sched_dev;
    struct proc_def *proc = fp->pd;
    struct lauberhorn_deadline_set cmd;
    struct lauberhorn_deadline_state state = {0};
    u64 minimum = lauberhorn_eci_sched_deadline_minimum_lead_rd(dev);
    u64 minimum_ns = DIV_ROUND_UP_ULL(minimum * NSEC_PER_SEC, LAUBERHORN_CLOCK_FREQ);
    u64 now_ns, now_ticks, delta, ticks, next = 0;
    unsigned int result;

    if (op == LAUBERHORN_IOCTL_GET_DEADLINE) {
        lauberhorn_eci_sched_deadline_snapshot_wr(dev, proc->idx);
        state.generation = lauberhorn_eci_sched_deadline_state_generation_rd(dev);
        state.next_deadline_ns = lauberhorn_eci_sched_deadline_state_next_rd(dev) ? proc->deadline_ns : 0;
        state.minimum_lead_ns = minimum_ns;
        state.pending = lauberhorn_eci_sched_deadline_state_pending_rd(dev);
        state.delivered = lauberhorn_eci_sched_deadline_state_claimed_rd(dev);
        return copy_to_user(arg, &state, sizeof(state)) ? -EFAULT : 0;
    }
    if (copy_from_user(&cmd, arg, sizeof(cmd))) return -EFAULT;
    /* Match compare-before-time validation in the VM. No failed command changes
     * hardware or the host clock-domain snapshot. */
    lauberhorn_eci_sched_deadline_snapshot_wr(dev, proc->idx);
    if (cmd.expected_generation != lauberhorn_eci_sched_deadline_state_generation_rd(dev))
        return -ESTALE;
    if (cmd.expected_generation == U64_MAX) return -EOVERFLOW;
    if (cmd.next_deadline_ns) {
        now_ns = ktime_get_ns();
        now_ticks = lauberhorn_eci_sched_deadline_now_rd(dev);
        if (cmd.next_deadline_ns < now_ns || cmd.next_deadline_ns - now_ns < minimum_ns)
            return -ETIME;
        delta = cmd.next_deadline_ns - now_ns;
        ticks = mul_u64_u32_div(delta, LAUBERHORN_CLOCK_FREQ, NSEC_PER_SEC) + 1;
        /* Modular comparisons require a lead below half the device clock range.
         * Tick zero is reserved for cancellation. */
        if (ticks >= BIT_ULL(63)) return -ERANGE;
        next = now_ticks + ticks;
        if (!next) return -ERANGE;
    }
    lauberhorn_eci_sched_deadline_next_wr(dev, next);
    lauberhorn_eci_sched_deadline_expected_wr(dev, cmd.expected_generation);
    lauberhorn_eci_sched_deadline_lifetime_wr(dev, proc->domain_code);
    lauberhorn_eci_sched_deadline_command_wr(dev, proc->idx);
    result = lauberhorn_eci_sched_deadline_result_rd(dev);
    switch (result) {
    case 0: break;
    case 1: return -ESHUTDOWN;
    case 2: return -ESTALE;
    case 3: return -ETIME;
    case 4: return -EOVERFLOW;
    default: return -EIO;
    }
    cmd.generation = lauberhorn_eci_sched_deadline_generation_rd(dev);
    proc->deadline_ns = cmd.next_deadline_ns;
    return copy_to_user(arg, &cmd, sizeof(cmd)) ? -EFAULT : 0;
}
#endif
