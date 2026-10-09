/* SPDX-License-Identifier: BSD-3-Clause */
#ifndef LAUBERHORN_BUFFER_H
#define LAUBERHORN_BUFFER_H
#include <stddef.h>
#include <stdint.h>
/* One assignment-bound TX reservation. Capacity chooses inline (<=64) or
 * overflow. Commit may send fewer bytes, without changing placement. Do not
 * retain data after commit/abort, or queue this object across worker yields. */
typedef struct {
    void *data;
    size_t capacity;
    uint64_t _generation;
    void *_worker;
} lauberhorn_tx_buffer_t;
int lauberhorn_tx_get_buffer(size_t capacity, lauberhorn_tx_buffer_t *);
int lauberhorn_tx_abort(lauberhorn_tx_buffer_t *);
#endif
