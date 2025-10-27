/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#ifndef LAUBERHORN_H
#define LAUBERHORN_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "lauberhorn/oncrpc.h"

typedef struct {
  int fd; // to /dev/lauberhorn

  uint8_t *parity_page;
} lauberhorn_t;

typedef struct {
  enum {
    SCHEMA_ONCRPC,
  } ty;
  union {
    struct lauberhorn_oncrpc_schema oncrpc;
  };
} lauberhorn_schema_t;

// Unmarshalled XDR data.  To be marshalled/unmarshalled according to
// the lauberhorn_schema_t on initialization
typedef void *lauberhorn_msg_t;

// (app data, unmarshalled request, xid) -> unmarshalled response
typedef lauberhorn_msg_t (*lauberhorn_handler_t)(void *, lauberhorn_msg_t, int);

// Register application
int lauberhorn_init(lauberhorn_t *ctx);
void lauberhorn_fini(lauberhorn_t *ctx);

// Register service handler
int lauberhorn_reg_srv(lauberhorn_t *ctx, lauberhorn_handler_t func, void *data,
                       int prog_num, int prog_ver, int proc_num,
                       uint16_t listen_port, lauberhorn_schema_t *schema);

int lauberhorn_dereg_srv(lauberhorn_t *ctx, int srv_id);

// Create worker thread (spins and runs handler function)
struct lauberhorn_worker;
typedef struct lauberhorn_worker *lauberhorn_worker_t;

lauberhorn_worker_t lauberhorn_create_worker(lauberhorn_t *ctx);
void lauberhorn_join_worker(lauberhorn_t *ctx, lauberhorn_worker_t);

#endif // LAUBERHORN_H