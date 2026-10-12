// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

#include "common.h"
#include "trace.h"
#include <linux/sched/signal.h>
#include <linux/timekeeping.h>

#include "regblock_bases.h"
#include "worker_regs.h"

#include "lauberhorn_eci_desched_dev.h"
#include "lauberhorn_eci_ownership_dev.h"
#include "lauberhorn_eci_hostIf_dev.h"

// [lo, hi) bound of CPU cores used to handle RPC requests
int worker_lo, worker_hi;
/* Keep module per-CPU state in the exact .data..percpu section. Linux's
 * module loader does not allocate .data..percpu..read_mostly, emitted by
 * DEFINE_PER_CPU_READ_MOSTLY (still true upstream at a90ee4305c4a).
 * That leaves per_cpu_ptr() accessing unallocated storage: on Enzian a stray
 * thr pointer suppressed idle dispatch even though SGI8 arrived. Built-in
 * kernel linking handles that subsection, but loadable modules do not.
 * The Nix module check rejects orphan per-CPU subsections in the final ELF.
 */
static DEFINE_PER_CPU(struct worker_fpi_data, fpi_percpu_data);
static u64 irq_no;

static lauberhorn_eci_ownership_t ownership_dev;
/* Privileged experiment control: only changes NIC receive hold duration.
 * Architectural CPU timeout protection is never modified. */
static lauberhorn_eci_hostIf_t host_if_dev;
static DEFINE_MUTEX(rx_hold_lock);
static bool host_if_ready;
static unsigned long long rx_hold_cycles = 10000;
static int rx_hold_set(const char *value, const struct kernel_param *param)
{
    unsigned long long cycles;
    int ret = kstrtoull(value, 0, &cycles);
    if (ret) return ret;
    mutex_lock(&rx_hold_lock);
    rx_hold_cycles = cycles;
    if (host_if_ready)
        lauberhorn_eci_hostIf_rx_block_cycles_wr(&host_if_dev, cycles);
    mutex_unlock(&rx_hold_lock);
    return 0;
}
static int rx_hold_get(char *buffer, const struct kernel_param *param)
{
    unsigned long long cycles;
    mutex_lock(&rx_hold_lock);
    cycles = host_if_ready ? lauberhorn_eci_hostIf_rx_block_cycles_rd(&host_if_dev) : rx_hold_cycles;
    mutex_unlock(&rx_hold_lock);
    return scnprintf(buffer, PAGE_SIZE, "%llu\n", cycles);
}
static const struct kernel_param_ops rx_hold_ops = {.set = rx_hold_set, .get = rx_hold_get};
module_param_cb(rx_hold_cycles, &rx_hold_ops, &rx_hold_cycles, 0600);
MODULE_PARM_DESC(rx_hold_cycles, "NIC RX hold in application clock cycles; readback is programmed hardware value");

static unsigned int fault_irq;
static void fault_work_fn(struct work_struct *work) { handle_domain_faults(); }
static DECLARE_WORK(fault_work, fault_work_fn);

struct worker_fpi_data *worker_by_index(unsigned int index)
{
    if (WARN_ON(index >= LAUBERHORN_NUM_WORKER_CORES)) return NULL;
    return per_cpu_ptr(&fpi_percpu_data, worker_lo + index);
}
int check_ownership_abi(void)
{
    lauberhorn_eci_ownership_initialize(&ownership_dev, LAUBERHORN_ECI_OWNERSHIP_BASE);
    if (lauberhorn_eci_ownership_abi_rd(&ownership_dev) != 2) return -EPROTO;
    if (lauberhorn_eci_ownership_reset_required_rd(&ownership_dev)) return -ESHUTDOWN;
    return 0;
}
u32 allocate_domain_code(u32 slot)
{
    u64 code;
    if (lauberhorn_eci_ownership_reset_required_rd(&ownership_dev)) return U32_MAX;
    code = lauberhorn_eci_ownership_allocate_domain_rd(&ownership_dev);
    if (code >= (1U << 19) || lauberhorn_eci_ownership_reset_required_rd(&ownership_dev)) return U32_MAX;
    lauberhorn_eci_ownership_domain_code_wr(&ownership_dev, code);
    lauberhorn_eci_ownership_domain_slot_wr(&ownership_dev, slot);
    return code;
}
void fail_domain(struct proc_def *proc)
{
    unsigned long flags;
    int i;
    bool first;
    if (!proc) return;
    spin_lock_irqsave(&claim_lock, flags);
    first = !proc->failed;
    proc->failed = true;
    for (i = 0; i < proc->declared_workers; ++i) {
        proc->thr_defs[i].activation_ready = false;
        proc->thr_defs[i].waiting = false;
    }
    spin_unlock_irqrestore(&claim_lock, flags);
    // Kill before control MMIO: another handoff can temporarily occupy the bus.
    send_sig(SIGKILL, proc->leader, 1);
    wake_up_all(&proc->claims);
    for (i = 0; i < proc->declared_workers; ++i)
        if (proc->thr_defs[i].enabled) wake_up_all(&proc->thr_defs[i].wait);
    if (first) {
        lauberhorn_eci_ownership_fail_domain_wr(&ownership_dev, proc->idx);
        pr_err("Domain slot %u code %u killed; session requires coherence reset\n", proc->idx, proc->domain_code);
    }
}
void handle_domain_faults(void)
{
    u64 faults = lauberhorn_eci_ownership_failed_domains_rd(&ownership_dev);
    unsigned int slot;
    for (slot = 1; slot <= LAUBERHORN_NUM_PROCS; ++slot)
        if (faults & BIT_ULL(slot)) fail_domain(find_proc_slot(slot));
}
static irqreturn_t domain_fault_handler(int irq, void *data)
{
    schedule_work(&fault_work);
    return IRQ_HANDLED;
}
static irqreturn_t worker_fpi_handler(int irq, void *data)
{
    struct worker_fpi_data *worker = data;
    if (trace_activation_enabled() && !atomic_read(&worker->kick))
        WRITE_ONCE(worker->irq_raw_ns, ktime_get_raw_ns());
    atomic_set(&worker->kick, 1);
    wake_up(&worker->dispatch_wait);
    return IRQ_HANDLED;
}

