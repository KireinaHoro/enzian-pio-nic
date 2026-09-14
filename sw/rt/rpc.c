#include <errno.h>
#include <stdatomic.h>

#include "lauberhorn.h"
#include "fiber/scheduler.h"
#include "lauberhorn_state.h"
#include "log.h"
#include "oncrpc.h"
#include "pending_call_table.h"

// go into . h public version
struct rpc_callback_fiber_ctx {
    rpc_result_t result;
    lauberhorn_cb_handler_t handler;
    lauberhorn_rpc_endpoint_t *endpoint;
};

struct rpc_call_fiber_ctx {
    lauberhorn_pkt_desc_t pkt_desc;
    void *unmarshaled_data;
    // filled on return and sent out in on_fiber_done
    void *result;
};

// return pointer to unmarshaled reply
static void *unmarshal_reply(void *data, size_t len, rpc_codec_t *codec) {
    void *data_unmarshaled = codec->ops->alloc(RPC_ALLOC_RESP, codec->priv);
    if (!data_unmarshaled) return NULL;
    if (codec->ops->unmarshal_resp(data, data_unmarshaled, len, codec->priv) < 0) {
        codec->ops->free(data_unmarshaled, RPC_FREE_RESP, codec->priv);
        return NULL;
    }
    return data_unmarshaled;
}

// return pointer to unmarshaled call
static void *unmarshal_call(void *data, size_t len, rpc_codec_t *codec) {
    void *data_unmarshaled = codec->ops->alloc(RPC_ALLOC_CALL, codec->priv);
    if (!data_unmarshaled) return NULL;
    if (codec->ops->unmarshal_call(data, data_unmarshaled, len, codec->priv) < 0) {
        codec->ops->free(data_unmarshaled, RPC_FREE_CALL, codec->priv);
        return NULL;
    }
    return data_unmarshaled;
}

// execute rpc handler inline and return result
static lauberhorn_msg_t rpc_call_exec_inline(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    struct lauberhorn_hw_handler *hw = desc->oncrpc_call_rx.func_ptr;
    rpc_codec_t *codec = &hw->srv->codec;

    // unmarshal data
    void *unmarshaled_args = unmarshal_call(w->dp.rx_buf, desc->payload_len, codec);

    if (!unmarshaled_args) return NULL;

    // call handler
    lauberhorn_msg_t resp =
        hw->handler.func(hw->handler.data, unmarshaled_args, desc->oncrpc_call_rx.xid);

    // free unmarshaled call
    codec->ops->free(unmarshaled_args, RPC_FREE_CALL, codec->priv);
    return resp;
}

// send out reply via core_eci_tx on the given worker
static void rpc_reply_send(lauberhorn_msg_t msg, lauberhorn_pkt_desc_t *desc) {

    lauberhorn_worker_t w = worker_get_current();

    if (!msg) {
        LOG("no reply message to send for RPC call");
        return;
    }
    struct lauberhorn_hw_handler *hw = desc->oncrpc_reply_tx.func_ptr;
    rpc_codec_t *codec = &hw->srv->codec;

    // marshal reply into worker tx buffer
    int len = codec->ops->marshal_resp(msg, w->dp.tx_buf, desc->payload_len,
                                       codec->priv);

    if (len < 0 || len > LAUBERHORN_MTU - 66) {
        LOG("marshal failed for reply");
        return;
    }
    desc->type = TY_ONCRPC_REPLY_TX;
    desc->payload_len = len;

    core_eci_tx(w->dp_base, &w->dp, desc);
}

// function invoked when rpc call handler fiber exits
static void rpc_call_fiber_done(void *ctx) {

    struct rpc_call_fiber_ctx *f_ctx = ctx;
    rpc_reply_send(f_ctx->result, &f_ctx->pkt_desc);
    struct lauberhorn_hw_handler *hw =
            f_ctx->pkt_desc.oncrpc_call_rx.func_ptr;

    // free returned result
    hw->handler.free(f_ctx->result);
    rpc_codec_t *codec = &hw->srv->codec;

    // free args (unmarshaled call)
    codec->ops->free(f_ctx->unmarshaled_data, RPC_FREE_CALL, codec->priv);

    // ctx was allocated via malloc() on creation of fiber
    free(ctx);
}

