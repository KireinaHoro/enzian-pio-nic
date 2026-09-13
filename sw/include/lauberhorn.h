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

typedef struct lauberhorn {
	/* to /dev/lauberhorn */
	int fd;
	uint8_t* parity_page;
} lauberhorn_t;

typedef void* lauberhorn_msg_t;

// Handler XIDs are host-order numbers; ECI performs network conversion.
typedef lauberhorn_msg_t (*lauberhorn_handler_func_t)(void* priv, lauberhorn_msg_t data, int xid);
typedef void (*lauberhorn_handler_free_t)(void* data);

LAUBERHORN_API int lauberhorn_init(lauberhorn_t* ctx);
LAUBERHORN_API void lauberhorn_fini(lauberhorn_t* ctx);

typedef enum {
	EXEC_FIBER = 0,
	EXEC_INLINE
} lauberhorn_exec_ctx_t; 

typedef struct {
	void(*cb)(rpc_result_t *reply, void*data); 
	void *data; 
	lauberhorn_exec_ctx_t mode;
} lauberhorn_cb_handler_t; 

typedef struct {
	void *data; 
	lauberhorn_handler_func_t func; 
	lauberhorn_handler_free_t free;
	lauberhorn_exec_ctx_t mode; 
} lauberhorn_handler_t; 

typedef enum {
    COMPLETION_FIBER,
    COMPLETION_CALLBACK
} completion_kind_t; 


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

typedef struct {
	const char *daddr; // IPv4 dotted-decimal address
	// Port and RPC program/version/procedure numbers are in host byte order.
	uint16_t dport; 
	uint32_t prog_num;
	uint32_t prog_ver;
	uint32_t proc_num;
	rpc_codec_t *rpc_codec; // how to serialize, deserialize stuff from this endpoint
} lauberhorn_rpc_endpoint_t; 


LAUBERHORN_API int lauberhorn_call_async(lauberhorn_rpc_endpoint_t *ep, void *payload,
                          size_t payload_len, lauberhorn_future_t *f);

LAUBERHORN_API int lauberhorn_call_cb(
			lauberhorn_rpc_endpoint_t *ep, 
			void *payload, 
			size_t payload_len, 
			lauberhorn_cb_handler_t cb
);

// Helper to free a completion's data using the stored free callback
void lauberhorn_free_result(rpc_result_t *c);


#endif	// LAUBERHORN_H