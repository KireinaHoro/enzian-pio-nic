/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Copyright (c) 2025 Zikai Liu */
// This file should be included at last

#ifndef __PIONIC_KERNEL_MODULE_COMMON_H__
#define __PIONIC_KERNEL_MODULE_COMMON_H__

#include <linux/printk.h>

// Always print module name in pr_info, pr_err, etc.
#ifdef pr_fmt
#undef pr_fmt
#endif
#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

// Init and deinit functions for bypass netdev handling
int init_bypass(void);
void deinit_bypass(void);

// IRQ activate and deactivate functions, for use with smp_call_on_cpu
int do_fpi_irq_activate(void *data);
int do_fpi_irq_deactivate(void *data);

#endif
