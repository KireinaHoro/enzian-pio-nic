/* SPDX-License-Identifier: BSD-3-Clause */
/* Copyright (c) 2025 Pengcheng Xu */

#ifndef LAUBERHORN_H
#define LAUBERHORN_H

#include  "lauberhorn/common.h"
#include <rpc/rpc.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "lauberhorn/rpc.h"
#include "lauberhorn/xdr_rpc.h"
#include "lauberhorn/future.h"
#include "lauberhorn/activation.h"

typedef struct lauberhorn {
	/* to /dev/lauberhorn */
	int fd;
	void *_aliases;
	uint32_t _declared_workers;
	uint32_t _created;
	/* Private: control operations require external serialization. */
	uint32_t _workers;
	bool _watchdog_enabled;
} lauberhorn_t;

/* Declare the immutable pool before creating any threads. */
int lauberhorn_set_worker_count(lauberhorn_t *ctx, uint32_t count);
/* Bootstrap or relinquish after PREEMPT; returns this thread's assignment. */
int lh_yield(struct lh_activation *result);

/* Deadline/watchdog integration is deferred; only a zero limit is supported. */
int lauberhorn_set_watchdog(lauberhorn_t *ctx, uint32_t limit_us);
/* Terminate and fence the domain. Resources and identities remain retained;
 * the session requires a full coherence reset before reuse. */
int lauberhorn_stop(lauberhorn_t *ctx);

typedef void* lauberhorn_msg_t;

typedef struct lauberhorn_rpc_endpoint lauberhorn_rpc_endpoint_t;
typedef struct lauberhorn_outcome lauberhorn_outcome_t;
/* Opaque request identity, valid only while its managed request is live.
 * Not a wire XID or a persistent client identity. No metadata query API yet. */
typedef uint32_t lauberhorn_request_context_t;
/* Both entry handlers and deferred functions return an explicit ownership
 * decision. The context token is opaque to applications. The decoded request
 * is borrowed until completion, including deferred execution. */
typedef lauberhorn_outcome_t (*lauberhorn_handler_func_t)(void *, void *, lauberhorn_request_context_t);
typedef void (*lauberhorn_handler_free_t)(void *);
typedef enum {
    LAUBERHORN_REPLY, LAUBERHORN_FAIL, LAUBERHORN_DEFER, LAUBERHORN_FORWARD
} lauberhorn_outcome_kind_t;
/* ONC-RPC accepted reply statuses with no additional protocol body. */
typedef enum {
    LAUBERHORN_RPC_SYSTEM_ERR = 5,
    LAUBERHORN_RPC_GARBAGE_ARGS = 4,
    LAUBERHORN_RPC_PROC_UNAVAIL = 3
} lauberhorn_rpc_error_t;
struct lauberhorn_outcome {
    lauberhorn_outcome_kind_t kind;
    lauberhorn_rpc_error_t error;
    void *data;
    size_t len;
    lauberhorn_handler_free_t destroy;
    lauberhorn_handler_func_t run;
    lauberhorn_rpc_endpoint_t *endpoint;
};
/* destroy(data), when non-NULL, runs exactly once, including rejection.
 * REPLY/FORWARD data must survive until serialization after handler return.
 * DEFER data is owned context; run(data, request, context) executes on a fiber.
 * Its destructor runs after the returned outcome has been consumed.
 * A deferred function returns REPLY, FAIL or FORWARD, never another DEFER. */
static inline lauberhorn_outcome_t lauberhorn_reply(void *data, size_t len,
                                                   lauberhorn_handler_free_t destroy) {
    return (lauberhorn_outcome_t){.kind=LAUBERHORN_REPLY, .data=data, .len=len, .destroy=destroy};
}
static inline lauberhorn_outcome_t lauberhorn_fail(lauberhorn_rpc_error_t error) {
    return (lauberhorn_outcome_t){.kind=LAUBERHORN_FAIL, .error=error};
}
static inline lauberhorn_outcome_t lauberhorn_defer(lauberhorn_handler_func_t run,
                                                   void *data, lauberhorn_handler_free_t destroy) {
    return (lauberhorn_outcome_t){.kind=LAUBERHORN_DEFER, .run=run, .data=data, .destroy=destroy};
}
static inline lauberhorn_outcome_t lauberhorn_forward(lauberhorn_rpc_endpoint_t *ep,
                                                     void *data, size_t len,
                                                     lauberhorn_handler_free_t destroy) {
    return (lauberhorn_outcome_t){.kind=LAUBERHORN_FORWARD, .endpoint=ep,
                                .data=data, .len=len, .destroy=destroy};
}
typedef struct {
    void *data;
    lauberhorn_handler_func_t func;
} lauberhorn_handler_t;

