#ifndef LAUBERHORN_RT_WORKER_H
#define LAUBERHORN_RT_WORKER_H

#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

#include "config.h"
#include "eci/core.h"
#include "lauberhorn.h"
#include "server_trace.h"

lauberhorn_worker_t worker_get_current(); 

struct lauberhorn_hw_handler {
    // pointer to handler
    lauberhorn_handler_t handler;
    // holds info about the service (serializers, etc.)
    struct srv *srv;
};

struct lauberhorn_worker {
    lauberhorn_t *ctx;
    int worker_id;

    lauberhorn_user_cb_t init, fini;
    lauberhorn_core_state_t dp;
    uint8_t rx_next, tx_next;

    void *dp_base;
    pthread_t thread;
    struct server_trace_row rx_trace;
    bool execution_active;
    uint8_t execution_pending;
};

static inline void worker_execution_end(lauberhorn_worker_t w) {
    if (w->ctx->_watchdog_enabled && w->execution_active) {
        w->execution_pending |= 1;
        w->execution_active = false;
    }
}
static inline uint8_t worker_execution_take(lauberhorn_worker_t w, bool end) {
    if (!w->ctx->_watchdog_enabled) return 0;
    if (end) worker_execution_end(w);
    uint8_t command = w->execution_pending;
    w->execution_pending = 0;
    return command;
}

#endif // LAUBERHORN_RT_WORKER_H
