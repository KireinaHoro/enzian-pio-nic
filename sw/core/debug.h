// Common preamble for rt parts

#ifndef __RT_COMMON_H__
#define __RT_COMMON_H__

#include <assert.h>
#define STATIC_ASSERT _Static_assert  // requires C11

#ifdef __KERNEL__

#include <linux/printk.h>

// always print module name in pr_info, pr_err, etc.
#ifdef pr_fmt
#undef pr_fmt
#endif
#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

#else  // not __KERNEL__

// headers for user-space library
#include <stdio.h>
#define pr_err printf
#define pr_warn printf
#define pr_info printf

#ifdef DEBUG
#define pr_debug printf
#else
#define pr_debug
#endif  // DEBUG

#define pr_flush() fflush(stdout);

#endif  // __KERNEL__

#endif  // __RT_COMMON_H__