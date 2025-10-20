/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Copyright (c) 2025 Zikai Liu, Pencheng Xu */

#ifndef LAUBERHORN_KMOD_COMMON_H
#define LAUBERHORN_KMOD_COMMON_H

#include <linux/cdev.h>
#include <linux/delay.h>
#include <linux/device.h>
#include <linux/fs.h>
#include <linux/init.h>
#include <linux/ioctl.h>
#include <linux/kdev_t.h>
#include <linux/kthread.h>
#include <linux/module.h>
#include <linux/sched.h>
#include <linux/sched/isolation.h>
#include <linux/slab.h> // kmalloc
#include <linux/uaccess.h> // copy_to/from_user
#include <linux/wait.h>
#include <linux/smp.h>
#include <linux/irq.h>
#include <linux/interrupt.h>
#include <linux/irqreturn.h>
#include <linux/irqdomain.h>

#include <asm/io.h>
#include <asm/arch_gicv3.h>

#include "eci/config.h"

#include "lauberhorn_eci_preempt_dev.h"
#include "lauberhorn_eci_worker_dev.h"

// Always print module name in pr_info, pr_err, etc.
#ifdef pr_fmt
#undef pr_fmt
#endif
#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

// Print SW, shell and NIC versions
int probe_versions(void);

// Init and deinit functions for bypass netdev handling
int init_bypass(void);
void deinit_bypass(void);

// Init and deinit functions for RPC worker cores
int init_workers(void);
void deinit_workers(void);

// IRQ activate and deactivate functions, for use with smp_call_on_cpu
int do_fpi_irq_activate(void *data);
int do_fpi_irq_deactivate(void *data);

// Create and destroy char devices for user APIs.
int create_devices(void);
void remove_devices(void);

#include "virt_node1.h"

// Map node 1 io/memory
int map_node1(void);
void unmap_node1(void);

struct proc_def;
struct thr_def;

// Private data for a VMA
#define THR_DATAPATH_VMA_NAME_SIZE 64
struct vma_priv_data {
	bool is_parity_page;

	union {
		struct proc_def *proc;
		struct {
			struct thr_def *thr;
			char vma_name[THR_DATAPATH_VMA_NAME_SIZE];
		};
	};
};

// Defines an RPC service
struct srv_def {
	bool enabled;
	u32 idx;

	// Is a service with same definition already registered?
	u16 port;
	u32 prog_num, prog_ver, proc_num;

	// For debugging -- the kernel does not touch this
	void __user *func_ptr;

	struct proc_def *proc;
};

// Defines a thread for an RPC application
struct thr_def {
	bool enabled;
	u32 idx;

	struct proc_def *parent;

	// Used to update the per-thread CL address to core worker mapping
	u16 prefix;
	static_assert(sizeof(u16) * 8 == LAUBERHORN_THR_PREFIX_WIDTH);
	phys_addr_t dp_phys_base;

	// Which worker core ID is this thread currently running on?
	int worker_idx;

	struct vma_priv_data vma_data_datapath;

	struct task_struct *task;
};

// Defines the process of an RPC application
struct proc_def {
	bool enabled;
	u32 idx;

	// This is the PID actually programmed into the process table
	pid_t tgid;
	struct thr_def thr_defs[LAUBERHORN_NUM_WORKER_CORES];
	u32 num_rdy_thrs;

	// Track services that this process owns
	struct srv_def *srvs[LAUBERHORN_NUM_SERVICES];

	// One page per process that contains the parity bits for all threads
	// 2 byte per thread: byte 0 is RX parity, byte 1 is TX parity
	u8 parity_page[PAGE_SIZE] __aligned(PAGE_SIZE);
	static_assert(PAGE_SIZE >= LAUBERHORN_NUM_WORKER_CORES * 2);

	struct vma_priv_data vma_data_parity_page;
};
struct proc_def *find_proc(pid_t tgid);

struct worker_fpi_data {
	// Mackerel devices for this CPU core
	lauberhorn_eci_preempt_t preempt_dev;
	lauberhorn_eci_worker_t worker_dev;

	// Thread running on this core (if any)
	struct thr_def *thr;
};

int prepare_worker_thread(struct thr_def *thr);
void clean_worker_thread(struct thr_def *thr);
void sched_worker_thread(struct thr_def *thr, struct worker_fpi_data *fpi_priv);
void desched_worker_thread(struct thr_def *thr);

// Manipulate thread router to route / unroute a prefix to a core
void route_prefix_to_core(u16 prefix, u32 core_idx);
void unroute_prefix_on_core(u32 core_idx);

// CMAC functions
typedef struct cmac_t cmac_t;
int start_cmac(cmac_t *cmac, bool loopback);
void stop_cmac(cmac_t *cmac);

// L2 management instructions
static inline void cl_hit_inv(phys_addr_t addr)
{
	// L2 Cache Hit Invalidate, SYS CVMCACHEINVL2, Xt
	asm volatile("sys #0,c11,c1,#1,%0 \n" ::"r"(addr));
}
static inline void cl_fetch_and_lock(phys_addr_t addr)
{
	// L2 Cache Fetch and Lock, SYS CVMCACHELCKL2, Xt
	asm volatile("sys #0,c11,c1,#4,%0 \n" ::"r"(addr));
}

#endif // LAUBERHORN_KMOD_COMMON_H