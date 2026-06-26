#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <threads.h>

#include "lauberhorn.h"

#include "bench.h"

#define DEFAULT_WORKERS 4

struct bench_def {
  const char *name;
  int prog;
  int vers;
  int proc;
  uint16_t port;
  int (*compute)(int, int);
  lauberhorn_trace_check_t trace_check;
};

// Handlers execute on multiple worker threads.
thread_local bench_resp resp;

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

static const struct bench_def benches[] = {
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

static lauberhorn_msg_t bench_handler(void *data, lauberhorn_msg_t req,
                                      int xid) {
  const struct bench_def *def = data;
  bench_call *call = req;
  (void)xid;

  resp.request_id = call->request_id;
  resp.result = def->compute(call->a, call->b);

  return &resp;
}

static const struct bench_def *find_bench(const char *name) {
  size_t i;

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
  fprintf(stderr, "usage: %s add|mul [workers=%d] [server_trace.csv]\n",
          argv0, DEFAULT_WORKERS);
}

int main(int argc, char *argv[]) {
  const struct bench_def *def;
  int err, srv_id = -1, i;
  int workers_count = DEFAULT_WORKERS;
  lauberhorn_t ctx;
  lauberhorn_schema_t schema = {
      .call_size = sizeof(bench_call),
      .call_func = (xdrproc_t)xdr_bench_call,
      .resp_func = (xdrproc_t)xdr_bench_resp,
      .trace_request_id = bench_request_id,
  };
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

  if (argc >= 3)
    workers_count = parse_positive_int(argv[2], "workers");

  schema.trace_check = def->trace_check;

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

  err = lauberhorn_reg_srv(&ctx, bench_handler, (void *)def, def->prog,
                           def->vers, def->proc, def->port, &schema);
  if (err < 0) {
    fprintf(stderr, "Failed to register %s service! err=%d\n", def->name, err);
    goto fini;
  }
  srv_id = err;

  fprintf(stderr, "microbenchmark %s listening on UDP port %u with %d workers\n",
          def->name, def->port, workers_count);

  for (i = 0; i < workers_count; ++i) {
    workers[i] = lauberhorn_create_worker(&ctx, noop_cb, noop_cb);
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
