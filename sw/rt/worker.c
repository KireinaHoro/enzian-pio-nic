#include <assert.h>
#include <errno.h>
#include <sys/mman.h>

#include "fiber/scheduler.h"
#include "lauberhorn.h"
#include "lauberhorn_state.h"
#include "log.h"
#include "oncrpc.h"
#include "worker.h"
#include "rpc.h"
#include "utils/clock.h"

static __thread lauberhorn_worker_t current_worker;
static atomic_int next_worker = 0;

lauberhorn_worker_t worker_get_current() {
    return current_worker; 
}

int init_worker(lauberhorn_worker_t w) {

    int dp_size = LAUBERHORN_ECI_CORE_OFFSET;
    int dp_offset = dp_size * w->worker_id + getpagesize();

    void *dp_base = mmap(NULL, dp_size, PROT_READ | PROT_WRITE, MAP_SHARED,
                         w->ctx->fd, dp_offset);

    if (dp_base == MAP_FAILED) {
        PERROR("map datapath page");
        return -1;
    }

    w->dp_base = dp_base;

    if (w->init) {
        w->init(w->worker_id);
    }

    // Mask SIGINT - handled in the main thread only
    sigset_t signal_mask;
    sigemptyset(&signal_mask);
    sigaddset(&signal_mask, SIGINT);
    sigaddset(&signal_mask, SIGTERM);
    int err = pthread_sigmask(SIG_BLOCK, &signal_mask, NULL);
    if (err) {
        return -1;
    }
    return 0;
}

// run every 1 seconds
//  when perf measurements were done, this was still set to run once every 100 iterations
#define SWEEP_PERIOD_MS 1000
static _Atomic int64_t last_sweep_ms = 0;

static void rpc_timeout_tick(lauberhorn_worker_t w) {
    struct timespec t = now();
    int64_t t_ms = t.tv_sec * 1000 + t.tv_nsec / 1000000;
    int64_t last = atomic_load(&last_sweep_ms);

    // first worker to pass the CAS sweeps, the others skip
    if (t_ms - last >= SWEEP_PERIOD_MS &&
        atomic_compare_exchange_strong(&last_sweep_ms, &last, t_ms)) {
        rpc_call_process_timeout();
    }
}

static void execution_flush(void) {
    lauberhorn_worker_t w = worker_get_current();
    uint8_t command = worker_execution_take(w, false);
    if (!command) return;
    lauberhorn_pkt_desc_t desc = {.type = TY_EXECUTION_CONTROL, .execution = command};
    if (core_eci_tx(w->dp_base, &w->dp, &desc) != CORE_TX_ACCEPTED) abort();
}
static void execution_begin(void) {
    lauberhorn_worker_t w = worker_get_current();
    if (!w->ctx->_watchdog_enabled || w->execution_active) return;
    w->execution_pending |= 2;
    w->execution_active = true;
    execution_flush();
}
static void execution_end(void) { worker_execution_end(worker_get_current()); }

static void *lauberhorn_worker_loop(void *arg) {
    lauberhorn_worker_t w = arg;
    current_worker = w;

    if (init_worker(w) < 0) {
        return NULL;
    }

    // initialize scheduler context
    fiber_sched_init();
    fiber_sched_set_execution_hooks(execution_begin, execution_end, execution_flush);

    lauberhorn_pkt_desc_t desc;

    while (lauberhorn_is_running) {
        // Sweep before draining: timeout wakeups run before another RX poll.
        rpc_timeout_tick(w);
        fiber_sched_run_ready();

        // wait for data
        if (core_eci_rx(w->dp_base, &w->dp, &desc)) {
            rpc_handle_packet(w, &desc);
        }
    }
    return NULL;
}

void lauberhorn_free_result(rpc_result_t *c) {
    if (!c || !c->data)
        return;
    c->codec->ops->free(c->data, RPC_FREE_RESP, c->codec->priv);
    c->data = NULL;
}

lauberhorn_worker_t lauberhorn_create_worker(lauberhorn_t *ctx,
                                             lauberhorn_user_cb_t init,
                                             lauberhorn_user_cb_t fini) {
    int err;

    lauberhorn_worker_t w = calloc(sizeof(struct lauberhorn_worker), 1);
    if (!w) {
        PERROR("allocate worker");
        return NULL;
    }

    w->ctx = ctx;
    w->init = init;
    w->fini = fini;
    w->worker_id = atomic_fetch_add(&next_worker, 1);

    // parity bits are shared with the kernel
    w->dp.rx_next_cl = &ctx->parity_page[w->worker_id * 2];
    w->dp.tx_next_cl = &ctx->parity_page[w->worker_id * 2 + 1];
    *w->dp.rx_next_cl = *w->dp.tx_next_cl = 0;
    w->dp.rx_buf_size = LAUBERHORN_MTU;

    // TODO: check if allocation failed
    w->dp.rx_buf = malloc(LAUBERHORN_MTU);
    w->dp.tx_buf = malloc(LAUBERHORN_MTU);

    lauberhorn_is_running = 1;

    ctx->_workers++;
    err = pthread_create(&w->thread, NULL, lauberhorn_worker_loop, w);
    if (err != 0) {
        ctx->_workers--;
        errno = err;
        PERROR("create thread");
        free(w->dp.tx_buf);
        free(w->dp.rx_buf);
        free(w);
        return NULL;
    }
    return w;
}

void lauberhorn_join_worker(lauberhorn_t *ctx, lauberhorn_worker_t w) {
    void *res;
    int err;

    err = pthread_join(w->thread, &res);
    if (err != 0) {
        errno = err;
        PERROR("join thread");
        return;
    }

    if (res) {
        LOG("worker thread returned %ld", (ssize_t)res);
    }

    err = munmap(w->dp_base, LAUBERHORN_ECI_CORE_OFFSET);
    if (err != 0) {
        PERROR("unmap datapath");
    }

    ctx->_workers--;
    free(w->dp.rx_buf);
    free(w->dp.tx_buf);
    free(w);
}
