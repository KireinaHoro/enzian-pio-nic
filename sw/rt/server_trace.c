#define _GNU_SOURCE
#include "server_trace.h"
#include <sched.h>
#include "lauberhorn/trace.h"
#include "fiber/scheduler.h"
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>

bool server_trace_enabled;
__thread struct server_trace_row *server_trace_inline;
__thread struct server_activation_trace server_activation;
static FILE *output;
static uint64_t overhead_ns;
uint64_t server_trace_clock(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC_RAW, &t);
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
uint64_t server_trace_stamp(struct server_trace_row *row) {
    if (!row || !row->enabled) return 0;
    row->timestamp_calls++;
    return server_trace_clock();
}
void server_trace_init(void) {
    const char *path = getenv("LAUBERHORN_SERVER_TRACE_CSV");
    if (!path || !*path) path = getenv("ADDER_SERVER_TRACE_CSV");
    if (!path || !*path || output) return;
    output = fopen(path, "w");
    if (!output) { perror("server trace CSV"); return; }
    setvbuf(output, NULL, _IOLBF, 0);
    overhead_ns = UINT64_MAX;
    for (int i = 0; i < 256; ++i) {
        uint64_t a = server_trace_clock(), b = server_trace_clock();
        if (b > a && b-a < overhead_ns) overhead_ns = b-a;
    }
    if (overhead_ns == UINT64_MAX) overhead_ns = 0;
    fputs("request_id,xid,worker_id,ok,request_bytes,response_bytes,", output);
#define TRACE_HEADER(name) fputs("server_" #name "_ns,", output);
    SERVER_TRACE_TIMES(TRACE_HEADER)
#undef TRACE_HEADER
    fputs("timestamp_overhead_ns,timestamp_call_count,request_id_valid,request_context,"
          "tx_worker_id,execution_kind,reply_status,transport_status,rx_cpu_id,tx_cpu_id,"
          "server_defer_enqueue_ns,server_forward_submit_ns,server_forward_complete_ns,"
          "server_handler_active_ns,server_handler_wait_ns,server_tid,activation_index,"
          "activation_worker,server_activation_ioctl_exit_ns,server_activation_ready_ns\n", output);
    server_trace_enabled = true;
}
void server_trace_fini(void) {
    server_trace_enabled = false;
    if (output) fclose(output);
    output = NULL;
}
void server_trace_rx_begin(struct server_trace_row *r, int worker_id) {
    *r = (struct server_trace_row){.enabled=server_trace_enabled, .ok=true,
        .request_id=UINT64_MAX, .worker_id=worker_id, .tx_worker_id=-1,
        .activation=server_activation};
    r->rx_enter_ns = server_trace_stamp(r);
}
static struct server_trace_row *current_trace(void) {
    return fiber_in_context() ? current_fiber->trace : server_trace_inline;
}
void lauberhorn_trace_request_id(uint64_t id) {
    struct server_trace_row *r = current_trace();
    if (r && r->enabled) { r->request_id=id; r->id_valid=true; }
}
void lauberhorn_trace_result(bool ok) {
    struct server_trace_row *r = current_trace();
    if (r && r->enabled) r->ok &= ok;
}
void server_trace_run_begin(struct server_trace_row *r) {
    if (r && r->enabled) r->run_start_ns = server_trace_stamp(r);
}
void server_trace_run_end(struct server_trace_row *r) {
    if (r && r->enabled && r->run_start_ns) {
        r->handler_active_ns += server_trace_stamp(r) - r->run_start_ns;
        r->run_start_ns = 0;
    }
}
void server_trace_write(struct server_trace_row *r) {
    if (!r || !r->enabled) return;
    r->enabled = false; /* terminal event: never write this ownership twice */
    uint64_t elapsed = r->handler_exit_ns - r->handler_enter_ns;
    if (r->handler_active_ns > elapsed) r->handler_active_ns = elapsed;
    uint64_t wait = elapsed > r->handler_active_ns ? elapsed-r->handler_active_ns : 0;
    flockfile(output);
    /* Wire XID is unavailable in the opaque-context ABI: leave it empty. */
    fprintf(output, "%" PRIu64 ",,%d,%u,%u,%u,", r->request_id, r->worker_id,
            r->ok, r->request_bytes, r->response_bytes);
#define TRACE_VALUE(name) fprintf(output, "%" PRIu64 ",", r->name##_ns);
    SERVER_TRACE_TIMES(TRACE_VALUE)
#undef TRACE_VALUE
    fprintf(output, "%" PRIu64 ",%u,%u,%u,%d,%u,%d,%d,%d,%d,%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%d,%" PRIu64 ",%d,%" PRIu64 ",%" PRIu64 "\n",
            overhead_ns, r->timestamp_calls, r->id_valid, r->request_context,
            r->tx_worker_id, r->kind, r->status, r->transport_status, r->rx_cpu_id, r->tx_cpu_id,
            r->defer_enqueue_ns, r->forward_submit_ns, r->forward_complete_ns,
            r->handler_active_ns, wait, r->activation.tid, r->activation.index,
            r->activation.worker, r->activation.ioctl_exit_ns, r->activation.ready_ns);
    funlockfile(output);
}