/**
 * Install handlers for the software-generated interrupts (SGI) that comes from
 * the FPGA, for the worker cores.  Adam's Linux Memory Driver calls these FPIs,
 * probably FPGA peripheral interrupts.
 *
 * The Lauberhorn NIC sends two SGI interrupts:
 * - #15  only to core 0: bypass core descriptor FIFO non-empty
 * - #8   to worker cores: idle dispatch only
 * - #9   to CPU 0: fatal domain violations
 *
 * This function only handles the interrupt for the worker cores; the bypass core
 * interrupt is handled inside `init_bypass`.
 */
static int init_worker_fpi(void)
{
    int err, cid, enabled = worker_lo;
    struct irq_data *gic = irq_get_irq_data(1U);
    struct irq_fwspec spec;
    if (!gic || !gic->domain) return -ENODEV;
    spec = (struct irq_fwspec) {
        .fwnode = *(struct fwnode_handle **)gic->domain->host_data,
        .param_count = 1, .param[0] = 8,
    };
    err = irq_create_fwspec_mapping(&spec);
    if (err <= 0) return -EINVAL;
    irq_no = err;
    err = request_percpu_irq(irq_no, worker_fpi_handler, "Lauberhorn idle dispatch", &fpi_percpu_data);
    if (err) goto dispose_worker;
    for (cid = worker_lo; cid < worker_hi; ++cid) {
        err = smp_call_on_cpu(cid, do_fpi_irq_activate, (void *)irq_no, true);
        if (err) goto disable_workers;
        enabled = cid + 1;
    }
    spec.param[0] = 9;
    err = irq_create_fwspec_mapping(&spec);
    if (err <= 0) { err = -EINVAL; goto disable_workers; }
    fault_irq = err;
    err = request_percpu_irq(fault_irq, domain_fault_handler, "Lauberhorn domain fault", &fpi_percpu_data);
    if (err) goto dispose_fault;
    err = smp_call_on_cpu(0, do_fpi_irq_activate, (void *)(unsigned long)fault_irq, true);
    if (!err) return 0;
    free_percpu_irq(fault_irq, &fpi_percpu_data);
dispose_fault:
    irq_dispose_mapping(fault_irq);
disable_workers:
    for (cid = worker_lo; cid < enabled; ++cid)
        smp_call_on_cpu(cid, do_fpi_irq_deactivate, (void *)irq_no, true);
    synchronize_irq(irq_no);
    free_percpu_irq(irq_no, &fpi_percpu_data);
dispose_worker:
    irq_dispose_mapping(irq_no);
    return err;
}

static void deinit_worker_fpi(void)
{
	int err, cid;

	for (cid = worker_lo; cid < worker_hi; ++cid) {
		err = smp_call_on_cpu(cid, do_fpi_irq_deactivate,
				      (void *)irq_no, true);
		WARN_ON(err < 0);

	}
    smp_call_on_cpu(0, do_fpi_irq_deactivate, (void *)(unsigned long)fault_irq, true);
    synchronize_irq(fault_irq);
    free_percpu_irq(fault_irq, &fpi_percpu_data);
    irq_dispose_mapping(fault_irq);
    flush_work(&fault_work);
	synchronize_irq(irq_no);
	free_percpu_irq(irq_no, &fpi_percpu_data);
	irq_dispose_mapping(irq_no);
}

