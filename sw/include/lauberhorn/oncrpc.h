/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#ifndef LAUBERHORN_ONCRPC_H
#define LAUBERHORN_ONCRPC_H

#include <rpc/xdr.h>
#include <stdbool.h>
#include <stdint.h>

typedef uint64_t (*lauberhorn_trace_request_id_t)(void *req);
typedef bool (*lauberhorn_trace_check_t)(void *req, void *resp);

struct lauberhorn_oncrpc_schema {
  xdrproc_t call_func;
  xdrproc_t resp_func;

  // used to allocate the buffer for incoming messages
  size_t call_size;

  // Optional per-request trace helpers used by the runtime CSV emitter.
  lauberhorn_trace_request_id_t trace_request_id;
  lauberhorn_trace_check_t trace_check;
};

// User-facing functions to create and manipulate an ONC-RPC schema

#endif // LAUBERHORN_ONCRPC_H
