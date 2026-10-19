/* Existing microbenchmark handler shared by native and DPDK backends. */
#ifndef LH_BENCH_ECHO_HANDLER_H
#define LH_BENCH_ECHO_HANDLER_H
#include <sched.h>
#include <stdatomic.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <threads.h>
#include "lauberhorn.h"
#include "lauberhorn/trace.h"
#include "lauberhorn/oncrpc.h"
#include <time.h>
struct activity_counter {
  _Atomic uint64_t calls;
  int32_t cpu, tid;
  unsigned char padding[48];
};
_Static_assert(sizeof(struct activity_counter) == 64, "activity counter layout");
struct bench_def {
  const char *name;
  int prog;
  int vers;
  int proc;
  uint16_t port;
  int (*compute)(int, int);
  lauberhorn_trace_check_t trace_check;
  bool echo;
  size_t body_size;
  xdrproc_t xdr;
};

static inline uint64_t bench_request_id(void *req) {
  bench_call *call = req;
  return call->request_id;
}

void bench_handler_configure(unsigned loopback_bytes, unsigned spin_us);
void bench_handler_activity(struct activity_counter *);
lauberhorn_outcome_t bench_handler(void *, lauberhorn_msg_t, lauberhorn_request_context_t);
#endif
