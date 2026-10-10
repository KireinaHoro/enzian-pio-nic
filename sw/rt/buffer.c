/* SPDX-License-Identifier: BSD-3-Clause */
#include <errno.h>
#include <string.h>
#include "worker.h"
#include "lauberhorn/buffer.h"

int lauberhorn_tx_get_buffer(bool inline_payload, lauberhorn_tx_buffer_t *out) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !out) { errno = EINVAL; return -1; }
    void *data = core_eci_tx_get_buffer(w->dp_base, &w->dp, inline_payload);
    if (!data) { errno = EBUSY; return -1; }
    *out = (lauberhorn_tx_buffer_t){data, w->dp.tx_capacity, w->dp.tx_generation, w};
    return 0;
}
int lauberhorn_tx_cancel(lauberhorn_tx_buffer_t *buffer) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !buffer || buffer->_worker != w || !w->dp.tx_reserved ||
        buffer->_generation != w->dp.tx_generation) { errno = EINVAL; return -1; }
    core_eci_tx_cancel(&w->dp);
    memset(buffer, 0, sizeof(*buffer));
    return 0;
}
