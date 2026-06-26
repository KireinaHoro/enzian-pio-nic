/*
 * ONC-RPC client for Lauberhorn add/mul microbenchmarks.
 */

#include "bench.h"

#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static struct timeval timeout = {25, 0};

enum bench_op {
  OP_ADD,
  OP_MUL,
};

enum bench_pattern {
  PATTERN_ADD,
  PATTERN_MUL,
  PATTERN_ALTERNATE,
  PATTERN_BURST,
};

struct trace_row {
  uint32_t request_id;
  const char *op;
  int a;
  int b;
  int expected;
  int result;
  uint32_t response_request_id;
  int rpc_status;
  bool ok;
  bool switch_request;
  uint64_t client_call_enter_ns;
  uint64_t client_xdr_call_enter_ns;
  uint64_t client_xdr_call_exit_ns;
  uint64_t client_xdr_resp_enter_ns;
  uint64_t client_xdr_resp_exit_ns;
  uint64_t client_call_exit_ns;
  unsigned xdr_call_count;
  unsigned xdr_resp_count;
};

static struct trace_row *active_trace;
static uint64_t clock_overhead_ns;

static uint64_t trace_now_ns(void) {
  struct timespec ts;
  if (clock_gettime(CLOCK_MONOTONIC_RAW, &ts) != 0) {
    perror("clock_gettime");
    exit(2);
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

static bool_t traced_xdr_bench_call(XDR *xdrs, bench_call *objp) {
  if (active_trace && xdrs->x_op == XDR_ENCODE) {
    if (active_trace->client_xdr_call_enter_ns == 0)
      active_trace->client_xdr_call_enter_ns = trace_now_ns();
    active_trace->xdr_call_count++;
  }

  bool_t ok = xdr_bench_call(xdrs, objp);

  if (active_trace && xdrs->x_op == XDR_ENCODE)
    active_trace->client_xdr_call_exit_ns = trace_now_ns();

  return ok;
}

static bool_t traced_xdr_bench_resp(XDR *xdrs, bench_resp *objp) {
  if (active_trace && xdrs->x_op == XDR_DECODE) {
    if (active_trace->client_xdr_resp_enter_ns == 0)
      active_trace->client_xdr_resp_enter_ns = trace_now_ns();
    active_trace->xdr_resp_count++;
  }

  bool_t ok = xdr_bench_resp(xdrs, objp);

  if (active_trace && xdrs->x_op == XDR_DECODE)
    active_trace->client_xdr_resp_exit_ns = trace_now_ns();

  return ok;
}

static const char *op_name(enum bench_op op) {
  return op == OP_ADD ? "add" : "mul";
}

static int op_proc(enum bench_op op) { return op == OP_ADD ? ADD : MUL; }

static int op_expected(enum bench_op op, int a, int b) {
  return op == OP_ADD ? a + b : a * b;
}

static enum clnt_stat call_checked(CLIENT *clnt, enum bench_op op,
                                   struct trace_row *row) {
  bench_call arg = {
      .request_id = row->request_id,
      .a = row->a,
      .b = row->b,
  };
  bench_resp result = {0};

  active_trace = row;
  row->client_call_enter_ns = trace_now_ns();
  enum clnt_stat stat =
      clnt_call(clnt, op_proc(op), (xdrproc_t)traced_xdr_bench_call,
                (caddr_t)&arg, (xdrproc_t)traced_xdr_bench_resp,
                (caddr_t)&result, timeout);
  row->client_call_exit_ns = trace_now_ns();
  active_trace = NULL;

  row->rpc_status = stat;
  row->result = result.result;
  row->response_request_id = result.request_id;
  row->ok = stat == RPC_SUCCESS && result.request_id == row->request_id &&
            result.result == row->expected;
  return stat;
}

static FILE *open_trace_csv(const char *path) {
  FILE *f = fopen(path, "w");
  if (!f) {
    fprintf(stderr, "failed to open client trace CSV %s: %s\n", path,
            strerror(errno));
    exit(2);
  }

  setvbuf(f, NULL, _IOLBF, 0);
  fprintf(f,
          "request_id,op,a,b,expected,result,response_request_id,rpc_status,"
          "ok,switch_request,client_call_enter_ns,client_xdr_call_enter_ns,"
          "client_xdr_call_exit_ns,client_xdr_resp_enter_ns,"
          "client_xdr_resp_exit_ns,client_call_exit_ns,latency_ns,"
          "xdr_call_count,xdr_resp_count,timestamp_overhead_ns\n");
  return f;
}

static void write_trace_row(FILE *f, const struct trace_row *row) {
  uint64_t latency_ns = row->client_call_exit_ns - row->client_call_enter_ns;

  fprintf(f,
          "%" PRIu32 ",%s,%d,%d,%d,%d,%" PRIu32 ",%d,%d,%d,"
          "%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%"
          PRIu64 ",%" PRIu64 ",%u,%u,%" PRIu64 "\n",
          row->request_id, row->op, row->a, row->b, row->expected,
          row->result, row->response_request_id, row->rpc_status,
          row->ok ? 1 : 0, row->switch_request ? 1 : 0,
          row->client_call_enter_ns, row->client_xdr_call_enter_ns,
          row->client_xdr_call_exit_ns, row->client_xdr_resp_enter_ns,
          row->client_xdr_resp_exit_ns, row->client_call_exit_ns, latency_ns,
          row->xdr_call_count, row->xdr_resp_count, clock_overhead_ns);
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

static enum bench_pattern parse_pattern(const char *text) {
  if (strcmp(text, "add") == 0)
    return PATTERN_ADD;
  if (strcmp(text, "mul") == 0)
    return PATTERN_MUL;
  if (strcmp(text, "alternate") == 0)
    return PATTERN_ALTERNATE;
  if (strcmp(text, "burst") == 0)
    return PATTERN_BURST;

  fprintf(stderr, "invalid pattern: %s\n", text);
  exit(1);
}

static enum bench_op select_op(enum bench_pattern pattern, int idx,
                               int burst_len) {
  switch (pattern) {
  case PATTERN_ADD:
    return OP_ADD;
  case PATTERN_MUL:
    return OP_MUL;
  case PATTERN_ALTERNATE:
    return (idx & 1) ? OP_MUL : OP_ADD;
  case PATTERN_BURST:
    return ((idx / burst_len) & 1) ? OP_MUL : OP_ADD;
  }

  abort();
}

static CLIENT *create_client(char *host, enum bench_op op) {
  CLIENT *clnt = clnt_create(host, op == OP_ADD ? ADD_PROG : MUL_PROG,
                             op == OP_ADD ? ADD_VERS : MUL_VERS, "udp");
  if (!clnt)
    clnt_pcreateerror(host);
  return clnt;
}

static int run(char *host, enum bench_pattern pattern, int calls,
               const char *csv_path, int burst_len) {
  CLIENT *add_clnt = NULL;
  CLIENT *mul_clnt = NULL;
  FILE *trace;
  enum bench_op prev_op = OP_ADD;
  bool have_prev_op = false;
  int failures = 0;

  if (pattern != PATTERN_MUL) {
    add_clnt = create_client(host, OP_ADD);
    if (!add_clnt)
      return 1;
  }
  if (pattern != PATTERN_ADD) {
    mul_clnt = create_client(host, OP_MUL);
    if (!mul_clnt) {
      if (add_clnt)
        clnt_destroy(add_clnt);
      return 1;
    }
  }

  trace = open_trace_csv(csv_path);

  for (int i = 0; i < calls; ++i) {
    enum bench_op op = select_op(pattern, i, burst_len);
    CLIENT *clnt = op == OP_ADD ? add_clnt : mul_clnt;
    struct trace_row row = {
        .request_id = (uint32_t)i,
        .op = op_name(op),
        .a = i & 0x3ff,
        .b = (i * 17 + 3) & 0x3ff,
        .switch_request = have_prev_op && prev_op != op,
    };
    row.expected = op_expected(op, row.a, row.b);

    enum clnt_stat stat = call_checked(clnt, op, &row);
    if (stat != RPC_SUCCESS)
      clnt_perror(clnt, "call failed");
    if (!row.ok)
      failures++;
    write_trace_row(trace, &row);

    prev_op = op;
    have_prev_op = true;
  }

  fclose(trace);
  if (add_clnt)
    clnt_destroy(add_clnt);
  if (mul_clnt)
    clnt_destroy(mul_clnt);

  fprintf(stderr, "bench client checked %d calls, failures=%d, csv=%s\n", calls,
          failures, csv_path);
  return failures == 0 ? 0 : 1;
}

int main(int argc, char *argv[]) {
  if (argc < 3 || argc > 6) {
    fprintf(stderr,
            "usage: %s server_host add|mul|alternate|burst [calls=10000] "
            "[trace.csv] [burst_len=4]\n",
            argv[0]);
    return 1;
  }

  enum bench_pattern pattern = parse_pattern(argv[2]);
  int calls = argc >= 4 ? parse_positive_int(argv[3], "calls") : 10000;
  const char *csv_path = argc >= 5 ? argv[4] : getenv("BENCH_CLIENT_TRACE_CSV");
  int burst_len = argc >= 6 ? parse_positive_int(argv[5], "burst_len") : 4;

  if (!csv_path || !csv_path[0])
    csv_path = "bench_client_timestamps.csv";

  clock_overhead_ns = calibrate_clock_overhead();
  return run(argv[1], pattern, calls, csv_path, burst_len);
}
