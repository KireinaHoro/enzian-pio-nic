#define _GNU_SOURCE
#include <fcntl.h>
#include <sched.h>
#include <stdatomic.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "lauberhorn/trace.h"
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <threads.h>
#include <time.h>

#include "lauberhorn.h"
#include "lauberhorn/oncrpc.h"

#include "bench.h"
#include "echo_registry.h"

#define DEFAULT_WORKERS 4

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

// Handlers execute on multiple worker threads.
thread_local bench_resp resp;
/* Optional loaded-case activity counters. Each worker owns one cache line;
 * the controller snapshots this file between cohorts, without trace recording. */
struct activity_counter {
  _Atomic uint64_t calls;
  int32_t cpu, tid;
  unsigned char padding[48];
};
_Static_assert(sizeof(struct activity_counter) == 64, "activity counter layout");
static struct activity_counter *activity;
static thread_local struct activity_counter *my_activity;
static thread_local uint64_t my_calls;
static void worker_init(int index) {
  if (activity) my_activity = &activity[index];
}
static unsigned flush_spin_us;
static unsigned loopback_size;
static bool_t xdr_loopback(XDR *xdr, void *bytes) {
  return xdr_opaque(xdr, bytes, loopback_size);
}
static const struct bench_def loopback_def = {
    .name = "loopback", .prog = 0x23451113, .vers = 1, .proc = 1,
    .port = 12345,
};
static uint64_t raw_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC_RAW, &t);
  return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}

static int compute_add(int a, int b) { return a + b; }

static int compute_mul(int a, int b) { return a * b; }

static uint64_t bench_request_id(void *req) {
  bench_call *call = req;
  return call->request_id;
}

static bool add_check(void *req, void *resp_msg) {
  bench_call *call = req;
  bench_resp *result = resp_msg;

  return result->request_id == call->request_id &&
         result->result == compute_add(call->a, call->b);
}

static bool mul_check(void *req, void *resp_msg) {
  bench_call *call = req;
  bench_resp *result = resp_msg;

  return result->request_id == call->request_id &&
         result->result == compute_mul(call->a, call->b);
}

#define ECHO_DEF(label, size, procedure, codec) { \
    .name = #label, .prog = ECHO_PROGRAM, .vers = ECHO_VERSION, \
    .proc = procedure, .port = 12345, .echo = true, .body_size = size, \
    .xdr = (xdrproc_t)codec },
static const struct bench_def benches[] = {
    ECHO_VARIANTS(ECHO_DEF)
    {
        .name = "add",
        .prog = ADD_PROG,
        .vers = ADD_VERS,
        .proc = ADD,
        .port = 12345,
        .compute = compute_add,
        .trace_check = add_check,
    },
    {
        .name = "mul",
        .prog = MUL_PROG,
        .vers = MUL_VERS,
        .proc = MUL,
        .port = 12346,
        .compute = compute_mul,
        .trace_check = mul_check,
    },
};

