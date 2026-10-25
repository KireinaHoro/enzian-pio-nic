/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#include "common.h"
#include "activation_backend.h"

struct lh_device_activation lh_device_activate(struct worker_fpi_data *worker)
{
    u64 info = lauberhorn_eci_desched_desched_info_rawrd(&worker->desched_dev);
    return (struct lh_device_activation) {
        .domain_code = lauberhorn_eci_desched_desched_info_domain_code_extract(info),
        .domain_slot = lauberhorn_eci_desched_desched_info_domain_slot_extract(info),
        .rx_next = lauberhorn_eci_desched_desched_info_rx_next_extract(info),
        .tx_next = lauberhorn_eci_desched_desched_info_tx_next_extract(info),
    };
}

bool lh_device_idle_pending(struct worker_fpi_data *worker)
{
    return lauberhorn_eci_desched_idle_notification_rd(&worker->desched_dev);
}

bool lh_device_yield_requested(struct worker_fpi_data *worker)
{
    return lauberhorn_eci_desched_yield_requested_rd(&worker->desched_dev);
}
