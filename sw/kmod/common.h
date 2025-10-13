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

	// Used to check if service with same definition is already registered
	u16 port;
	u32 prog_num, prog_ver, proc_num;

	// For debugging
	void __user *func_ptr;

	u32 proc_idx;
};

// Defines a thread for an RPC application
struct thr_def {
	bool enabled;

	struct proc_def *parent;

	// Used to update the per-thread CL address to core worker mapping
	u32 prefix;

	// Which worker core ID is this thread currently running on?
	int worker_idx;

	struct vma_priv_data vma_data_datapath;
};

// Defines the process of an RPC application
struct proc_def {
	bool enabled;

	// This is the PID actually programmed into the process table
	pid_t tgid;
	struct thr_def thr_defs[LAUBERHORN_NUM_WORKER_CORES];

	// One page per process that contains the parity bits for all threads
	// 1 byte per thread: bit 0 is RX parity, bit 1 is TX parity
	u8 parity_page[PAGE_SIZE] __aligned(PAGE_SIZE);
	struct vma_priv_data vma_data_parity_page;
};
struct proc_def *find_proc(pid_t tgid);

int prepare_worker_thread(struct thr_def *thr);
void clean_worker_thread(struct thr_def *thr);
void enable_worker_thread(struct thr_def *thr, u32 core_idx);
void disable_worker_thread(struct thr_def *thr);

// Manipulate thread router to route / unroute a prefix to a core
int route_prefix_to_core(u32 prefix, u32 core_idx);
void unroute_prefix_on_core(u32 core_idx);

// CMAC functions
typedef struct cmac_t cmac_t;
int start_cmac(cmac_t *cmac, bool loopback);
void stop_cmac(cmac_t *cmac);

#define FPGA_MEM_BASE (0x10000000000UL)

#endif // LAUBERHORN_KMOD_COMMON_H
