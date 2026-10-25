/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LAUBERHORN_DEADLINE_H
#define LAUBERHORN_DEADLINE_H
#ifdef __KERNEL__
#include <linux/types.h>
#include <linux/ioctl.h>
#define LH_DEADLINE_U64 __u64
#else
#include <stdint.h>
#include <sys/ioctl.h>
#define LH_DEADLINE_U64 uint64_t
#endif
/* Absolute CLOCK_MONOTONIC nanoseconds; zero cancels. expected_generation is
 * compare-and-replace: ESTALE, ETIME and all other errors leave state unchanged.
 * Success atomically acknowledges the previous event and publishes a new
 * generation. A fresh domain starts at generation zero. */
struct lauberhorn_deadline_set {
    LH_DEADLINE_U64 next_deadline_ns;
    LH_DEADLINE_U64 expected_generation;
    LH_DEADLINE_U64 generation;
};
struct lauberhorn_deadline_state {
    LH_DEADLINE_U64 generation, next_deadline_ns, minimum_lead_ns;
    LH_DEADLINE_U64 pending, delivered;
};
#define LAUBERHORN_IOCTL_SET_DEADLINE _IOWR('L', 15, struct lauberhorn_deadline_set)
#define LAUBERHORN_IOCTL_GET_DEADLINE _IOR('L', 16, struct lauberhorn_deadline_state)
#undef LH_DEADLINE_U64
#endif