int init_workers()
{
	int err, cpu, i, started = 0;
	struct worker_fpi_data *fpi_data;
	struct task_struct *task;

	// From setting nohz_full=<range> and isolcpus=nohz,domain,managed_irq,<range>
	enum hk_type required_isol_types[] = {
		HK_TYPE_DOMAIN,	 HK_TYPE_TICK,	      HK_TYPE_WQ,
		HK_TYPE_TIMER,	 HK_TYPE_RCU,	      HK_TYPE_MISC,
		HK_TYPE_KTHREAD, HK_TYPE_MANAGED_IRQ,
	};
	for (i = 0; i < sizeof(required_isol_types) / sizeof(enum hk_type);
	     ++i) {
		if (!housekeeping_enabled(required_isol_types[i])) {
			pr_err("Required housekeeping type %d not enabled\n",
			       required_isol_types[i]);
			return -1;
		}
	}

	// In addition we should also set rcu_nocbs=<range> and irqaffinity=<complement of range>;
	// The backing storage for these masks are not exported so we don't check

	err = check_ownership_abi();
	if (err) return err;
    mutex_lock(&rx_hold_lock);
    lauberhorn_eci_hostIf_initialize(&host_if_dev, LAUBERHORN_ECI_HOST_IF_BASE);
    lauberhorn_eci_hostIf_rx_block_cycles_wr(&host_if_dev, rx_hold_cycles);
    host_if_ready = true;
    mutex_unlock(&rx_hold_lock);

	// Which cores are the worker cores?
	worker_hi = num_online_cpus();
	worker_lo = worker_hi - LAUBERHORN_NUM_WORKER_CORES;
    if (worker_lo <= 0) return -EINVAL;
	pr_info("Using %d cores %d-%d for RPC processing\n",
		LAUBERHORN_NUM_WORKER_CORES, worker_lo, worker_hi - 1);

	// Fill out the per-CPU struct
	for (cpu = worker_lo; cpu < worker_hi; ++cpu) {
		// Verify that the worker cores are correctly isolated
		if (!cpu_online(cpu) || housekeeping_test_cpu(cpu, required_isol_types[0])) {
			pr_err("Worker core %d is not properly isolated!\n",
			       cpu);
			err = -EINVAL;
            goto stop_dispatchers;
		}

		fpi_data = per_cpu_ptr(&fpi_percpu_data, cpu);
		fpi_data->index = cpu - worker_lo;
		fpi_data->cpu = cpu;

        lauberhorn_eci_desched_initialize(&fpi_data->desched_dev,
            worker_desched_base(fpi_data->index));


		// Program the real core ID for this worker (destination core for FPI)
		lauberhorn_eci_desched_real_core_id_wr(&fpi_data->desched_dev,
						       cpu);
        err = start_worker_dispatch(fpi_data);
        if (err) goto stop_dispatchers;
        ++started;
	}

	// Enable interrupts for all worker cores
	err = init_worker_fpi();
	if (err != 0) goto stop_dispatchers;

	// Promote ksoftirqd on this core to SCHED_FIFO with MAX_RT_PRIO / 2
	// The RPC tasks will run with a priority of 1 (just above SCHED_NORMAL)
	rcu_read_lock();
	for_each_process(task) {
		int core_id;
		if (sscanf(task->comm, "ksoftirqd/%d", &core_id) != 1) {
			continue;
		}
		if (core_id < worker_lo || core_id >= worker_hi) {
			continue;
		}

		pr_info("Setting ksoftirqd on core %d (PID %d) to SCHED_FIFO\n",
			core_id, task_pid_nr(task));
		sched_set_fifo(task);
	}
	rcu_read_unlock();

	// We don't have any RPC handlers on these worker cores yet, so nothing
	// more to do here.  Once a user-level application thread starts, it will
	// register itself with an ioctl to /dev/lauberhorn -- we then set their
	// affinity, scheduling policy and priority.

    return 0;
stop_dispatchers:
    while (started) stop_worker_dispatch(worker_by_index(--started));
    return err;
}

void deinit_workers()
{
    mutex_lock(&rx_hold_lock);
    host_if_ready = false;
    mutex_unlock(&rx_hold_lock);

	struct task_struct *task;

	// Disable FPI interrupt for the core
	deinit_worker_fpi();
    for (int index = 0; index < LAUBERHORN_NUM_WORKER_CORES; ++index) {
        stop_worker_dispatch(worker_by_index(index));
    }

	// Restore ksoftirqd to SCHED_NORMAL
	rcu_read_lock();
	for_each_process(task) {
		int core_id;
		if (sscanf(task->comm, "ksoftirqd/%d", &core_id) != 1) {
			continue;
		}
		if (core_id < worker_lo || core_id >= worker_hi) {
			continue;
		}

		pr_info("Restoring ksoftirqd on core %d (PID %d) to SCHED_NORMAL\n",
			core_id, task_pid_nr(task));
		sched_set_normal(task, 19);
	}
	rcu_read_unlock();
}
