/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LAUBERHORN_TCP_TRANSPORT_H
#define LAUBERHORN_TCP_TRANSPORT_H
#ifdef __KERNEL__
#include <linux/types.h>
#include <linux/ioctl.h>
#else
#include <stdint.h>
#include <sys/ioctl.h>
#endif
/* Host-order IPv4 address/port. Register before workers; endpoint ownership is
 * retained for domain lifetime. Repeating the same registration is idempotent.
 * The VM delivers and transmits complete wire-format IPv4/TCP packets. */
struct lauberhorn_tcp_endpoint {
    uint32_t address, handle;
    uint16_t port, reserved;
};
#define LAUBERHORN_IOCTL_REG_TCP_ENDPOINT _IOWR('L', 17, struct lauberhorn_tcp_endpoint)
#ifndef __KERNEL__
#include <lauberhorn.h>
#include <lauberhorn/buffer.h>
/* Raw transport only: the optional TCP library owns protocol processing.
 * Initialize stack worker state before attach; attach parks until activation. */
int lauberhorn_tcp_attach(lauberhorn_t *, uint32_t index);
/* Returns 1 for a packet or DEADLINE, 0 for NACK, -1 for error. A packet borrow
 * (data != NULL) must be released before waiting/yielding. DEADLINE carries
 * only generation and needs acknowledgement/rearm through SET_DEADLINE. */
struct lauberhorn_tcp_event {
    const void *data;
    size_t length;
    uint64_t deadline_generation;
    uint32_t endpoint;
};
int lauberhorn_tcp_receive(struct lauberhorn_tcp_event *);
int lauberhorn_tcp_release(void);
int lauberhorn_tcp_send(const struct lauberhorn_tcp_endpoint *,
                       lauberhorn_tx_buffer_t *, size_t length);
#endif
#endif
