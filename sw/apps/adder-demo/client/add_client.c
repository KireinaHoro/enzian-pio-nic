/*
 * Small ONC-RPC client for the Lauberhorn adder demo.
 */

#include "add.h"

#include <errno.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static struct timeval timeout = {25, 0};

struct trace_row {
  uint32_t request_id;
  int a;
  int b;
  int expected;
  int result;
  uint32_t response_request_id;
  int rpc_status;
  bool ok;
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

static bool_t traced_xdr_add_call(XDR *xdrs, add_call *objp) {
  if (active_trace && xdrs->x_op == XDR_ENCODE) {
    if (active_trace->client_xdr_call_enter_ns == 0)
      active_trace->client_xdr_call_enter_ns = trace_now_ns();
    active_trace->xdr_call_count++;
  }

  bool_t ok = xdr_add_call(xdrs, objp);

  if (active_trace && xdrs->x_op == XDR_ENCODE)
    active_trace->client_xdr_call_exit_ns = trace_now_ns();

  return ok;
}

static bool_t traced_xdr_add_resp(XDR *xdrs, add_resp *objp) {
  if (active_trace && xdrs->x_op == XDR_DECODE) {
    if (active_trace->client_xdr_resp_enter_ns == 0)
      active_trace->client_xdr_resp_enter_ns = trace_now_ns();
    active_trace->xdr_resp_count++;
  }

  bool_t ok = xdr_add_resp(xdrs, objp);

  if (active_trace && xdrs->x_op == XDR_DECODE)
    active_trace->client_xdr_resp_exit_ns = trace_now_ns();

  return ok;
}

static enum clnt_stat add_checked(CLIENT *clnt, struct trace_row *row) {
  add_call arg = {
      .request_id = row->request_id,
      .a = row->a,
      .b = row->b,
  };
  add_resp result = {0};

  active_trace = row;
  row->client_call_enter_ns = trace_now_ns();
  enum clnt_stat stat =
      clnt_call(clnt, ADD, (xdrproc_t)traced_xdr_add_call, (caddr_t)&arg,
                (xdrproc_t)traced_xdr_add_resp, (caddr_t)&result, timeout);
  row->client_call_exit_ns = trace_now_ns();
  active_trace = NULL;

  row->rpc_status = stat;
  row->result = result.sum;
  row->response_request_id = result.request_id;
  row->ok = stat == RPC_SUCCESS && result.request_id == row->request_id &&
            result.sum == row->expected;
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
          "request_id,a,b,expected,result,response_request_id,rpc_status,ok,"
          "client_call_enter_ns,client_xdr_call_enter_ns,"
          "client_xdr_call_exit_ns,client_xdr_resp_enter_ns,"
          "client_xdr_resp_exit_ns,client_call_exit_ns,"
          "xdr_call_count,xdr_resp_count,timestamp_overhead_ns\n");
  return f;
}

static void write_trace_row(FILE *f, const struct trace_row *row) {
  fprintf(f,
          "%" PRIu32 ",%d,%d,%d,%d,%" PRIu32 ",%d,%d,"
          "%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%"
          PRIu64 ",%u,%u,%" PRIu64 "\n",
          row->request_id, row->a, row->b, row->expected, row->result,
          row->response_request_id, row->rpc_status, row->ok ? 1 : 0,
          row->client_call_enter_ns, row->client_xdr_call_enter_ns,
          row->client_xdr_call_exit_ns, row->client_xdr_resp_enter_ns,
          row->client_xdr_resp_exit_ns, row->client_call_exit_ns,
          row->xdr_call_count, row->xdr_resp_count, clock_overhead_ns);
}

static int parse_positive_int(const char *text, const char *name) {
  char *end = NULL;
  long value = strtol(text, &end, 0);
  if (!text[0] || *end || value <= 0 || value > INT32_MAX) {
    fprintf(stderr, "invalid %s: %s\n", name, text);
    exit(1);
  }
  return (int)value;
}

static int run(char *host, int side, const char *csv_path) {
  CLIENT *clnt = clnt_create(host, ADD_PROG, ADD_VERS, "udp");
  if (clnt == NULL) {
    clnt_pcreateerror(host);
    return 1;
  }

  FILE *trace = open_trace_csv(csv_path);
  uint32_t request_id = 0;
  int failures = 0;
  int calls = 0;

  for (int i = 0; i < side; ++i) {
    for (int j = 0; j < side; ++j) {
      struct trace_row row = {
          .request_id = request_id++,
          .a = i,
          .b = j,
          .expected = i + j,
      };
      enum clnt_stat stat = add_checked(clnt, &row);
      if (stat != RPC_SUCCESS)
        clnt_perror(clnt, "call failed");
      if (!row.ok)
        failures++;
      write_trace_row(trace, &row);
      calls++;
    }
  }

  fclose(trace);
  clnt_destroy(clnt);

  fprintf(stderr, "adder client checked %d calls, failures=%d, csv=%s\n", calls,
          failures, csv_path);
  return failures == 0 ? 0 : 1;
}

int main(int argc, char *argv[]) {
  if (argc < 2 || argc > 4) {
    fprintf(stderr, "usage: %s server_host [side=100] [trace.csv]\n", argv[0]);
    return 1;
  }

  int side = argc >= 3 ? parse_positive_int(argv[2], "side") : 100;
  const char *csv_path = argc >= 4 ? argv[3] : getenv("ADDER_CLIENT_TRACE_CSV");
  if (!csv_path || !csv_path[0])
    csv_path = "adder_client_timestamps.csv";

  clock_overhead_ns = calibrate_clock_overhead();
  return run(argv[1], side, csv_path);
}
