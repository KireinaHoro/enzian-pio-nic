/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Copyright (c) 2025 Zikai Liu, Pencheng Xu */

// Common preamble for rt parts

#ifndef LAUBERHORN_CORE_COMMON_H
#define LAUBERHORN_CORE_COMMON_H

#ifdef __KERNEL__

#include <linux/printk.h>
#include <linux/prefetch.h>
#include <asm/barrier.h>

#define BARRIER dmb(sy)
#define PREFETCH 

// always print module name in pr_info, pr_err, etc.
#ifdef pr_fmt
#undef pr_fmt
#endif
#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

// bypass core does not have preemption control; critical section operations are no-ops
#define enter_cs(...)
#define exit_cs(...)

#define assert(cond) BUG_ON(!(cond))

#else // not __KERNEL__

#include <assert.h>
#define static_assert _Static_assert // requires C11

// headers for user-space library
#include <stdio.h>
#define pr_err printf
#define pr_warn printf
#define pr_info printf

#ifdef DEBUG
#define pr_debug printf
#else
#define pr_debug(...)
#endif // DEBUG

#define pr_flush() fflush(stdout);

#define BARRIER asm volatile("dmb sy\nisb")

// prototypes for critical section handling -- defined in rt/eci.c
void enter_cs();
void exit_cs();

// prototype for prefetch -- defined in rt/eci.c
void prefetchw(const void *ptr);

#define min(a, b) ((a) < (b) ? (a) : (b))

#endif // __KERNEL__

#endif // LAUBERHORN_CORE_COMMON_H
