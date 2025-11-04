/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <rpc/rpc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "ioctl.h"
#include "lauberhorn.h"
#include "oncrpc.h"

#include "eci/config.h"
#include "eci/core.h"

#define LAUBERHORN_DEV_PATH "/dev/lauberhorn"

#define LOG(fmt, ...) printf("[lauberhorn rt] " fmt "\n", ##__VA_ARGS__)
#define PERROR(msg) perror("[lauberhorn rt] " msg)

static int page_size;

int lauberhorn_init(lauberhorn_t *ctx) {
  page_size = getpagesize();

  LOG("initializing");

  ctx->fd = open(LAUBERHORN_DEV_PATH, O_RDWR);
  if (ctx->fd < 0) {
    PERROR("open device file");
    goto out;
  }

  // Map parity page
  ctx->parity_page =
      mmap(NULL, page_size, PROT_READ | PROT_WRITE, MAP_PRIVATE, ctx->fd, 0);
  if (ctx->parity_page == MAP_FAILED) {
    PERROR("map parity page");
    goto close_fd;
  }

  LOG("app initialized");
  return 0;

close_fd:
  close(ctx->fd);
out:
  return -1;
}

void lauberhorn_fini(lauberhorn_t *ctx) {
  munmap(ctx->parity_page, page_size);
  close(ctx->fd);
}

struct schema_reg {
  lauberhorn_schema_t *schema;
  bool enabled;
  int srv_id; // as returned from the kernel

  // used to deregister from the portmapper
  int prog_num, prog_ver;
};
static struct schema_reg registered_schemas[LAUBERHORN_NUM_SERVICES];
static int next_schema = 0;

struct lauberhorn_hw_handler {
  lauberhorn_handler_t func;
  void *data;
  struct schema_reg *sreg;
};

// Register service handler
int lauberhorn_reg_srv(lauberhorn_t *ctx, lauberhorn_handler_t func, void *data,
                       int prog_num, int prog_ver, int proc_num,
                       uint16_t listen_port, lauberhorn_schema_t *schema) {
  lauberhorn_reg_srv_t cmd;
  int err;
  struct lauberhorn_hw_handler *hw_func_ptr =
      malloc(sizeof(struct lauberhorn_hw_handler));

  hw_func_ptr->func = func;
  hw_func_ptr->data = data;
  hw_func_ptr->sreg = &registered_schemas[next_schema++];

  cmd.prog_num = prog_num;
  cmd.prog_ver = prog_ver;
  cmd.proc_num = proc_num;
  cmd.port = listen_port;
  cmd.func_ptr = hw_func_ptr;

  err = ioctl(ctx->fd, LAUBERHORN_IOCTL_REG_SRV, &cmd);
  if (err) {
    PERROR("register service");
    return -1;
  }

  // Record list of schemas, so that every worker thread can allocate
  // their own message buffers
  *hw_func_ptr->sreg = (struct schema_reg){.schema = schema,
                                           .srv_id = cmd.id,
                                           .prog_num = prog_num,
                                           .prog_ver = prog_ver,
                                           .enabled = true};

  // Register this service with the local port mapper, so that the remote
  // client can find it automatically
  err = pmap_set(prog_num, prog_ver, IPPROTO_UDP, listen_port);
  if (err) {
    LOG("failed to register with portmapper");
  }

  return cmd.id;
}

int lauberhorn_dereg_srv(lauberhorn_t *ctx, int srv_id) {
  int err, i;
  struct schema_reg *sreg = NULL;

  // Find the registered schema
  for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
    if (registered_schemas[i].enabled &&
        registered_schemas[i].srv_id == srv_id) {
      sreg = &registered_schemas[i];
      break;
    }
  }
  if (!sreg) {
    LOG("failed to find registered schema");
    return -1;
  }

  sreg->enabled = false;

  // Deregister with the portmapper
  err = pmap_unset(sreg->prog_num, sreg->prog_ver);
  if (err) {
    LOG("failed to deregister with portmapper");
  }

  err = ioctl(ctx->fd, LAUBERHORN_IOCTL_DEREG_SRV, &srv_id);
  if (err) {
    PERROR("deregister service");
    return -1;
  }

  return 0;
}

struct lauberhorn_worker {
  lauberhorn_t *ctx;
  int worker_id;

  lauberhorn_core_state_t dp;
  void *dp_base;

