/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LH_VM_ABI_H
#define LH_VM_ABI_H
#include "transport_types.h"
#ifdef __KERNEL__
#include <linux/ioctl.h>
#else
#include <sys/ioctl.h>
#endif
/* Private 64-bit VM ABI. No compat_ioctl is supplied. Buffers are per-domain,
 * per-device-worker, not per application thread. Kernel validates the caller's
 * current activation for every transport operation. */
/* Read-only scheduler diagnostics for this domain. Counts include in-flight
 * reservations separately; no mutation or cross-domain access. */
struct lh_vm_schedule_state {
    uint32_t queued, capacity, assigned, reserved, registered, ready_workers;
    uint32_t last_deadline_donor_fill, pad;
    uint64_t arrival_idle, arrival_ready, background, deadline;
};
#define LH_VM_SCHEDULE_STATE _IOR('L', 37, struct lh_vm_schedule_state)
#define LH_VM_TX_OFFSET 4096
#define LH_VM_RX _IOR('L', 32, lauberhorn_pkt_desc_t)
#define LH_VM_RX_RELEASE _IO('L', 33)
#define LH_VM_TX_RESERVE _IO('L', 34)
#define LH_VM_TX_CANCEL _IO('L', 35)
#define LH_VM_TX _IOW('L', 36, lauberhorn_pkt_desc_t)
#endif