// function invoked when rpc call is scheduled on fiber
static void rpc_call_fiber_start(void *ctx) {
    struct rpc_call_fiber_ctx *f_ctx = ctx;
    struct lauberhorn_hw_handler *hw = f_ctx->pkt_desc.oncrpc_call_rx.func_ptr;
    assert(hw && hw->handler.func && "no handler function for RPC Call");

    f_ctx->result = hw->handler.func(hw->handler.data, f_ctx->unmarshaled_data,
                                       f_ctx->pkt_desc.oncrpc_call_rx.xid);
}

// function invoked when callback is scheduled on fiber
static void rpc_callback_fiber_start(void *ctx) {
    struct rpc_callback_fiber_ctx *f_ctx = ctx;

    // invoke handler with result and free it after
    f_ctx->handler.cb(&f_ctx->result, f_ctx->handler.data);
    lauberhorn_free_result(&f_ctx->result);
    __atomic_fetch_sub(&f_ctx->endpoint->_transport.unresolved, 1, __ATOMIC_RELEASE);
    free(ctx);
}

// schedule rpc call handler to be executed in a new fiber
static void rpc_call_schedule_fiber(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    struct lauberhorn_hw_handler *hw = desc->oncrpc_call_rx.func_ptr;
    rpc_codec_t *codec = &hw->srv->codec;

    void *unmarshaled_args = unmarshal_call(w->dp.rx_buf, desc->payload_len, codec);
    if (!unmarshaled_args) return;
    struct rpc_call_fiber_ctx *ctx = malloc(sizeof(*ctx));
    struct fiber *f = ctx ? fiber_sched_acquire() : NULL;
    if (!f) {
        free(ctx);
        codec->ops->free(unmarshaled_args, RPC_FREE_CALL, codec->priv);
        LOG("failed to allocate handler fiber");
        return;
    }
    ctx->pkt_desc = *desc;
    ctx->unmarshaled_data = unmarshaled_args;

    // will be filled by rpc_call_fiber_start that runs in fiber and execs the handler
    ctx->result = NULL;
    fiber_prepare(f, rpc_call_fiber_start, rpc_call_fiber_done, ctx);
    fiber_sched_submit(f);
}

// function invoked on incoming rpc call
static void rpc_handle_call(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    struct lauberhorn_hw_handler *hw = desc->oncrpc_call_rx.func_ptr;

    switch (hw->handler.mode) {
        case EXEC_FIBER: {
            rpc_call_schedule_fiber(w, desc);
            break;
        }
        case EXEC_INLINE: {
            lauberhorn_msg_t resp = rpc_call_exec_inline(w, desc);
            rpc_reply_send(resp, desc);
            break;
        }
    }
}

// schedule callback handler to be executed in a new fiber
// frees the pending call entry
static bool rpc_callback_schedule_fiber(struct pending_call *call) {
    struct rpc_callback_fiber_ctx *ctx = malloc(sizeof(*ctx));
    struct fiber *f = ctx ? fiber_sched_acquire() : NULL;
    if (!f) {
        free(ctx);
        return false;
    }
    ctx->handler = call->completion.callback;
    ctx->endpoint = call->endpoint;
    __atomic_fetch_add(&ctx->endpoint->_transport.unresolved, 1, __ATOMIC_RELAXED);
    // copy result into context and release pending call slot
    pending_call_extract_result(call, &ctx->result);

    // submit new fiber to scheduler
    fiber_prepare(f, rpc_callback_fiber_start, NULL, ctx);
    fiber_sched_submit(f);
    return true;
}

