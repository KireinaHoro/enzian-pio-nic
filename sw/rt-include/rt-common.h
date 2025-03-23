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
#ifdef DEBUG
#include <stdio.h>
#define pr_debug printf
#else
#define pr_debug

#endif  // __KERNEL__

#define HDR_ETHERNET_SIZE TODO:
#define HDR_IP_SIZE TODO:
#define HDR_UDP_SIZE TODO:
#define HDR_ONC_RPC_CALL_SIZE TODO:

#endif  // __RT_COMMON_H__