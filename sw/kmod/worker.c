// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

#include "common.h"

#include "eci/regblock_bases.h"

#include "lauberhorn_eci_preempt_dev.h"
#include "lauberhorn_eci_threadRouter_dev.h"

// [lo, hi) bound of CPU cores used to handle RPC requests
int worker_lo, worker_hi;
static DEFINE_PER_CPU_READ_MOSTLY(struct worker_fpi_data, fpi_percpu_data);
static u64 irq_no;

static lauberhorn_eci_threadRouter_t thread_router_dev;

static irqreturn_t worker_fpi_handler(int irq, void *data)
{
	u32 next_pid;
	bool killed;
	lauberhorn_eci_preempt_sched_cmd_t ack_reg;
	struct worker_fpi_data *priv = *(struct worker_fpi_data **)data;

	struct proc_def *next_proc;
	struct thr_def *next_thr = NULL;
	int i;

	pr_info("%s.%d[%2d]: FPI %d\n", __func__, __LINE__, smp_processor_id(),
		irq);

	// Disable interrupt
	lauberhorn_eci_preempt_irq_en_wr(&priv->preempt_dev, 0);

	// Read out preempt command -- this tells the HW we are in the kernel
	ack_reg = lauberhorn_eci_preempt_sched_cmd_rawrd(&priv->preempt_dev);

	// Decode next task and killed
	next_pid = lauberhorn_eci_preempt_sched_cmd_next_pid_extract(ack_reg);
	killed = lauberhorn_eci_preempt_sched_cmd_killed_extract(ack_reg);

	BUG_ON(killed);
	next_proc = find_proc(next_pid);
	BUG_ON(!next_proc);

	// Disable old thread, if one is actually running
	if (priv->thr) {
		desched_worker_thread(priv->thr);
	}

	// Select and enable new thread
	for (i = 0; i < LAUBERHORN_NUM_WORKER_CORES; ++i) {
		if (!next_proc->thr_defs[i].enabled) {
			next_thr = &next_proc->thr_defs[i];
			break;
		}
	}
	BUG_ON(!next_thr);
	sched_worker_thread(next_thr, priv);

	// Interrupt will be unmasked when the new thread is scheduled

	return IRQ_HANDLED;
}

/**
 * Install handlers for the software-generated interrupts (SGI) that comes from
 * the FPGA, for the worker cores.  Adam's Linux Memory Driver calls these FPIs,
 * probably FPGA peripheral interrupts.
 *
 * The Lauberhorn NIC sends two SGI interrupts:
 * - #15  only to core 0: bypass core descriptor FIFO non-empty
 * - #8   to all worker cores: preemption interrupt for switching tasks
 *
 * This function only handles the interrupt for the worker cores; the bypass core
 * interrupt is handled inside `init_bypass`.
 */
static int init_worker_fpi(void)
{
	int err, cid;
	struct irq_data *gic_irq_data;
	struct irq_domain *gic_domain;
	struct fwnode_handle *fwnode;
	struct worker_fpi_data *fpi_data;
	static struct irq_fwspec fwspec_fpi;

	// Get the fwnode for the GIC.  A hack here to find the fwnode through IRQ
	// 1, since we don't have a device tree node.  We assuming that fwnode is
	// the first element of structure gic_chip_data
	gic_irq_data = irq_get_irq_data(1U);
	gic_domain = gic_irq_data->domain;
	fwnode = *(struct fwnode_handle **)(gic_domain->host_data);

	// Allocate an IRQ number for SGI #8 for all worker cores
	fwspec_fpi = (struct irq_fwspec){
		.fwnode = fwnode,
		.param_count = 1,
		.param[0] = 8,
	};
	err = irq_create_fwspec_mapping(&fwspec_fpi);
	if (err < 0) {
		pr_warn("irq_create_fwspec_mapping returns %d\n", err);
		return err;
	}
	irq_no = err;
	pr_info("Allocated interrupt number = %llu\n", irq_no);
	smp_wmb();

	err = request_percpu_irq(irq_no, worker_fpi_handler,
				 "Lauberhorn RPC Worker Preemption IRQ",
				 &fpi_percpu_data);
	if (err < 0) {
		pr_warn("request_percpu_irq returns %d\n", err);
		return err;
	}

	for (cid = worker_lo; cid < worker_hi; ++cid) {
		// Activate FPI IRQ handler on this core
		err = smp_call_on_cpu(cid, do_fpi_irq_activate, (void *)irq_no,
				      true);
		WARN_ON(err < 0);

		// Unmask FPI interrupt in HW
		fpi_data = per_cpu_ptr(&fpi_percpu_data, cid);
		lauberhorn_eci_preempt_irq_en_wr(&fpi_data->preempt_dev, 1);
	}

	return 0;
}

