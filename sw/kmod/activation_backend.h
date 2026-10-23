/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LAUBERHORN_ACTIVATION_BACKEND_H
#define LAUBERHORN_ACTIVATION_BACKEND_H

/* Device snapshot, distinct from the public worker activation. The caller
 * holds worker->reader and has fenced the outgoing task inside yield.
 * Reading acknowledges relinquishment and may block until device commit.
 * No claim_lock or control_lock may be held across this operation. */
struct lh_device_activation {
    u32 domain_code, domain_slot;
    u8 rx_next, tx_next;
};

struct proc_def;
void lh_device_fail_domain(struct proc_def *proc);
struct worker_fpi_data;
struct lh_device_activation lh_device_activate(struct worker_fpi_data *worker);
bool lh_device_idle_pending(struct worker_fpi_data *worker);
bool lh_device_yield_requested(struct worker_fpi_data *worker);

#endif
