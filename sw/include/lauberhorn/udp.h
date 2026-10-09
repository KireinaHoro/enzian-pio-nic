/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef LAUBERHORN_UDP_H
#define LAUBERHORN_UDP_H
#include <lauberhorn.h>
#include <sys/uio.h>

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
    const void *data;
    size_t length;
} lauberhorn_udp_datagram_t;

/* Zero-initialize endpoint. Register before any worker starts. */
int lauberhorn_udp_bind(lauberhorn_t *, lauberhorn_udp_endpoint_t *, uint16_t port);
/* Call on each already initialized application worker, with a distinct pool
 * index. Registers then parks in bootstrap yield until packet-driven activation.
 * No individual detach or graceful pool teardown is supported. */
int lauberhorn_udp_attach(lauberhorn_t *, uint32_t index);
/* 1 = datagram (including length zero), 0 = RX NACK, -1 = error.
 * Borrows worker-local copied storage until next receive; copy before retaining.
 * Call only after draining application work and responses. PREEMPT is handled
 * immediately through the existing yield contract before any further RX/TX. */
int lauberhorn_udp_receive(lauberhorn_udp_datagram_t *);
/* Synchronous local admission/consumption: 0 success, -1 with errno.
 * EAGAIN is definite rejection and permits retry of unchanged buffers.
 * EIO is indeterminate and must not be retried automatically. Success says
 * nothing about remote delivery. Storage is borrowed only during this call.
 * Multiple datagrams may be sent per receive, including zero-length sends. */
int lauberhorn_udp_sendv(const lauberhorn_udp_endpoint_t *, uint32_t daddr,
                         uint16_t dport, const struct iovec *, size_t count);
#endif
