#ifndef LAUBERHORN_RT_WORKER_H
#define LAUBERHORN_RT_WORKER_H

#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

#include "config.h"
#include "eci/core.h"
#include "lauberhorn.h"

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

    void *dp_base;
    pthread_t thread;
};

#endif // LAUBERHORN_RT_WORKER_H
