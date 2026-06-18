/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#include "oncrpc.h"

#include <limits.h>

lauberhorn_msg_t
lauberhorn_oncrpc_req_alloc(struct lauberhorn_oncrpc_schema *schema) {
  return calloc(1, schema->call_size);
}

void lauberhorn_oncrpc_req_free(struct lauberhorn_oncrpc_schema *schema,
                                lauberhorn_msg_t msg) {
  free(msg);
}

int lauberhorn_oncrpc_marshal(struct lauberhorn_oncrpc_schema *schema,
                              uint8_t *out_buf, size_t out_buf_size,
                              lauberhorn_msg_t in_msg) {
  XDR xdrs;
  xdrmem_create(&xdrs, (char *)out_buf, out_buf_size, XDR_ENCODE);

  if (!schema->resp_func(&xdrs, in_msg)) {
    xdr_destroy(&xdrs);
    return -1;
  }

  unsigned int pos = xdr_getpos(&xdrs);
  xdr_destroy(&xdrs);

  if (pos > INT_MAX)
    return -1;

  return (int)pos;
}

int lauberhorn_oncrpc_unmarshal(struct lauberhorn_oncrpc_schema *schema,
                                lauberhorn_msg_t out_msg, uint8_t *in_buf,
                                size_t in_bytes) {
  XDR xdrs;
  xdrmem_create(&xdrs, (char *)in_buf, in_bytes, XDR_DECODE);

  return schema->call_func(&xdrs, out_msg);
}
