/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Copyright (c) 2025 Zikai Liu */

// IOCTL commands definitions
// Shared by the kernel module and the user-space library

#ifndef LAUBERHORN_IOCTL_H
#define LAUBERHORN_IOCTL_H

#ifdef __KERNEL__
#include <linux/ioctl.h>
#else
#include <sys/ioctl.h>
#endif

// Define ioctl numbers properly
// https://www.kernel.org/doc/Documentation/ioctl/ioctl-number.txt
#define IOCTL_YIELD _IO('p', 'y')
#define IOCTL_TEST_ACTIVATE_PID _IOW('t', 'a', pid_t)

// Global configurations
#define LAUBERHORN_IOCTL_SET_RX_BLOCK_CYCLES _IOW('g', 1, int)
#define LAUBERHORN_IOCTL_GET_RX_BLOCK_CYCLES _IOR('g', 1, int)

#endif // LAUBERHORN_IOCTL_H
