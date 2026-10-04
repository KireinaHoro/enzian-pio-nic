/* Controlled worker-local syscall probe. Link against the matched DUT runtime.
 * Port 22346, nested-validation RPC IDs; words are network byte order.
 * Request: id, mode (0=noop, 1=getpid syscall, 2=mprotect), spin_us, reserved.
 * Reply: id, mode, Linux CPU, errno, operation start/ns and end/ns (u64),
 *        restore end/ns (u64), getpid result (u64). No deliberate handoff.
 */
#define _GNU_SOURCE
#include "lauberhorn.h"
#include <arpa/inet.h>
#include <endian.h>
#include <errno.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
static unsigned char *page;
static size_t page_size;
static _Thread_local unsigned char message[48];
static void worker_init(int id) {
  (void)id;
  memset(message, 0, sizeof(message));
}
static uint64_t stamp(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC_RAW, &t);
  return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
static uint32_t get32(void *p) {
  uint32_t v;
  memcpy(&v, p, 4);
  return ntohl(v);
}
static void put32(void *p, uint32_t v) {
  v = htonl(v);
  memcpy(p, &v, 4);
}
static void put64(void *p, uint64_t v) {
  v = htobe64(v);
  memcpy(p, &v, 8);
}
static void *alloc_msg(rpc_alloc_kind_t k, void *p) {
  (void)k;
  (void)p;
  return message;
}
static void free_msg(void *p, rpc_free_kind_t k, void *v) {
  (void)p;
  (void)k;
  (void)v;
}
static int decode(void *in, void *out, size_t n, void *p) {
  (void)p;
  if (n < 16)
    return -1;
  memcpy(out, in, 16);
  return 0;
}
static int encode(void *in, void *out, size_t n, size_t cap, void *p) {
  (void)n;
  (void)p;
  if (cap < 48)
    return -1;
  memcpy(out, in, 48);
  return 48;
}
static lauberhorn_outcome_t handle(void *p, void *arg,
                                   lauberhorn_request_context_t token) {
  (void)p;
  (void)token;
  unsigned char *m = arg;
  uint32_t mode = get32(m + 4), spin = get32(m + 8);
  if (mode > 2 || spin > 10000)
    return lauberhorn_fail(LAUBERHORN_RPC_SYSTEM_ERR);
  uint64_t deadline = stamp() + (uint64_t)spin * 1000;
  while (stamp() < deadline) {
  }
  int cpu = sched_getcpu(), err = 0;
  long value = 0;
  uint64_t a = stamp();
  if (mode == 1)
    value = syscall(SYS_getpid);
  if (mode == 2 && mprotect(page, page_size, PROT_READ))
    err = errno;
  uint64_t b = stamp();
  if (mode == 2 && mprotect(page, page_size, PROT_READ | PROT_WRITE))
    err = errno;
  uint64_t c = stamp();
  put32(m + 8, cpu);
  put32(m + 12, err);
  put64(m + 16, a);
  put64(m + 24, b);
  put64(m + 32, c);
  put64(m + 40, value);
  return lauberhorn_reply(m, 48, NULL);
}
int main(void) {
  page_size = sysconf(_SC_PAGESIZE);
  page = mmap(NULL, page_size, PROT_READ | PROT_WRITE,
              MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (page == MAP_FAILED)
    return 1;
  memset(page, 0x5a, page_size);
  static rpc_ops_t ops = {.alloc = alloc_msg,
                          .free = free_msg,
                          .unmarshal_call = decode,
                          .marshal_resp = encode};
  rpc_codec_t codec = {.ops = &ops};
  lauberhorn_t ctx;
  if (lauberhorn_init(&ctx) ||
      lauberhorn_reg_srv(&ctx, (lauberhorn_handler_t){.func = handle},
                         0x10234567, 0x12345678, 0x23456789, 22346,
                         &codec) < 0 ||
      lauberhorn_set_worker_count(&ctx, 1))
    return 2;
  lauberhorn_worker_t w = lauberhorn_create_worker(&ctx, worker_init, NULL);
  if (!w)
    return 3;
  puts("GSYNC_PROBE_READY");
  fflush(stdout);
  lauberhorn_join_worker(&ctx, w);
  return 0;
}