// unconditionally deliver result via future / callback
// only call this if we set the call's state successfully to SLOT_FULFILLED
static void pending_call_settle(struct pending_call *c, rpc_result_t *reply) {

    // store result
    c->result = *reply;

    // wake fiber or invoke callback
    switch (c->kind) {
        // settle future
        case COMPLETION_FIBER: {
            //  wake up waiter. Do not free slot before
            // fiber has not taken the data out
            struct fiber_handle waiter = c->completion.fiber_handle;
            atomic_store_explicit(&c->state, SLOT_READY, memory_order_release);

            /* A coalesced notification still leaves the result owned by its
             * future. Only future consumption releases this slot. */
            fiber_wake(&waiter);
            return;
        }
        // invoke callback
        case COMPLETION_CALLBACK: {
            lauberhorn_cb_handler_t handler = c->completion.callback;

            switch (handler.mode) {
                case EXEC_FIBER: {
                    // schedules callback and frees entry
                    if (!rpc_callback_schedule_fiber(c)) {
                        atomic_store_explicit(&c->state, SLOT_CALLBACK_READY, memory_order_release);
                        return;
                    }
                    break;
                }
                case EXEC_INLINE: {
                    // execute inline and then release slot
                    handler.cb(&c->result, handler.data);
                    break;
                }
            }
        }
    }
    pending_call_release(&pending_call_table, c);
}

// function invoked on incoming rpc reply
static void rpc_handle_reply(lauberhorn_msg_t reply, lauberhorn_pkt_desc_t *desc) {

    // ECI converts wire XIDs to host order before completion delivery.
    uint32_t xid = desc->oncrpc_reply_rx.xid;
    struct pending_call *entry = pending_call_get_by_cookie(&pending_call_table, desc->oncrpc_reply_rx.cookie);
    if (!entry) {
        LOG("no pending call found for xid %d\n", xid);
        return;
    }

    // try to set call from PENGING -> FULFILLED
    // ensures that timer cannot time us out anymore
    if (!pending_call_try_claim_reply(entry, desc->oncrpc_reply_rx.cookie, xid)) {
        LOG("reply with xid: %d could not be fulfilled", xid);
        return;
    }

    void *unmarshaled = unmarshal_reply(reply, desc->payload_len, entry->codec);
    rpc_result_t result = {
        .data = unmarshaled,
        .codec = entry->codec,
        .code = unmarshaled ? LAUBERHORN_RPC_SUCCESS : LAUBERHORN_RPC_ERROR
    };
    pending_call_settle(entry, &result);
}

// handle incoming rpc message (call or reply)
void rpc_handle_packet(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    switch (desc->type) {
        case TY_ONCRPC_CALL_RX: {
            rpc_handle_call(w, desc);
            break;
        }
        case TY_ONCRPC_REPLY_RX: {
            rpc_handle_reply(w->dp.rx_buf, desc);
            break;
        }
        default:
            LOG("cannot handle packet of type %d\n", desc->type);
            return;
    }
}


// marshal and send out a call via curernt worker
static void rpc_send_call(uint32_t xid, lauberhorn_rpc_endpoint_t *ep, void *payload,
    size_t payload_len) {

    // ECI converts host-order RPC metadata to wire order in hardware.
    lauberhorn_pkt_desc_t desc = {
        .type = TY_ONCRPC_CALL_TX,
        .oncrpc_call_tx = {.xid = xid,
                           .cookie = xid,
                           .pid = ep->_transport.pid,
                           .dport = ep->dport,
                           .sport = ep->_transport.sport,
                           .daddr = ep->_transport.daddr,
                           .prog_num = ep->prog_num,
                           .proc_num = ep->proc_num,
                           .prog_ver = ep->prog_ver},
        .payload_len = payload_len};

    lauberhorn_worker_t w = worker_get_current();

    core_eci_tx(w->dp_base, &w->dp, &desc);
}

int lauberhorn_call_cb(lauberhorn_rpc_endpoint_t *ep, void *payload,
    size_t payload_len, lauberhorn_cb_handler_t cb) {
    if (!cb.cb || (cb.mode != EXEC_INLINE && cb.mode != EXEC_FIBER)) {
        errno = EINVAL; return -1;
    }

    lauberhorn_worker_t w = worker_get_current();
    if (!ep || ep->_transport.self != ep || !w || ep->_transport.owner != w->ctx) {
        errno = EINVAL;
        return -1;
    }
    int enc_len = ep->rpc_codec->ops->marshal_call(payload, w->dp.tx_buf,
                                                  payload_len, ep->rpc_codec->priv);
    if (enc_len < 0 || enc_len > LAUBERHORN_MTU - 82) {
        errno = EMSGSIZE;
        return -1;
    }
    struct pending_call *pc = pending_call_acquire(&pending_call_table);
    if (!pc) {
        errno = EAGAIN;
        LOG("async call failed");
        return -1;
    }

    pc->kind = COMPLETION_CALLBACK;
    pc->codec = ep->rpc_codec;
    pc->endpoint = ep;
    __atomic_fetch_add(&ep->_transport.unresolved, 1, __ATOMIC_RELAXED);
    pc->completion.callback = cb;

    uint32_t cookie = pending_call_generate_xid(pc);
    pending_call_arm_timeout(pc, 0);
    rpc_send_call(cookie, ep, payload, enc_len);
    pending_call_start_timeout(pc, cookie, 1000);
    return 0;
}

