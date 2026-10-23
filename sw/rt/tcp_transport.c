/* SPDX-License-Identifier: BSD-3-Clause */
#include <errno.h>
#include <string.h>
#include "lauberhorn/tcp_transport.h"
#include "worker.h"
int lauberhorn_tcp_receive(struct lauberhorn_tcp_event *event)
{
#if LH_BACKEND_VM
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !event) { errno = EINVAL; return -1; }
    if (w->dp.rx_view.pending || w->dp.tx_reserved) { errno = EBUSY; return -1; }
    for (;;) {
        lauberhorn_pkt_desc_t desc;
        if (!core_rx_acquire(w->dp_base, &w->dp, &desc)) return 0;
        if (desc.type == TY_DESCHED) {
            struct lh_activation activation;
            if (lh_yield(&activation)) return -1;
            continue;
        }
        if (desc.type == TY_DEADLINE) {
            *event = (struct lauberhorn_tcp_event){ .deadline_generation = desc.deadline.generation };
            return 1;
        }
        if (desc.type != TY_NATIVE_TCP_RX) {
            core_rx_release(&w->dp); errno = EPROTO; return -1;
        }
        *event = (struct lauberhorn_tcp_event){ .data = w->dp.rx_view.data,
            .length = w->dp.rx_view.length, .endpoint = desc.tcp.endpoint };
        return 1;
    }
#else
    errno = EOPNOTSUPP; return -1;
#endif
}
int lauberhorn_tcp_release(void)
{
#if LH_BACKEND_VM
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !w->dp.rx_view.pending) { errno = EINVAL; return -1; }
    core_rx_release(&w->dp); return 0;
#else
    errno = EOPNOTSUPP; return -1;
#endif
}
int lauberhorn_tcp_send(const struct lauberhorn_tcp_endpoint *ep,
                       lauberhorn_tx_buffer_t *buffer, size_t length)
{
#if LH_BACKEND_VM
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !ep || !ep->handle || !buffer || buffer->_worker != w ||
        !w->dp.tx_reserved || buffer->_generation != w->dp.tx_generation ||
        buffer->capacity != w->dp.tx_capacity) { errno = EINVAL; return -1; }
    if (length > buffer->capacity) { errno = EMSGSIZE; return -1; }
    lauberhorn_pkt_desc_t desc = { .type = TY_NATIVE_TCP_TX,
        .tcp.endpoint = ep->handle, .payload_len = length };
    int result = core_tx_commit(w->dp_base, &w->dp, &desc);
    memset(buffer, 0, sizeof(*buffer));
    if (result == CORE_TX_ACCEPTED) return 0;
    errno = result == CORE_TX_RETRY ? EAGAIN : EIO; return -1;
#else
    errno = EOPNOTSUPP; return -1;
#endif
}
