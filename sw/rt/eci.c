/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <pthread.h>
#include <rpc/rpc.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include "ioctl.h"
#include "lauberhorn.h"
#include "oncrpc.h"

#include "config.h"
#include "eci/core.h"

#define LAUBERHORN_DEV_PATH "/dev/lauberhorn"

#define LOG(fmt, ...)                                                           \
  do {                                                                          \
    if (rt_verbose)                                                             \
      fprintf(stderr, "[lauberhorn rt] " fmt "\n", ##__VA_ARGS__);             \
  } while (0)
#define STATUS(fmt, ...)                                                        \
  fprintf(stderr, "[lauberhorn rt] " fmt "\n", ##__VA_ARGS__)
#define PERROR(msg) perror("[lauberhorn rt] " msg)

static int page_size;
static volatile sig_atomic_t is_running;
static int fd;
static bool rt_verbose;

static FILE *server_trace_csv;
static pthread_mutex_t server_trace_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t server_trace_clock_overhead_ns;

struct server_trace_row {
  uint64_t request_id;
  int xid;
  int worker_id;
  int ok;
  size_t request_bytes;
  size_t response_bytes;
  uint64_t rx_enter_ns;
  uint64_t rx_exit_ns;
  uint64_t unmarshal_enter_ns;
  uint64_t unmarshal_exit_ns;
  uint64_t handler_enter_ns;
  uint64_t handler_exit_ns;
  uint64_t marshal_enter_ns;
  uint64_t marshal_exit_ns;
  uint64_t tx_enter_ns;
  uint64_t tx_exit_ns;
};

static uint64_t trace_now_ns(void) {
  struct timespec ts;
  if (clock_gettime(CLOCK_MONOTONIC_RAW, &ts) != 0) {
    PERROR("clock_gettime");
    abort();
  }
  return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static uint64_t calibrate_clock_overhead(void) {
  uint64_t best = UINT64_MAX;
  uint64_t prev = trace_now_ns();

  for (int i = 0; i < 10000; ++i) {
    uint64_t cur = trace_now_ns();
    uint64_t delta = cur - prev;
    if (delta != 0 && delta < best)
      best = delta;
    prev = cur;
  }

  return best == UINT64_MAX ? 0 : best;
}

static void server_trace_open(void) {
  const char *path = getenv("LAUBERHORN_SERVER_TRACE_CSV");
  if (!path || !path[0])
    path = getenv("ADDER_SERVER_TRACE_CSV");
  if (!path || !path[0])
    return;

  server_trace_csv = fopen(path, "w");
  if (!server_trace_csv) {
    fprintf(stderr, "[lauberhorn rt] failed to open server trace CSV %s: %s\n",
            path, strerror(errno));
    return;
  }

  setvbuf(server_trace_csv, NULL, _IOLBF, 0);
  server_trace_clock_overhead_ns = calibrate_clock_overhead();
  fprintf(server_trace_csv,
          "request_id,xid,worker_id,ok,request_bytes,response_bytes,"
          "server_rx_enter_ns,server_rx_exit_ns,"
          "server_unmarshal_enter_ns,server_unmarshal_exit_ns,"
          "server_handler_enter_ns,server_handler_exit_ns,"
          "server_marshal_enter_ns,server_marshal_exit_ns,"
          "server_tx_enter_ns,server_tx_exit_ns,"
          "timestamp_overhead_ns,timestamp_call_count\n");
}

static void server_trace_close(void) {
  if (!server_trace_csv)
    return;

  fclose(server_trace_csv);
  server_trace_csv = NULL;
}

static void server_trace_write(const struct server_trace_row *row) {
  if (!server_trace_csv)
    return;

  pthread_mutex_lock(&server_trace_lock);
  fprintf(server_trace_csv,
          "%" PRIu64 ",%d,%d,%d,%zu,%zu,"
          "%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ","
          "%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ","
          "%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%u\n",
          row->request_id, row->xid, row->worker_id, row->ok,
          row->request_bytes, row->response_bytes, row->rx_enter_ns,
          row->rx_exit_ns, row->unmarshal_enter_ns, row->unmarshal_exit_ns,
          row->handler_enter_ns, row->handler_exit_ns, row->marshal_enter_ns,
          row->marshal_exit_ns, row->tx_enter_ns, row->tx_exit_ns,
          server_trace_clock_overhead_ns, 10u);
  pthread_mutex_unlock(&server_trace_lock);
}

static void sigint_handler(int signo) {
  int err;

  assert(signo == SIGINT);

  // tell all threads to stop soon
  is_running = 0;

  // ioctl to resume all workers for cleanup
  err = ioctl(fd, LAUBERHORN_IOCTL_WAKE_ALL_WORKERS, NULL);
  if (err) {
    PERROR("wake all workers");
  }
}

int lauberhorn_init(lauberhorn_t *ctx) {
  struct sigaction sa = {
      .sa_handler = sigint_handler,
      .sa_flags = SA_RESTART, // resume join in main
  };
  int err;

  page_size = getpagesize();
  rt_verbose = getenv("LAUBERHORN_RT_VERBOSE") != NULL;

  STATUS("initializing");
  server_trace_open();

  fd = ctx->fd = open(LAUBERHORN_DEV_PATH, O_RDWR);
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

  // Register SIGINT handler
  err = sigaction(SIGINT, &sa, NULL);
  if (err) {
    PERROR("handle SIGINT");
    goto out;
  }

  STATUS("app initialized");
  return 0;

close_fd:
  close(ctx->fd);
out:
  server_trace_close();
  return -1;
}

void lauberhorn_fini(lauberhorn_t *ctx) {
  munmap(ctx->parity_page, page_size);
  close(ctx->fd);
  server_trace_close();
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
  if (err != 1) {
    LOG("failed to register with portmapper: err=%d", err);
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
  if (err != 1) {
    LOG("failed to deregister with portmapper: err=%d", err);
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

  lauberhorn_user_cb_t init, fini;

  lauberhorn_core_state_t dp;
  void *dp_base;
  uint8_t *tx_buf;
  size_t tx_buf_size;

  pthread_t thread;
  lauberhorn_msg_t msg_bufs[LAUBERHORN_NUM_SERVICES];
};
static void *lauberhorn_worker_loop(void *arg) {
  lauberhorn_worker_t w = arg;

  // Datapath base for this worker thread
  void *dp_base;
  int dp_size = LAUBERHORN_ECI_CORE_OFFSET, i, to_send;
  int dp_offset = dp_size * w->worker_id + page_size;
  ssize_t err;

  lauberhorn_msg_t msg;
  lauberhorn_pkt_desc_t desc;

  struct lauberhorn_oncrpc_schema *schema;
  struct lauberhorn_hw_handler *hw_handler;

  sigset_t sigint_mask;

  STATUS("worker %d: starting", w->worker_id);

  // Map datapath region
  dp_base = mmap(NULL, dp_size, PROT_READ | PROT_WRITE, MAP_SHARED, w->ctx->fd,
                 dp_offset);
  if (dp_base == MAP_FAILED) {
    PERROR("map datapath page");
    err = errno;
    goto out;
  }
  w->dp_base = dp_base;

  // Allocate a request buffer for each schema
  for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
    if (registered_schemas[i].enabled) {
      schema = registered_schemas[i].schema;
      w->msg_bufs[i] = lauberhorn_oncrpc_req_alloc(schema);
    }
  }

  // Call user init function
  w->init(w->worker_id);

  // Mask SIGINT, to be handled in the main thread
  sigemptyset(&sigint_mask);
  sigaddset(&sigint_mask, SIGINT);
  err = pthread_sigmask(SIG_BLOCK, &sigint_mask, NULL);
  if (err) {
    goto out;
  }

  // Main loop
  while (is_running) {
    struct server_trace_row trace_row = {
        .request_id = UINT64_MAX,
        .worker_id = w->worker_id,
        .ok = -1,
    };

    // Receive request from datapath
    if (server_trace_csv)
      trace_row.rx_enter_ns = trace_now_ns();
    bool got_req = core_eci_rx(dp_base, &w->dp, &desc);
    if (server_trace_csv)
      trace_row.rx_exit_ns = trace_now_ns();
    if (!got_req)
      continue;
    assert(desc.type == TY_ONCRPC_CALL);
    hw_handler = desc.oncrpc_server.func_ptr;
    schema = hw_handler->sreg->schema;
    msg = w->msg_bufs[hw_handler->sreg - registered_schemas];
    trace_row.xid = desc.oncrpc_server.xid;
    trace_row.request_bytes = desc.payload_len;

    // Unmarshal request
    if (server_trace_csv)
      trace_row.unmarshal_enter_ns = trace_now_ns();
    err = lauberhorn_oncrpc_unmarshal(schema, msg, w->dp.rx_buf,
                                      desc.payload_len);
    if (server_trace_csv)
      trace_row.unmarshal_exit_ns = trace_now_ns();
    if (err != 1) {
      LOG("failed to unmarshal request, skipping");
      continue;
    }
    if (server_trace_csv && schema->trace_request_id)
      trace_row.request_id = schema->trace_request_id(msg);

    // Call handler
    if (server_trace_csv)
      trace_row.handler_enter_ns = trace_now_ns();
    msg = hw_handler->func(hw_handler->data, msg, desc.oncrpc_server.xid);
    if (server_trace_csv)
      trace_row.handler_exit_ns = trace_now_ns();
    if (server_trace_csv && schema->trace_check)
      trace_row.ok = schema->trace_check(w->msg_bufs[hw_handler->sreg -
                                                     registered_schemas],
                                         msg)
                         ? 1
                         : 0;

    // Marshal response
    if (server_trace_csv)
      trace_row.marshal_enter_ns = trace_now_ns();
    err = lauberhorn_oncrpc_marshal(schema, w->tx_buf, w->tx_buf_size, msg);
    if (server_trace_csv)
      trace_row.marshal_exit_ns = trace_now_ns();
    if (err < 0) {
      LOG("failed to marshal response, skipping");
      continue;
    }
    to_send = err;
    trace_row.response_bytes = to_send;

    // Send marshalled response
    desc.type = TY_ONCRPC_REPLY;
    // xid and func_ptr stays the same
    desc.payload_len = to_send;
    if (server_trace_csv)
      trace_row.tx_enter_ns = trace_now_ns();
    core_eci_tx(dp_base, &w->dp, &desc);
    if (server_trace_csv)
      trace_row.tx_exit_ns = trace_now_ns();
    server_trace_write(&trace_row);
  }

  STATUS("worker %d: requested to exit, cleaning up", w->worker_id);
  w->fini(w->worker_id);

  // further cleanup happen in lauberhorn_join_worker on the
  // main thread

  return NULL;

out:
  return (void *)err;
}

static int next_worker = 0;

// Create worker thread (spins and runs handler function)
lauberhorn_worker_t lauberhorn_create_worker(lauberhorn_t *ctx,
                                             lauberhorn_user_cb_t init,
                                             lauberhorn_user_cb_t fini) {
  lauberhorn_worker_t w = calloc(sizeof(struct lauberhorn_worker), 1);
  int err;

  w->worker_id = next_worker++;
  w->ctx = ctx;
  w->init = init;
  w->fini = fini;

  is_running = 1;

  // Parity bits are shared with the kernel
  w->dp.rx_next_cl = &ctx->parity_page[w->worker_id * 2];
  w->dp.tx_next_cl = &ctx->parity_page[w->worker_id * 2 + 1];
  *w->dp.rx_next_cl = *w->dp.tx_next_cl = 0;

  w->dp.rx_buf_size = w->tx_buf_size = LAUBERHORN_MTU;
  w->dp.rx_buf = malloc(LAUBERHORN_MTU);
  w->tx_buf = malloc(LAUBERHORN_MTU);
  w->dp.tx_buf = w->tx_buf;

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
  if (res) {
    STATUS("worker thread returned %ld", (ssize_t)res);
  }

  // Unmap the datapath base
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
  free(w->tx_buf);
  free(w);
}