LAUBERHORN_API int lauberhorn_init(lauberhorn_t* ctx);
LAUBERHORN_API void lauberhorn_fini(lauberhorn_t* ctx);

LAUBERHORN_API int lauberhorn_reg_srv(lauberhorn_t* ctx,
									  // convert these two to a struct
									  lauberhorn_handler_t handler, 
									  uint32_t prog_num, uint32_t prog_ver, uint32_t proc_num,
									  uint16_t listen_port,
									  rpc_codec_t* rpc_codec);

/**
 * deregister an RPC service with lauberhorn
 *
 * removes the service from the dispatch table
 * and unregisters it from the portmapper.
 *
 * @ctx: lauebrhorn context. Must have been initialized
 * via lauberhorn_init()
 * @srv_id: service id to be deregistered
 * 
 * Returns 0 on success, - 1 on error
 */
LAUBERHORN_API int lauberhorn_dereg_srv(lauberhorn_t* ctx, int srv_id);

struct lauberhorn_worker;

/* opaque pointer to a worker handle returned by `lauberhorn_create_worker` */
typedef struct lauberhorn_worker* lauberhorn_worker_t;

// Per-worker init and clean-up callback functions from the user
typedef void (*lauberhorn_user_cb_t)(int);

/**
 * Spawn a worker thread that runs the event loop
 *
 * @ctx:  Runtime context returned by lauberhorn_init().  Must remain valid
 *        for the lifetime of the worker.
 * @init: Called once on thread start before entering the dispatch loop.
 *        Receives the worker index. May be NULL.
 * @fini: Called once on thread exit after the dispatch loop returns.
 *        Receives the same worker index. May be NULL.
 *
 * Returns: Opaque worker handle on success, or -1 on failure
 */
LAUBERHORN_API lauberhorn_worker_t lauberhorn_create_worker(
	lauberhorn_t* ctx, lauberhorn_user_cb_t init, lauberhorn_user_cb_t fini);


LAUBERHORN_API void lauberhorn_join_worker(lauberhorn_t* ctx,
										   lauberhorn_worker_t w);

struct lauberhorn_rpc_endpoint {
	const char *daddr; // IPv4 dotted-decimal address
	// Port and RPC program/version/procedure numbers are in host byte order.
	uint16_t dport; 
	uint32_t prog_num;
	uint32_t prog_ver;
	uint32_t proc_num;
	rpc_codec_t *rpc_codec; // how to serialize, deserialize stuff from this endpoint
    /* Zero initialize. Do not copy or mutate a registered endpoint. */
    struct {
        const void *self;
        lauberhorn_t *owner;
        uint64_t unresolved;
        uint32_t handle, daddr;
        uint16_t pid, sport;
    } _transport;
};

/* Register before workers start; destroy after all workers have joined.
 * Both return 0 or -1 with errno. Timeout does not release a reservation. */
LAUBERHORN_API int lauberhorn_rpc_endpoint_init(lauberhorn_t *ctx,
                                               lauberhorn_rpc_endpoint_t *ep);
LAUBERHORN_API int lauberhorn_rpc_endpoint_destroy(lauberhorn_t *ctx,
                                                  lauberhorn_rpc_endpoint_t *ep);


LAUBERHORN_API int lauberhorn_call_async(lauberhorn_rpc_endpoint_t *ep, void *payload,
                          size_t payload_len, lauberhorn_future_t *f);

// Helper to free a completion's data using the stored free callback
void lauberhorn_free_result(rpc_result_t *c);


#endif	// LAUBERHORN_H