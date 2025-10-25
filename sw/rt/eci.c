#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "ioctl.h"
#include "lauberhorn.h"

#include "eci/config.h"

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

// Register service handler
int lauberhorn_reg_srv(lauberhorn_t *ctx, lauberhorn_handler_t func, void *data,
                       int prog_num, int prog_ver, int proc_num,
                       uint16_t listen_port, lauberhorn_schema_t *schema) {
  lauberhorn_reg_srv_t cmd;
  int err;

  cmd.prog_num = prog_num;
  cmd.prog_ver = prog_ver;
  cmd.proc_num = proc_num;
  cmd.port = listen_port;
  cmd.func_ptr = func;

  // TODO: register schema with the RX unmarshaller
  // (void *)schema;

  err = ioctl(ctx->fd, LAUBERHORN_IOCTL_REG_SRV, &cmd);
  if (err) {
    PERROR("register service");
    return -1;
  }

  return cmd.id;
}

int lauberhorn_dereg_srv(lauberhorn_t *ctx, int srv_id) {
  int err;

  err = ioctl(ctx->fd, LAUBERHORN_IOCTL_DEREG_SRV, &srv_id);
  if (err) {
    PERROR("deregister service");
    return -1;
  }

  return 0;
}

struct lauberhorn_worker_args {
  lauberhorn_t *ctx;
  int worker_id;

  lauberhorn_core_state_t dp;
};
static void lauberhorn_worker_loop(struct lauberhorn_worker_args *args) {
  LOG("worker %d starting", args->worker_id);
}

static int next_worker = 0;

// Create worker thread (spins and runs handler function)
int lauberhorn_create_worker(lauberhorn_t *ctx) {
  struct lauberhorn_worker_args *args =
      calloc(sizeof(struct lauberhorn_worker_args), 1);

  args->worker_id = next_worker++;
  args->ctx = ctx;

  // Parity bits are shared with the kernel
  args->dp.rx_next_cl = &ctx->parity_page[args->worker_id * 2];
  args->dp.tx_next_cl = &ctx->parity_page[args->worker_id * 2 + 1];
  *args->dp.rx_next_cl = *args->dp.tx_next_cl = 0;

  args->dp.rx_overflow_buf_size = args->dp.tx_overflow_buf_size =
      LAUBERHORN_MTU;
  args->dp.rx_overflow_buf = malloc(LAUBERHORN_MTU);
  args->dp.tx_overflow_buf = malloc(LAUBERHORN_MTU);
}