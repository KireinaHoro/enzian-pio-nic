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
#include <linux/mutex.h>
#include "lauberhorn/activation.h"
#include <linux/irq.h>
#include <linux/interrupt.h>
#include <linux/irqreturn.h>
#include <linux/irqdomain.h>

#include <asm/io.h>
#if !LH_BACKEND_VM
#include <asm/arch_gicv3.h>
#endif

#include "config.h"

#if !LH_BACKEND_VM
#include "lauberhorn_eci_desched_dev.h"
#include "lauberhorn_eci_worker_dev.h"
#endif

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

// ThunderX L2C-TAD debug sampling
int init_l2c_tad_debug(void);
void deinit_l2c_tad_debug(void);
void l2c_tad_debug_print_tx(u32 tx_len);

struct proc_def;
struct thr_def;

// Defines an RPC service
struct srv_def {
	bool enabled;
	u32 idx;

	// Which listen port slot is this service occupying in
	// the UDP decoder?
	u32 listen_idx;

	// Is a service with same definition already registered?
	u16 port;
	u32 prog_num, prog_ver, proc_num;

	// For debugging -- the kernel does not touch this
	void __user *func_ptr;

	struct proc_def *proc;
};

/* Thread identity, scheduler slot, PA lifetime and CPU are separate. */
#include "activation_timing.h"
struct thr_def {
    bool enabled, waiting, activation_ready;
    u32 idx;
    struct proc_def *parent;
    int worker_idx;
    struct task_struct *task;
    wait_queue_head_t wait;
    struct lh_activation activation;
    struct lh_activation_timing timing;
};
struct proc_def {
    bool enabled, failed;
    u64 lifetime;
    u64 deadline_ns; /* last successful publication, CLOCK_MONOTONIC */
    u32 idx, domain_code, declared_workers, num_rdy_thrs;
    pid_t tgid;
    struct task_struct *leader;
#if LH_BACKEND_VM
    void *vm_buffers;
#endif
    struct thr_def thr_defs[LAUBERHORN_NUM_WORKER_CORES];
    struct srv_def *srvs[LAUBERHORN_NUM_SERVICES];
    wait_queue_head_t claims;
};
struct proc_def *find_proc(pid_t tgid);
struct proc_def *find_proc_slot(u32 slot);
void fail_domain(struct proc_def *proc);
void handle_domain_faults(void);
u32 allocate_domain_code(u32 slot);
int check_ownership_abi(void);
extern spinlock_t claim_lock;

struct worker_fpi_data {
#if !LH_BACKEND_VM
    lauberhorn_eci_desched_t desched_dev;
#endif
    struct thr_def *thr;
    int index, cpu;
    struct mutex reader;
    wait_queue_head_t dispatch_wait;
    atomic_t kick;
    u64 irq_raw_ns, activation_sequence;
    struct task_struct *dispatcher;
};
struct worker_fpi_data *worker_by_index(unsigned int index);
int start_worker_dispatch(struct worker_fpi_data *worker);
void stop_worker_dispatch(struct worker_fpi_data *worker);
long worker_yield(struct proc_def *proc, struct lh_activation __user *result);
int register_worker_thread(struct proc_def *proc, u32 index);

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
static inline void cl_hit_wb_inv(phys_addr_t addr)
{
	// L2 Cache Hit Writeback Invalidate, SYS CVMCACHEWBIL2, Xt
	asm volatile("sys #0,c11,c1,#2,%0 \n" ::"r"(addr));
}

#endif // LAUBERHORN_KMOD_COMMON_H