int lauberhorn_call_async(lauberhorn_rpc_endpoint_t *ep, void *payload,
                          size_t payload_len, lauberhorn_future_t *f) {
    if (!f) { errno = EINVAL; return -1; }


    lauberhorn_worker_t w = worker_get_current();
    if (!ep || ep->_transport.self != ep || !w || ep->_transport.owner != w->ctx) {
        errno = EINVAL;
        return -1;
    }
    int enc_len = ep->rpc_codec->ops->marshal_call(payload, w->dp.tx_buf,
                                                  payload_len, ep->rpc_codec->priv);
    if (enc_len < 0 || enc_len > LAUBERHORN_MTU - 82) {
        errno = EMSGSIZE;
        return -1;
    }
    struct pending_call *pc = pending_call_acquire(&pending_call_table);

    if (!pc) {
        errno = EAGAIN;
        LOG("async call failed");
        return -1;
    }

    pc->codec = ep->rpc_codec;
    pc->endpoint = ep;
    __atomic_fetch_add(&ep->_transport.unresolved, 1, __ATOMIC_RELAXED);
    pc->kind = COMPLETION_FIBER;
    pc->completion.fiber_handle = fiber_sched_current_handle();

    // bind future to pending call
    f->c = pc;
    f->xid = pending_call_generate_xid(pc);

    uint32_t cookie = f->xid;
    pending_call_arm_timeout(pc, 0);
    rpc_send_call(cookie, ep, payload, enc_len);
    pending_call_start_timeout(pc, cookie, 1000);

    return 0;
}

static bool rpc_call_try_timeout(struct pending_call *c, struct timespec *now) {

    /* if fibers exit without awaiting calls: check
    for now not implemented
    if (c->kind == COMPLETION_FIBER) {
        if (fiber_handle_is_stale(c->completion.fiber_handle)) {
        // try to set to SLOT_FREE
        }
    }
    */

    // revalidate timeout under lock
    pthread_spin_lock(&c->lock);
    // verify timeout again under lock
    if (atomic_load(&c->state) == SLOT_PENDING && pending_call_has_expired(c, now)) {
        // mark as fulfilled
        atomic_store_explicit(&c->state, SLOT_FULFILLED, memory_order_relaxed);
        pthread_spin_unlock(&c->lock);

        rpc_result_t result = {
            .codec = NULL,
            .data = NULL,
            .code = LAUBERHORN_RPC_TIMEOUT
        };

        pending_call_settle(c, &result);
        return true;
    }
    pthread_spin_unlock(&c->lock);
    return false;
}

void rpc_call_process_timeout() {

    struct timespec t_now = now();
    struct pending_call *e;

    pending_call_table_for_each(&pending_call_table, e) {
        rpc_call_try_timeout(e, &t_now);
    }
}

/* Retry deferred fiber callbacks without losing their result or running them
 * inline on a scheduler stack. A slot lock assigns one retry owner. */
bool rpc_call_process_ready(void) {
    bool scheduled = false;
    struct pending_call *c;
    pending_call_table_for_each(&pending_call_table, c) {
        pthread_spin_lock(&c->lock);
        bool retry = atomic_load_explicit(&c->state, memory_order_acquire) == SLOT_CALLBACK_READY;
        if (retry) atomic_store(&c->state, SLOT_FULFILLED);
        pthread_spin_unlock(&c->lock);
        if (!retry) continue;
        if (rpc_callback_schedule_fiber(c)) {
            pending_call_release(&pending_call_table, c);
            scheduled = true;
        } else atomic_store_explicit(&c->state, SLOT_CALLBACK_READY, memory_order_release);
    }
    return scheduled;
}