  pthread_t thread;
  lauberhorn_msg_t msg_bufs[LAUBERHORN_NUM_SERVICES];
};
static void *lauberhorn_worker_loop(void *arg) {
  lauberhorn_worker_t w = arg;

  // Datapath base for this worker thread
  void *dp_base;
  int dp_size = LAUBERHORN_ECI_CORE_OFFSET, i, err, to_send;
  int dp_offset = dp_size * w->worker_id + page_size;

  lauberhorn_msg_t msg;
  lauberhorn_pkt_desc_t desc;

  struct lauberhorn_oncrpc_schema *schema;
  struct lauberhorn_hw_handler *hw_handler;

  LOG("worker %d starting", w->worker_id);

  // Map datapath region
  dp_base = mmap(NULL, dp_size, PROT_READ | PROT_WRITE, MAP_PRIVATE, w->ctx->fd,
                 dp_offset);
  if (dp_base == MAP_FAILED) {
    PERROR("map datapath page");
    return (void *)-1;
  }
  w->dp_base = dp_base;

  // Allocate a request buffer for each schema
  for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
    if (registered_schemas[i].enabled) {
      schema = registered_schemas[i].schema;
      w->msg_bufs[i] = lauberhorn_oncrpc_req_alloc(schema);
    }
  }

  // Main loop
  while (true) {
    // Receive request from datapath
    bool got_req = core_eci_rx(dp_base, &w->dp, &desc);
    if (!got_req)
      continue;
    assert(desc.type == TY_ONCRPC_CALL);
    hw_handler = desc.oncrpc_server.func_ptr;
    schema = hw_handler->sreg->schema;
    msg = w->msg_bufs[hw_handler->sreg - registered_schemas];

    // Unmarshal request
    err = lauberhorn_oncrpc_unmarshal(schema, msg, w->dp.rx_buf,
                                      desc.payload_len);
    if (err) {
      LOG("failed to unmarshal request, skipping");
      continue;
    }

    // Call handler
    msg = hw_handler->func(hw_handler->data, msg, desc.oncrpc_server.xid);

    // Marshal response
    err =
        lauberhorn_oncrpc_marshal(schema, w->dp.tx_buf, w->dp.tx_buf_size, msg);
    if (err < 0) {
      LOG("failed to marshal request, skipping");
      continue;
    }
    to_send = err;

    // Send marshalled response
    desc.type = TY_ONCRPC_REPLY;
    // xid and func_ptr stays the same
    desc.payload_len = to_send;
    core_eci_tx(dp_base, &w->dp, &desc);
  }
}

static int next_worker = 0;

// Create worker thread (spins and runs handler function)
lauberhorn_worker_t lauberhorn_create_worker(lauberhorn_t *ctx) {
  lauberhorn_worker_t w = calloc(sizeof(struct lauberhorn_worker), 1);
  int err;

  w->worker_id = next_worker++;
  w->ctx = ctx;

  // Parity bits are shared with the kernel
  w->dp.rx_next_cl = &ctx->parity_page[w->worker_id * 2];
  w->dp.tx_next_cl = &ctx->parity_page[w->worker_id * 2 + 1];
  *w->dp.rx_next_cl = *w->dp.tx_next_cl = 0;

  w->dp.rx_buf_size = w->dp.tx_buf_size = LAUBERHORN_MTU;
  w->dp.rx_buf = malloc(LAUBERHORN_MTU);
  w->dp.tx_buf = malloc(LAUBERHORN_MTU);

  // Create thread
  err = pthread_create(&w->thread, NULL, lauberhorn_worker_loop, w);
  if (err != 0) {
    errno = err;
    PERROR("create thread");
    free(w);
    return NULL;
  }

  return w;
}

void lauberhorn_join_worker(lauberhorn_t *ctx, lauberhorn_worker_t w) {
  void *res;
  int err, i;

  // Join thread
  err = pthread_join(w->thread, &res);
  if (err != 0) {
    errno = err;
    PERROR("join thread");
    return;
  }

  // Unmap our copy of the datapath base
  err = munmap(w->dp_base, LAUBERHORN_ECI_CORE_OFFSET);
  if (err != 0) {
    PERROR("unmap datapath");
    return;
  }

  // Free all buffers
  for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
    if (registered_schemas[i].enabled) {
      lauberhorn_oncrpc_req_free(registered_schemas[i].schema, w->msg_bufs[i]);
    }
  }

  free(w->dp.rx_buf);
  free(w->dp.tx_buf);
  free(w);
}