static void deinit_worker_fpi(void)
{
	int err, cid;
	struct worker_fpi_data *fpi_data;

	for (cid = worker_lo; cid < worker_hi; ++cid) {
		err = smp_call_on_cpu(cid, do_fpi_irq_deactivate,
				      (void *)irq_no, true);
		WARN_ON(err < 0);

		// Mask FPI interrupt in HW
		fpi_data = per_cpu_ptr(&fpi_percpu_data, cid);
		lauberhorn_eci_preempt_irq_en_wr(&fpi_data->preempt_dev, 0);
	}
	free_percpu_irq(irq_no, &fpi_percpu_data);
	irq_dispose_mapping(irq_no);
}

int init_workers()
{
	int err, cpu, i;
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

	lauberhorn_eci_threadRouter_initialize(
		&thread_router_dev, LAUBERHORN_ECI_THREAD_ROUTER_BASE);

	// Which cores are the worker cores?
	worker_hi = num_online_cpus();
	worker_lo = worker_hi - LAUBERHORN_NUM_WORKER_CORES;
	pr_info("Using %d cores %d-%d for RPC processing\n",
		LAUBERHORN_NUM_WORKER_CORES, worker_lo, worker_hi - 1);

	// Fill out the per-CPU struct
	for (cpu = worker_lo; cpu < worker_hi; ++cpu) {
		// Verify that the worker cores are correctly isolated
		if (housekeeping_test_cpu(cpu, required_isol_types[0])) {
			pr_err("Worker core %d is not properly isolated!\n",
			       cpu);
			return -1;
		}

		fpi_data = per_cpu_ptr(&fpi_percpu_data, cpu);

		lauberhorn_eci_preempt_initialize(
			&fpi_data->preempt_dev,
			LAUBERHORN_ECI_PREEMPT_BASE(cpu - worker_lo + 1));
		lauberhorn_eci_worker_initialize(
			&fpi_data->worker_dev,
			LAUBERHORN_ECI_WORKER_BASE(cpu - worker_lo + 1));

		// Program the real core ID for this worker (destination core for FPI)
		lauberhorn_eci_preempt_real_core_id_wr(&fpi_data->preempt_dev,
						       cpu);
	}

	// Enable interrupts for all worker cores
	err = init_worker_fpi();
	if (err != 0)
		return err;

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
}

void deinit_workers()
{
	struct task_struct *task;

	// Disable FPI interrupt for the core
	deinit_worker_fpi();

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

static DEFINE_SPINLOCK(thread_router_spinlock);

void route_prefix_to_core(u16 prefix, u32 core_idx)
{
	unsigned long flags;
	spin_lock_irqsave(&thread_router_spinlock, flags);

	pr_info("Enabling prefix %#x on core slot %u\n", prefix, core_idx);

	lauberhorn_eci_threadRouter_ctrl_enabled_wr(&thread_router_dev, 1);
	lauberhorn_eci_threadRouter_ctrl_addr_prefix_wr(&thread_router_dev,
							prefix);
	lauberhorn_eci_threadRouter_ctrl_core_idx_wr(&thread_router_dev,
						     core_idx);

	spin_unlock_irqrestore(&thread_router_spinlock, flags);
}

void unroute_prefix_on_core(u32 core_idx)
{
	unsigned long flags;
	spin_lock_irqsave(&thread_router_spinlock, flags);

	pr_info("Unrouting core %u\n", core_idx);

	lauberhorn_eci_threadRouter_ctrl_enabled_wr(&thread_router_dev, 0);
	lauberhorn_eci_threadRouter_ctrl_core_idx_wr(&thread_router_dev,
						     core_idx);

	spin_unlock_irqrestore(&thread_router_spinlock, flags);
}
