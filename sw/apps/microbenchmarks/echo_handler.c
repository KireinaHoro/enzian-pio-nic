/* Compile this handler once per backend with the same optimization settings.
 * Keeping it out of transport translation units prevents backend-specific
 * constant folding of its dispatch/control branches. */
#define _GNU_SOURCE
#include "bench.h"
#include "echo_handler.h"
static thread_local struct activity_counter *my_activity;
static thread_local uint64_t my_calls;
// Handlers execute on multiple worker threads.
thread_local bench_resp resp;
static unsigned flush_spin_us;
static unsigned loopback_size;
static uint64_t raw_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC_RAW, &t);
  return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}

lauberhorn_outcome_t bench_handler(void *data, lauberhorn_msg_t req,
                                      lauberhorn_request_context_t context) {
  if (my_activity) {
    if (!my_calls) {
      my_activity->cpu = sched_getcpu();
      my_activity->tid = syscall(SYS_gettid);
    }
    atomic_store_explicit(&my_activity->calls, ++my_calls, memory_order_release);
  }
  if (loopback_size)
    return lauberhorn_reply(req, loopback_size, NULL);
  const struct bench_def *def = data;
  if (def->echo)
    return lauberhorn_reply(req, def->body_size, NULL);
  bench_call *call = req;
  (void)context;
  lauberhorn_trace_request_id(bench_request_id(req));

  if (flush_spin_us) {
    uint64_t end = raw_ns() + (uint64_t)flush_spin_us * 1000;
    while (raw_ns() < end) { }
  }
  resp.request_id = call->request_id;
  resp.result = def->compute(call->a, call->b);

  lauberhorn_trace_result(def->trace_check(req, &resp));
  return lauberhorn_reply(&resp, sizeof(resp), NULL);
}

/* Startup-only configuration keeps transport details out of the handler. */
void bench_handler_configure(unsigned loopback_bytes, unsigned spin_us) {
  loopback_size = loopback_bytes;
  flush_spin_us = spin_us;
}
void bench_handler_activity(struct activity_counter *counter) {
  my_activity = counter;
  my_calls = 0;
}
