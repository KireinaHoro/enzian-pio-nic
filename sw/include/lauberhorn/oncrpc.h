/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#ifndef LAUBERHORN_ONCRPC_H
#define LAUBERHORN_ONCRPC_H

#include <rpc/xdr.h>

struct lauberhorn_oncrpc_schema {
  xdrproc_t call_func;
  xdrproc_t resp_func;

  // used to allocate the buffer for incoming messages
  size_t call_size;
};

// User-facing functions to create and manipulate an ONC-RPC schema

#endif // LAUBERHORN_ONCRPC_H