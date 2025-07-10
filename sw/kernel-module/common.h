// This file should be included at last

#ifndef __PIONIC_KERNEL_MODULE_COMMON_H__
#define __PIONIC_KERNEL_MODULE_COMMON_H__

#include <linux/printk.h>

// Always print module name in pr_info, pr_err, etc.
#ifdef pr_fmt
#undef pr_fmt
#endif
#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

#endif
