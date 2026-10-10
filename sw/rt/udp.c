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

static int udp_wait(void) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w) { errno = EINVAL; return -1; }
    /* Readiness is level-triggered over exactly one borrowed packet. */
    if (w->dp.rx_view.pending || w->dp.tx_reserved) { errno = EBUSY; return -1; }
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
            received = core_eci_rx_acquire_traced(w->dp_base, &w->dp, &desc,
                &w->rx_trace.rx_unblock_ns, server_trace_clock);
            w->rx_trace.rx_exit_ns = server_trace_stamp(&w->rx_trace);
        } else {
            received = core_eci_rx_acquire(w->dp_base, &w->dp, &desc);
        }
        if (!received) return 0;
        if (desc.type == TY_PREEMPT) {
            /* A control-only response never leaves a borrowed packet. */
            if (w->dp.rx_view.pending) { errno = EPROTO; return -1; }
            struct lh_activation activation;
            if (lh_yield(&activation)) return -1;
            continue;
        }
        if (desc.type != TY_NATIVE_UDP_RX || desc.payload_len > LAUBERHORN_UDP_MAX_PAYLOAD) {
            core_eci_rx_discard(&w->dp);
            errno = EPROTO; return -1;
        }
        w->udp_rx_desc = desc;
        if (server_trace_enabled) {
            server_trace_inline = &w->rx_trace;
            w->rx_trace.request_bytes = desc.payload_len;
            w->rx_trace.rx_cpu_id = sched_getcpu();
            server_trace_event(&w->rx_trace, LH_TRACE_RX_PACKET,
                ((uint64_t)desc.udp.saddr << 32) | ((uint64_t)desc.udp.sport << 16) | desc.udp.dport,
                desc.payload_len);
        }
        return 1;
    }
}

int lauberhorn_udp_receive_view(lauberhorn_udp_datagram_t *packet) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !packet) { errno = EINVAL; return -1; }
    int result = udp_wait();
    if (result <= 0) return result;
    const lauberhorn_pkt_desc_t *desc = &w->udp_rx_desc;
    *packet = (lauberhorn_udp_datagram_t){
        .endpoint = desc->udp.endpoint, .saddr = desc->udp.saddr,
        .daddr = desc->udp.daddr, .sport = desc->udp.sport, .dport = desc->udp.dport,
        .data = w->dp.rx_view.data, .length = desc->payload_len
    };
    if (server_trace_inline == &w->rx_trace)
        w->rx_trace.handler_enter_ns = server_trace_stamp(&w->rx_trace);
    return 1;
}

int lauberhorn_udp_release(void) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w) { errno = EINVAL; return -1; }
    if (!w->dp.rx_view.pending) { errno = EINVAL; return -1; }
    core_eci_rx_discard(&w->dp);
    return 0;
}

static int udp_commit(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    size_t length = desc->payload_len;
    uint16_t dport = desc->udp.dport;
    if (server_trace_inline) {
        struct server_trace_row *row = server_trace_inline;
        row->tx_worker_id = w->worker_id;
        row->tx_cpu_id = sched_getcpu();
        row->tx_enter_ns = server_trace_stamp(row);
        server_trace_event(row, LH_TRACE_UDP_TX_BEGIN, length, dport);
    }
    int result = core_eci_tx_commit(w->dp_base, &w->dp, desc);
    if (server_trace_inline) {
        struct server_trace_row *row = server_trace_inline;
        row->tx_exit_ns = server_trace_stamp(row);
        row->transport_status = result;
        if (result == CORE_TX_ACCEPTED) row->response_bytes += length;
        row->ok &= result == CORE_TX_ACCEPTED;
        server_trace_event(row, LH_TRACE_UDP_TX_END, length, result);
    }
    return result;
}

int lauberhorn_udp_send_buffer(const lauberhorn_udp_endpoint_t *ep, uint32_t daddr,
                               uint16_t dport, lauberhorn_tx_buffer_t *buffer, size_t length) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w || !ep || ep->owner != w->ctx || !ep->handle || !dport ||
        !buffer || buffer->_worker != w || !w->dp.tx_reserved ||
        buffer->_generation != w->dp.tx_generation || buffer->capacity != w->dp.tx_capacity) {
        errno = EINVAL; return -1;
    }
    if (length > buffer->capacity || length > LAUBERHORN_UDP_MAX_PAYLOAD) {
        errno = EMSGSIZE; return -1;
    }
    lauberhorn_pkt_desc_t desc = {.type = TY_NATIVE_UDP_TX, .payload_len = length,
        .udp = {.endpoint = ep->handle, .daddr = daddr, .dport = dport}};
    int result = udp_commit(w, &desc);
    memset(buffer, 0, sizeof(*buffer));
    if (result == CORE_TX_ACCEPTED) return 0;
    errno = result == CORE_TX_RETRY ? EAGAIN : EIO;
    return -1;
}
