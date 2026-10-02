/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LAUBERHORN_ACTIVATION_H
#define LAUBERHORN_ACTIVATION_H
#ifdef __KERNEL__
#include <linux/types.h>
struct lh_activation {
    __u32 worker_index;
    __u32 rx_next;
    __u32 tx_next;
};
#else
#include <stdint.h>
struct lh_activation {
    uint32_t worker_index;
    uint32_t rx_next;
    uint32_t tx_next;
};
#endif
#endif
