/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#ifndef LAUBERHORN_RT_ONCRPC_H
#define LAUBERHORN_RT_ONCRPC_H

#include "lauberhorn.h"
#include "lauberhorn/oncrpc.h"

// Runtime-facing functions to marshal and unmarshal a message
// according to a schema

lauberhorn_msg_t
lauberhorn_oncrpc_alloc(struct lauberhorn_oncrpc_schema *schema);

void lauberhorn_oncrpc_free(struct lauberhorn_oncrpc_schema *schema,
                            lauberhorn_msg_t msg);

int lauberhorn_oncrpc_marshal(struct lauberhorn_oncrpc_schema *schema,
                              uint8_t *out_buf, size_t out_buf_size,
                              lauberhorn_msg_t in_msg);

int lauberhorn_oncrpc_unmarshal(struct lauberhorn_oncrpc_schema *schema,
                                lauberhorn_msg_t out_msg, uint8_t *in_buf,
                                size_t in_bytes);

#endif // LAUBERHORN_RT_ONCRPC_H