static lauberhorn_outcome_t bench_handler(void *data, lauberhorn_msg_t req,
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

static const struct bench_def *find_bench(const char *name) {
  size_t i;
  const unsigned sizes[] = {8, 64, 128, 512, 1432};
  for (i = 0; i < sizeof(sizes) / sizeof(sizes[0]); ++i) {
    char label[32];
    snprintf(label, sizeof(label), "loopback%u", sizes[i]);
    if (!strcmp(name, label)) {
      loopback_size = sizes[i];
      return &loopback_def;
    }
  }

  for (i = 0; i < sizeof(benches) / sizeof(benches[0]); ++i) {
    if (strcmp(name, benches[i].name) == 0)
      return &benches[i];
  }

  return NULL;
}

static int parse_positive_int(const char *text, const char *name) {
  char *end = NULL;
  long value;

  errno = 0;
  value = strtol(text, &end, 0);
  if (errno || !text[0] || *end || value <= 0 || value > INT_MAX) {
    fprintf(stderr, "invalid %s: %s\n", name, text);
    exit(1);
  }

  return (int)value;
}

static void usage(const char *argv0) {
  fprintf(stderr, "usage: %s add|mul|echo{0,8,128,1432}|loopback{8,64,128,512,1432} [workers=%d] [server_trace.csv]\n",
          argv0, DEFAULT_WORKERS);
}

int main(int argc, char *argv[]) {
  const struct bench_def *def;
  int err, srv_id = -1, i;
  int workers_count = DEFAULT_WORKERS;
  lauberhorn_t ctx;
  xdr_adapter_t adapter = {
      .call_size = sizeof(bench_call),
      .resp_size = sizeof(bench_resp),
      .call_xdrproc = (xdrproc_t)xdr_bench_call,
      .resp_xdrproc = (xdrproc_t)xdr_bench_resp,
  };
  rpc_codec_t codec = oncrpc_codec(&adapter);
  codec.response_inline = true; /* bench_resp is two XDR uint32s (8 bytes). */
  lauberhorn_worker_t *workers;

  if (argc < 2 || argc > 4) {
    usage(argv[0]);
    return EXIT_FAILURE;
  }

  def = find_bench(argv[1]);
  if (!def) {
    usage(argv[0]);
    return EXIT_FAILURE;
  }

  if (def->echo) {
    /* A void codec still gets a non-null allocation; its wire body is empty. */
    adapter.call_size = adapter.resp_size = def->body_size ? def->body_size : 1;
    adapter.call_xdrproc = adapter.resp_xdrproc = def->xdr;
    codec.response_inline = def->body_size <= 64;
  }
  if (loopback_size) {
    adapter.call_size = adapter.resp_size = loopback_size;
    adapter.call_xdrproc = adapter.resp_xdrproc = (xdrproc_t)xdr_loopback;
    codec.response_inline = loopback_size <= 64;
  }

  const char *spin = getenv("LH_BENCH_FLUSH_SPIN_US");
  if (spin && *spin) {
    flush_spin_us = parse_positive_int(spin, "LH_BENCH_FLUSH_SPIN_US");
    if (strcmp(def->name, "mul") || flush_spin_us > 10000) {
      fprintf(stderr, "flush spin requires mul and at most 10000 us\n");
      return EXIT_FAILURE;
    }
  }
  if (argc >= 3)
    workers_count = parse_positive_int(argv[2], "workers");

  const char *activity_path = getenv("LH_BENCH_ACTIVITY_FILE");
  if (activity_path) {
    size_t bytes = workers_count * sizeof(*activity);
    int fd = open(activity_path, O_RDWR | O_CREAT | O_EXCL, 0600);
    if (fd < 0 || ftruncate(fd, bytes)) { perror("activity file"); return EXIT_FAILURE; }
    activity = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (activity == MAP_FAILED) { perror("activity mapping"); return EXIT_FAILURE; }
    memset(activity, 0, bytes);
  }

  lauberhorn_handler_t handler = {
      .func = bench_handler,
      .data = (void *)def,
      // The inline handler returns borrowed thread-local response storage.
  };

  if (argc >= 4)
    setenv("LAUBERHORN_SERVER_TRACE_CSV", argv[3], 1);

  workers = calloc((size_t)workers_count, sizeof(*workers));
  if (!workers) {
    perror("calloc workers");
    return EXIT_FAILURE;
  }

  err = lauberhorn_init(&ctx);
  if (err) {
    fprintf(stderr, "Failed to initialize Lauberhorn! err=%d\n", err);
    free(workers);
    return EXIT_FAILURE;
  }

  err = lauberhorn_reg_srv(&ctx, handler, def->prog,
                           def->vers, def->proc, def->port, &codec);
  if (err < 0) {
    fprintf(stderr, "Failed to register %s service! err=%d\n", def->name, err);
    goto fini;
  }
  srv_id = err;

  fprintf(stderr, "microbenchmark %s listening on UDP port %u with %d workers\n",
          def->name, def->port, workers_count);

  if (lauberhorn_set_worker_count(&ctx, workers_count)) {
    perror("declare worker pool");
    goto fini;
  }
  for (i = 0; i < workers_count; ++i) {
    workers[i] = lauberhorn_create_worker(&ctx, worker_init, NULL);
    if (!workers[i])
      fprintf(stderr, "Failed to launch worker #%d!\n", i);
  }

  for (i = 0; i < workers_count; ++i) {
    if (workers[i])
      lauberhorn_join_worker(&ctx, workers[i]);
  }

  lauberhorn_dereg_srv(&ctx, srv_id);
fini:
  lauberhorn_fini(&ctx);
  free(workers);
  return err < 0 ? EXIT_FAILURE : EXIT_SUCCESS;
}
