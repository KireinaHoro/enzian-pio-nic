// IOCTL commands definitions
// Shared by the kernel module and the user-space library

#ifndef __PIONIC_IOCTL_H__
#define __PIONIC_IOCTL_H__

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
#define PIONIC_IOCTL_SET_RX_BLOCK_CYCLES _IOW('g', 1, int)
#define PIONIC_IOCTL_GET_RX_BLOCK_CYCLES _IOR('g', 1, int)

#endif // __PIONIC_IOCTL_H__