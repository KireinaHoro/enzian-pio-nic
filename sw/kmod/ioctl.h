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

#define __user

#define u16 uint16_t
#define u32 uint32_t
#define u64 uint64_t

#endif

// Define ioctl numbers properly
// https://www.kernel.org/doc/Documentation/ioctl/ioctl-number.txt
#define LAUBERHORN_IOCTL_MAGIC 'L'

/* Compile-time transport identity. Query before mapping transport storage. */
#define LAUBERHORN_IOCTL_BACKEND _IOR(LAUBERHORN_IOCTL_MAGIC, 14, u32)
#define LAUBERHORN_BACKEND_ECI 0
#define LAUBERHORN_BACKEND_VM 1

// Register / deregister an application
// These are implemented as open and close on the device

// Register / deregister a service
typedef u16 lauberhorn_srv_id_t;

typedef struct {
	// to kernel
	void __user *func_ptr;
	u32 prog_num;
	u32 prog_ver;
	u32 proc_num;
	u16 port;
	// from kernel
	lauberhorn_srv_id_t id;
} lauberhorn_reg_srv_t;
#define LAUBERHORN_IOCTL_REG_SRV \
	_IOWR(LAUBERHORN_IOCTL_MAGIC, 1, lauberhorn_reg_srv_t)

typedef struct {
	// to kernel
	lauberhorn_srv_id_t id;
	// from kernel
	void __user *func_ptr;
} lauberhorn_dereg_srv_t;
#define LAUBERHORN_IOCTL_DEREG_SRV \
	_IOW(LAUBERHORN_IOCTL_MAGIC, 2, lauberhorn_dereg_srv_t)

#include "lauberhorn/activation.h"

/* Declare once before registration. Registration does not activate a thread. */
#define LAUBERHORN_IOCTL_SET_WORKER_COUNT _IOW(LAUBERHORN_IOCTL_MAGIC, 10, u32)
#define LAUBERHORN_IOCTL_REGISTER_THREAD _IOW(LAUBERHORN_IOCTL_MAGIC, 11, u32)
#define LAUBERHORN_IOCTL_YIELD _IOR(LAUBERHORN_IOCTL_MAGIC, 12, struct lh_activation)

// Legacy shutdown command, now an alias for whole-process STOP_PROCESS.
// Never wake parked workers against an unrouted datapath.
#define LAUBERHORN_IOCTL_WAKE_ALL_WORKERS _IO(LAUBERHORN_IOCTL_MAGIC, 3)

/* Fixed-width control ABI. Reserved fields must be zero. Numeric fields
 * are host order. Handles are scoped to the open device context. */
typedef struct {
    u32 handle;
    u16 pid;
    u16 port;
    u64 reserved;
} lauberhorn_reg_endpoint_t;
typedef struct {
    u32 handle;
    u32 reserved;
} lauberhorn_dereg_endpoint_t;
#define LAUBERHORN_IOCTL_REG_ENDPOINT \
    _IOWR(LAUBERHORN_IOCTL_MAGIC, 4, lauberhorn_reg_endpoint_t)
#define LAUBERHORN_IOCTL_DEREG_ENDPOINT \
    _IOW(LAUBERHORN_IOCTL_MAGIC, 5, lauberhorn_dereg_endpoint_t)

#define LAUBERHORN_IOCTL_REG_UDP_ENDPOINT \
    _IOWR(LAUBERHORN_IOCTL_MAGIC, 13, lauberhorn_reg_endpoint_t)

/* Slow-path cancellation/timeout control; never used to submit or receive RPCs. */
typedef struct {
    u32 endpoint_handle;
    u32 cookie;
} lauberhorn_retire_call_t;
#define LAUBERHORN_IOCTL_RETIRE_CALL \
    _IOW(LAUBERHORN_IOCTL_MAGIC, 6, lauberhorn_retire_call_t)

/* Terminate this application's workers and reclaim hardware ownership. */
#define LAUBERHORN_IOCTL_STOP_PROCESS _IO(LAUBERHORN_IOCTL_MAGIC, 7)

typedef struct { u32 limit_us; u32 reserved; } lauberhorn_watchdog_t;
#define LAUBERHORN_IOCTL_WATCHDOG _IOW(LAUBERHORN_IOCTL_MAGIC, 8, lauberhorn_watchdog_t)

/* Number 9 (the removed KICK_PROCESS operation) is not reused. */
#include "lauberhorn/deadline.h"
#include "lauberhorn/tcp_transport.h"

#endif // LAUBERHORN_IOCTL_H
