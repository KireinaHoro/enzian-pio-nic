/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef LAUBERHORN_BUFFER_H
#define LAUBERHORN_BUFFER_H
#include <stddef.h>
#include <stdbool.h>
#include <stdint.h>
/* One contiguous mutable TX reservation, owned by the current worker.
 * data is valid until submission or cancel. Do not yield while reserved.
 * Fields prefixed with _ are private; copying this struct does not transfer ownership. */
typedef struct {
    void *data;
    size_t capacity;
    uint64_t _generation;
    void *_worker;
} lauberhorn_tx_buffer_t;
/* Choose inline storage (64 bytes) or overflow storage (full transport MTU).
 * Unknown sizes should use false. The returned capacity bounds all writes and
 * sends; short overflow sends stay in overflow. 0 success, -1 with errno
 * (EBUSY if already reserved). Failure leaves the output unchanged. */
int lauberhorn_tx_get_buffer(bool inline_payload, lauberhorn_tx_buffer_t *);
/* Abandon a live reservation and invalidate its handle: 0 success, -1 error. */
int lauberhorn_tx_cancel(lauberhorn_tx_buffer_t *);
#endif
