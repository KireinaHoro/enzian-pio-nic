/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Copyright (c) 2025 Zikai Liu */

// ioctls used by unprivileged applications on /dev/lauberhorn
// Shared by the kernel module and the user-space library

#ifndef LAUBERHORN_IOCTL_H
#define LAUBERHORN_IOCTL_H

#ifdef __KERNEL__
#include <linux/ioctl.h>
#else
#include <sys/ioctl.h>
#include <stdint.h>

#define u16 uint16_t
#define u32 uint32_t
#define u64 uint64_t

#endif

// Define ioctl numbers properly
// https://www.kernel.org/doc/Documentation/ioctl/ioctl-number.txt
#define LAUBERHORN_IOCTL_MAGIC "L"

// Register / deregister an application
// These are implemented as open and close on the device

// Register / deregister a service
typedef u16 lauberhorn_srv_id_t;
typedef struct {
	// to kernel
	void *func_ptr;
	u32 prog_num;
	u32 prog_ver;
	u32 proc_num;
	u16 port;
	// from kernel
	lauberhorn_srv_id_t id;
} lauberhorn_reg_srv_t;
#define LAUBERHORN_IOCTL_REG_SRV \
	_IOWR(LAUBERHORN_IOCTL_MAGIC, 1, lauberhorn_reg_srv_t)
#define LAUBERHORN_IOCTL_DEREG_SRV \
	_IOW(LAUBERHORN_IOCTL_MAGIC, 2, lauberhorn_srv_id_t)

// Start / stop handling requests on an application thread
// These are implemented as mmap / destroy VMA

#endif // LAUBERHORN_IOCTL_H
