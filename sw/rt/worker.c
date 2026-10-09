#define _GNU_SOURCE
#include <sched.h>
#include <assert.h>
#include <errno.h>
#include <sys/mman.h>

#include "fiber/scheduler.h"
#include "lauberhorn.h"
#include "ioctl.h"
#include "lauberhorn_state.h"
#include "log.h"
#include "oncrpc.h"
#include "worker.h"
#include "rpc.h"
#include "utils/clock.h"

static __thread lauberhorn_worker_t current_worker;

lauberhorn_worker_t worker_get_current() {
    return current_worker; 
}

int init_worker(lauberhorn_worker_t w) {

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

static void worker_fail(lauberhorn_worker_t w) {
    lauberhorn_stop(w->ctx);
    abort();
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

    if (init_worker(w) < 0) worker_fail(w);

    server_trace_thread_init();

    // initialize scheduler context
    fiber_sched_init();
    fiber_sched_set_execution_hooks(execution_begin, execution_end, execution_flush);

    uint32_t index = w->worker_id;
    struct lh_activation activation;
    if (ioctl(w->ctx->fd, LAUBERHORN_IOCTL_REGISTER_THREAD, &index) ||
        lh_yield(&activation)) worker_fail(w);

    lauberhorn_pkt_desc_t desc;

    while (lauberhorn_is_running) {
        // Sweep before draining: timeout wakeups run before another RX poll.
        rpc_timeout_tick(w);
        fiber_sched_run_ready();

        // wait for data
        bool received;
        if (server_trace_enabled) {
            server_trace_rx_begin(&w->rx_trace, w->worker_id);
            received = core_eci_rx_acquire_traced(w->dp_base, &w->dp, &desc,
                &w->rx_trace.rx_unblock_ns, server_trace_clock);
            w->rx_trace.timestamp_calls++; /* RX-unblock probe */
            w->rx_trace.rx_exit_ns = server_trace_stage(&w->rx_trace, LH_PMU_rx_exit);
            w->rx_trace.rx_cpu_id = sched_getcpu();
        } else {
            received = core_eci_rx_acquire(w->dp_base, &w->dp, &desc);
        }
        if (received && desc.type == TY_PREEMPT) {
            if (lh_yield(&activation)) worker_fail(w);
            continue;
        }
        if (received) {
            w->dp.rx_buf = w->dp.rx_view.data; /* borrowed, never queued */
            rpc_handle_packet(w, &desc);
            core_eci_rx_discard(&w->dp);
            w->dp.rx_buf = NULL;
        }
    }
    worker_fail(w); /* Individual withdrawal is unsupported. */
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
    if (!ctx || !ctx->_declared_workers || ctx->_created >= ctx->_declared_workers) {
        errno = EINVAL;
        return NULL;
    }

    lauberhorn_worker_t w = calloc(sizeof(struct lauberhorn_worker), 1);
    if (!w) {
        PERROR("allocate worker");
        return NULL;
    }

    w->ctx = ctx;
    w->init = init;
    w->fini = fini;
    w->worker_id = ctx->_created++;

    w->dp.rx_next_cl = &w->rx_next;
    w->dp.tx_next_cl = &w->tx_next;
    w->dp.rx_buf_size = LAUBERHORN_MTU;

    /* RX aliases the borrowed transport view; TX aliases a live reservation.
     * Neither pointer owns a heap allocation. */

    lauberhorn_is_running = 1;

    ctx->_workers++;
    err = pthread_create(&w->thread, NULL, lauberhorn_worker_loop, w);
    if (err != 0) {
        ctx->_workers--;
        errno = err;
        PERROR("create thread");
        lauberhorn_stop(ctx);
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

    ctx->_workers--;
    free(w->compat_rx_buf);
    free(w);
}

/* Native application workers finish their own initialization before joining. */
int lauberhorn_udp_attach(lauberhorn_t *ctx, uint32_t index) {
    if (!ctx || current_worker || index >= ctx->_declared_workers) {
        errno = EINVAL;
        return -1;
    }
    lauberhorn_worker_t w = calloc(1, sizeof(*w));
    if (!w) return -1;
    w->ctx = ctx;
    w->worker_id = index;
    w->thread = pthread_self();
    w->dp.rx_next_cl = &w->rx_next;
    w->dp.tx_next_cl = &w->tx_next;
    w->dp.rx_buf_size = LAUBERHORN_MTU;
    server_trace_thread_init();
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_REGISTER_THREAD, &index)) {
        free(w);
        return -1;
    }
    current_worker = w;
    __atomic_add_fetch(&ctx->_workers, 1, __ATOMIC_RELAXED);
    struct lh_activation activation;
    if (lh_yield(&activation)) worker_fail(w);
    return 0;
}
