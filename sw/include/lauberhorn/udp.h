/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef LAUBERHORN_UDP_H
#define LAUBERHORN_UDP_H
#include <lauberhorn.h>
#include <lauberhorn/buffer.h>

#define LAUBERHORN_UDP_MAX_PAYLOAD 1472

/* Numeric network fields are host order. No per-peer/session allocation. */
typedef struct {
    lauberhorn_t *owner;
    uint32_t handle;
    uint16_t port;
} lauberhorn_udp_endpoint_t;
typedef struct {
    uint32_t endpoint, saddr, daddr;
    uint16_t sport, dport;
    void *data;
    size_t length;
} lauberhorn_udp_datagram_t;

/* Zero-initialize endpoint. Register before any worker starts. */
int lauberhorn_udp_bind(lauberhorn_t *, lauberhorn_udp_endpoint_t *, uint16_t port);
/* Call on each already initialized application worker, with a distinct pool
 * index. Registers then parks in bootstrap yield until packet-driven activation.
 * No individual detach or graceful pool teardown is supported. */
int lauberhorn_udp_attach(lauberhorn_t *, uint32_t index);
/* Wait for one contiguous mutable borrowed datagram: 1 packet (including
 * zero length), 0 NACK, -1 error. No payload staging. Only call at the worker
 * idle boundary after prior work/responses drain. PREEMPT yields internally.
 * Release after every user of the bytes finishes, before another receive or
 * assignment-releasing yield. Queueing a pointer is NOT release. A second
 * receive while borrowed fails with EBUSY; keep readiness in application state. */
int lauberhorn_udp_receive_view(lauberhorn_udp_datagram_t *);
int lauberhorn_udp_release(void);

/* Consume a live TX reservation on submission, including rejection. On EAGAIN
 * reserve again and regenerate/copy bytes; the old pointer is no longer valid.
 * EIO is indeterminate: do not retry automatically. Success is local admission,
 * not remote delivery. Multiple sends per receive and zero-length sends are valid.
 * Validation errors before submission leave the reservation live for abort. */
int lauberhorn_udp_send_buffer(const lauberhorn_udp_endpoint_t *, uint32_t daddr,
    uint16_t dport, lauberhorn_tx_buffer_t *, size_t actual_length);
#endif
