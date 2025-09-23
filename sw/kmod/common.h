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
#include <linux/slab.h>    // kmalloc
#include <linux/uaccess.h> // copy_to/from_user
#include <linux/wait.h>
#include <linux/smp.h>
#include <linux/irq.h>
#include <linux/interrupt.h>
#include <linux/irqreturn.h>
#include <linux/irqdomain.h>

#include <asm/io.h>
#include <asm/arch_gicv3.h>

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

// Scheduler integration: worker thread management
int prepare_worker_thread();
void clean_worker_thread(u32 proc_idx, u32 thr_idx);
void enable_worker_thread();
void disable_worker_thread();

// CMAC functions
typedef struct cmac_t cmac_t;
int start_cmac(cmac_t *cmac, bool loopback);
void stop_cmac(cmac_t *cmac);

#endif  // LAUBERHORN_KMOD_COMMON_H
