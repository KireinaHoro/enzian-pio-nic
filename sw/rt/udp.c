/* SPDX-License-Identifier: BSD-3-Clause */
#define _GNU_SOURCE
#include <sched.h>
#include <errno.h>
#include <string.h>
#include "lauberhorn/udp.h"
#include "ioctl.h"
#include "worker.h"
#include "lauberhorn/trace.h"

int lauberhorn_udp_bind(lauberhorn_t *ctx, lauberhorn_udp_endpoint_t *ep, uint16_t port) {
    if (!ctx || !ep || !port || ctx->fd < 0 || ctx->_workers || ep->handle) {
        errno = EINVAL;
        return -1;
    }
    lauberhorn_reg_endpoint_t cmd = {.port = port};
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_REG_UDP_ENDPOINT, &cmd)) return -1;
    *ep = (lauberhorn_udp_endpoint_t){.owner = ctx, .handle = cmd.handle, .port = cmd.port};
    return 0;
}

int lauberhorn_udp_receive(lauberhorn_udp_datagram_t *packet) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !packet) { errno = EINVAL; return -1; }
    if (server_trace_inline == &w->rx_trace) {
        w->rx_trace.handler_exit_ns = server_trace_stamp(&w->rx_trace);
        server_trace_write(&w->rx_trace);
        server_trace_inline = NULL;
    }
    for (;;) {
        lauberhorn_pkt_desc_t desc;
        bool received;
        if (server_trace_enabled) {
            server_trace_rx_begin(&w->rx_trace, w->worker_id);
            received = core_eci_rx_traced(w->dp_base, &w->dp, &desc,
                &w->rx_trace.rx_unblock_ns, server_trace_clock);
            w->rx_trace.rx_exit_ns = server_trace_stamp(&w->rx_trace);
        } else {
            received = core_eci_rx(w->dp_base, &w->dp, &desc);
        }
        if (!received) return 0;
        if (desc.type == TY_PREEMPT) {
            struct lh_activation activation;
            if (lh_yield(&activation)) return -1;
            continue;
        }
        if (desc.type != TY_NATIVE_UDP_RX) { errno = EPROTO; return -1; }
        if (server_trace_enabled) {
            server_trace_inline = &w->rx_trace;
            w->rx_trace.request_bytes = desc.payload_len;
            w->rx_trace.rx_cpu_id = sched_getcpu();
            w->rx_trace.handler_enter_ns = server_trace_stamp(&w->rx_trace);
            server_trace_event(&w->rx_trace, LH_TRACE_RX_PACKET,
                ((uint64_t)desc.udp.saddr << 32) | ((uint64_t)desc.udp.sport << 16) | desc.udp.dport,
                desc.payload_len);
        }
        *packet = (lauberhorn_udp_datagram_t){
            .endpoint = desc.udp.endpoint, .saddr = desc.udp.saddr,
            .daddr = desc.udp.daddr, .sport = desc.udp.sport, .dport = desc.udp.dport,
            .data = w->dp.rx_buf, .length = desc.payload_len
        };
        return 1;
    }
}

int lauberhorn_udp_sendv(const lauberhorn_udp_endpoint_t *ep, uint32_t daddr,
                         uint16_t dport, const struct iovec *iov, size_t count) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !ep || ep->owner != w->ctx || !ep->handle || !dport ||
        (count && !iov)) { errno = EINVAL; return -1; }
    size_t length = 0;
    for (size_t i = 0; i < count; ++i) {
        if (iov[i].iov_len > LAUBERHORN_UDP_MAX_PAYLOAD - length) {
            errno = EMSGSIZE; return -1;
        }
        length += iov[i].iov_len;
    }
    size_t offset = 0;
    for (size_t i = 0; i < count; ++i) {
        if (iov[i].iov_len) memcpy(w->dp.tx_buf + offset, iov[i].iov_base, iov[i].iov_len);
        offset += iov[i].iov_len;
    }
    lauberhorn_pkt_desc_t desc = {.type = TY_NATIVE_UDP_TX, .payload_len = length,
        .udp = {.endpoint = ep->handle, .daddr = daddr, .dport = dport}};
    if (server_trace_inline) {
        struct server_trace_row *row = server_trace_inline;
        row->tx_worker_id = w->worker_id;
        row->tx_cpu_id = sched_getcpu();
        row->tx_enter_ns = server_trace_stamp(row);
        server_trace_event(row, LH_TRACE_UDP_TX_BEGIN, length, dport);
    }
    int result = core_eci_tx(w->dp_base, &w->dp, &desc);
    if (server_trace_inline) {
        struct server_trace_row *row = server_trace_inline;
        row->tx_exit_ns = server_trace_stamp(row);
        row->transport_status = result;
        if (result == CORE_TX_ACCEPTED) row->response_bytes += length;
        row->ok &= result == CORE_TX_ACCEPTED;
        server_trace_event(row, LH_TRACE_UDP_TX_END, length, result);
    }
    if (result == CORE_TX_ACCEPTED) return 0;
    errno = result == CORE_TX_RETRY ? EAGAIN : EIO;
    return -1;
}
