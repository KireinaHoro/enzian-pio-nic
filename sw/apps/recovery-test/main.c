/* Enzian recovery fixture. Intentionally uses private runtime/ECI interfaces
 * to create faults; this is not an example of the public application contract. */
#include "lauberhorn.h"
#include "lauberhorn/oncrpc.h"
#include "worker.h"
#include "bench.h"
#include <errno.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static const char *mode;
static _Thread_local bench_resp response;
static void hang(void) { for (;;) atomic_signal_fence(memory_order_seq_cst); }
static lauberhorn_outcome_t handle(void *unused, void *arg, lauberhorn_request_context_t token)
{
    bench_call *call = arg;
    lauberhorn_worker_t w = worker_get_current();
    if (!strcmp(mode, "busy")) {
        enter_cs(w->dp_base);
        hang();
    }
    if (!strcmp(mode, "dirty-tx")) {
        enter_cs(w->dp_base);
        volatile unsigned char *base = (unsigned char *)w->dp_base + LAUBERHORN_ECI_TX_BASE;
        volatile unsigned char *ctrl = base + *w->dp.tx_next_cl * LAUBERHORN_ECI_CL_SIZE;
        /* Acquire/dirty TX and overflow, but never read the opposite control CL
         * to commit it. Recovery must invalidate without transmitting garbage. */
        ctrl[0] = 0xff;
        base[LAUBERHORN_ECI_OVERFLOW_OFFSET] = 0xa5;
        BARRIER;
        exit_cs(w->dp_base);
        hang();
    }
    if (!strcmp(mode, "hang")) hang();
    response = (bench_resp){.request_id = call->request_id, .result = call->a + call->b};
    return lauberhorn_reply(&response, sizeof(response), NULL);
}
static unsigned number(const char *s)
{
    char *end;
    errno = 0;
    unsigned long value = strtoul(s, &end, 10);
    if (errno || !*s || *end || value > UINT32_MAX) exit(2);
    return value;
}
int main(int argc, char **argv)
{
    if (argc < 3 || argc > 5) {
        fprintf(stderr, "usage: %s normal|hang|busy|dirty-tx WATCHDOG_US [WORKERS=4] [STOP_AFTER_MS=0]\n", argv[0]);
        return 2;
    }
    mode = argv[1];
    if (strcmp(mode, "normal") && strcmp(mode, "hang") && strcmp(mode, "busy") && strcmp(mode, "dirty-tx")) return 2;
    unsigned watchdog = number(argv[2]), workers = argc > 3 ? number(argv[3]) : 4;
    unsigned stop_ms = argc > 4 ? number(argv[4]) : 0;
    if (!workers || workers > LAUBERHORN_NUM_WORKER_CORES) return 2;
    lauberhorn_t ctx;
    if (lauberhorn_init(&ctx) || lauberhorn_set_watchdog(&ctx, watchdog)) { perror("init/watchdog"); return 1; }
    xdr_adapter_t adapter = {.call_size = sizeof(bench_call), .resp_size = sizeof(bench_resp),
        .call_xdrproc = (xdrproc_t)xdr_bench_call, .resp_xdrproc = (xdrproc_t)xdr_bench_resp};
    rpc_codec_t codec = oncrpc_codec(&adapter);
    lauberhorn_handler_t handler = {.func = handle};
    if (lauberhorn_reg_srv(&ctx, handler, ADD_PROG, ADD_VERS, ADD, 12345, &codec) < 0) { perror("service"); return 1; }
    lauberhorn_worker_t threads[LAUBERHORN_NUM_WORKER_CORES];
    for (unsigned i = 0; i < workers; ++i)
        if (!(threads[i] = lauberhorn_create_worker(&ctx, NULL, NULL))) { perror("worker"); return 1; }
    fprintf(stderr, "RECOVERY_READY mode=%s watchdog_us=%u workers=%u\n", mode, watchdog, workers);
    fflush(stderr);
    if (stop_ms) {
        struct timespec delay = {.tv_sec = stop_ms / 1000, .tv_nsec = (stop_ms % 1000) * 1000000L};
        nanosleep(&delay, NULL);
        if (lauberhorn_stop(&ctx)) { perror("stop"); return 1; }
    }
    for (unsigned i = 0; i < workers; ++i) lauberhorn_join_worker(&ctx, threads[i]);
    lauberhorn_fini(&ctx);
    return 0;
}
