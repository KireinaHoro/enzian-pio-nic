#define _GNU_SOURCE
#include <sched.h>
#include <errno.h>
#include <stdatomic.h>
#include <string.h>

#include "lauberhorn.h"
#include "lauberhorn/trace.h"
#include "fiber/scheduler.h"
#include "lauberhorn_state.h"
#include "log.h"
#include "pending_call_table.h"
#include "ioctl.h"

#define CALL_CAPACITY (LAUBERHORN_MTU - 82)
#define REPLY_CAPACITY (LAUBERHORN_MTU - 66)

struct request {
    lauberhorn_pkt_desc_t desc;
    rpc_codec_t *codec;
    void *args;
#if LH_ENABLE_TRACING
    struct server_trace_row trace;
#endif
};
struct deferred_request {
    struct request request;
    lauberhorn_outcome_t defer, result;
};

static void destroy_outcome(lauberhorn_outcome_t *o) {
    if (o->destroy) o->destroy(o->data);
}
static void release_request(struct request *r) {
    r->codec->ops->free(r->args, RPC_FREE_CALL, r->codec->priv);
}
static void send_reply(lauberhorn_pkt_desc_t *parent, unsigned status, size_t len,
                       struct server_trace_row *trace) {
    lauberhorn_worker_t w = worker_get_current();
    lauberhorn_pkt_desc_t desc = {
        .type = TY_ONCRPC_REPLY_TX, .payload_len = len,
        .execution = worker_execution_take(w, true),
        .oncrpc_reply_tx = {.request_token = parent->oncrpc_call_rx.request_token,
                           .status = status}
    };
    if (!w->dp.tx_reserved && !core_eci_tx_get_buffer(w->dp_base, &w->dp, len <= 64)) {
        LOG("Cannot reserve reply TX"); return;
    }
#if LH_ENABLE_TRACING
    if (trace->enabled) trace->tx_cpu_id = sched_getcpu();
    trace->tx_enter_ns = server_trace_stage(trace, LH_PMU_tx_enter);
    int result = core_eci_tx_commit_traced(w->dp_base, &w->dp, &desc,
        trace->enabled ? &trace->tx_doorbell_ns : NULL,
        trace->enabled ? server_trace_clock : NULL);
    if (trace->enabled && trace->tx_doorbell_ns) trace->timestamp_calls++;
    trace->tx_exit_ns = server_trace_stage(trace, LH_PMU_tx_exit);
    trace->tx_worker_id = w->worker_id;
    trace->response_bytes = len;
    trace->status = status;
    trace->transport_status = result;
    trace->ok &= status == 0 && result == CORE_TX_ACCEPTED;
    server_trace_write(trace);
#else
    int result = core_eci_tx_commit(w->dp_base, &w->dp, &desc);
#endif
    if (result != CORE_TX_ACCEPTED)
        LOG("RPC reply transport failure; delivery is indeterminate");
}
static void fail_request(lauberhorn_pkt_desc_t *desc, unsigned status,
                         struct server_trace_row *trace) {
    /* These accepted-error variants have no extra ONC-RPC body. */
    if (status != 3 && status != 4 && status != 5) status = 5;
    send_reply(desc, status, 0, trace);
}
static void *decode(void *data, size_t len, rpc_codec_t *codec, bool call) {
    void *out = codec->ops->alloc(call ? RPC_ALLOC_CALL : RPC_ALLOC_RESP, codec->priv);
    if (!out) { errno = ENOMEM; return NULL; }
    int ret = call ? codec->ops->unmarshal_call(data, out, len, codec->priv)
                   : codec->ops->unmarshal_resp(data, out, len, codec->priv);
    if (ret < 0) {
        codec->ops->free(out, call ? RPC_FREE_CALL : RPC_FREE_RESP, codec->priv);
        errno = EINVAL;
        return NULL;
    }
    return out;
}

/* Submission owns a generation-tagged transport operation, independent of how
 * it is consumed. The caller publishes its consumer before making it visible. */
static struct pending_call *prepare_call(lauberhorn_rpc_endpoint_t *ep,
                                         void *payload, size_t len, int *encoded) {
    lauberhorn_worker_t w = worker_get_current();
    if (!ep || ep->_transport.self != ep || !w || ep->_transport.owner != w->ctx) {
        errno = EINVAL; return NULL;
    }
    w->dp.tx_buf = core_eci_tx_get_buffer(w->dp_base, &w->dp, false);
    if (!w->dp.tx_buf) { errno = EBUSY; return NULL; }
    *encoded = ep->rpc_codec->ops->marshal_call(payload, w->dp.tx_buf, len,
                                               CALL_CAPACITY, ep->rpc_codec->priv);
    if (*encoded < 0 || *encoded > CALL_CAPACITY) { core_eci_tx_cancel(&w->dp); errno = EMSGSIZE; return NULL; }
    struct pending_call *pc = pending_call_acquire(&pending_call_table);
    if (!pc) { core_eci_tx_cancel(&w->dp); errno = EAGAIN; return NULL; }
    pc->codec = ep->rpc_codec;
    pc->endpoint = ep;
    pc->result = (rpc_result_t){0};
    __atomic_fetch_add(&ep->_transport.unresolved, 1, __ATOMIC_RELAXED);
    return pc;
}
static void pending_call_settle(struct pending_call *, rpc_result_t *);
/* The caller exclusively owns a FULFILLED slot while control retirement runs.
 * A late completion cannot settle it again. Failure retains unresolved ownership
 * for process cleanup rather than claiming hardware has released the operation. */
static bool retire_call_hardware(struct pending_call *c) {
    lauberhorn_retire_call_t cmd = {
        .endpoint_handle = c->endpoint->_transport.handle,
        .cookie = pending_call_generate_xid(c)
    };
    if (ioctl(c->endpoint->_transport.owner->fd, LAUBERHORN_IOCTL_RETIRE_CALL, &cmd))
        return false;
    c->hardware_done = true;
    return true;
}
static int submit_call(struct pending_call *pc, int len) {
    lauberhorn_worker_t w = worker_get_current();
    lauberhorn_rpc_endpoint_t *ep = pc->endpoint;
    uint32_t cookie = pending_call_generate_xid(pc);
    lauberhorn_pkt_desc_t desc = {
        .type = TY_ONCRPC_CALL_TX,
        .execution = worker_execution_take(w, pc->kind == COMPLETION_FORWARD), .payload_len = len,
        .oncrpc_call_tx = {.xid = cookie, .cookie = cookie,
            .pid = ep->_transport.pid, .dport = ep->dport,
            .sport = ep->_transport.sport, .daddr = ep->_transport.daddr,
            .prog_num = ep->prog_num, .proc_num = ep->proc_num, .prog_ver = ep->prog_ver}
    };
    pending_call_arm_timeout(pc, 0);
    lauberhorn_trace_event(LH_TRACE_CALL_SUBMIT, cookie, len);
    int result = core_eci_tx_commit(w->dp_base, &w->dp, &desc);
    lauberhorn_trace_event(LH_TRACE_CALL_SUBMITTED, cookie, result);
    if (result == CORE_TX_RETRY) {
        /* This proves no hardware reservation/packet exists. */
        pc->hardware_done = true;
        pending_call_release(&pending_call_table, pc);
        errno = EAGAIN;
        return -1;
    }
    if (result != CORE_TX_ACCEPTED) {
        /* An indeterminate failure can race a reply. Settle only if this
         * generation is still pending; never free another consumer's state. */
        if (pending_call_try_claim(pc, cookie)) {
            rpc_result_t error = {.code = LAUBERHORN_RPC_ERROR};
            pending_call_settle(pc, &error);
        }
        return 0;
    }
    pending_call_start_timeout(pc, cookie, 1000);
    return 0;
}
int lauberhorn_call_async(lauberhorn_rpc_endpoint_t *ep, void *payload,
                          size_t len, lauberhorn_future_t *f) {
    if (!f) { errno = EINVAL; return -1; }
    /* This guard precedes encoding, allocation and endpoint accounting. */
    if (!fiber_in_context()) { errno = EPERM; return -1; }
    int encoded;
    struct pending_call *pc = prepare_call(ep, payload, len, &encoded);
    if (!pc) return -1;
    pc->kind = COMPLETION_FIBER;
    pc->completion.fiber_handle = fiber_sched_current_handle();
    *f = (lauberhorn_future_t){.owner = pc->completion.fiber_handle,
         .c = pc, .xid = pending_call_generate_xid(pc)};
    if (submit_call(pc, encoded)) { f->c = NULL; return -1; }
    return 0;
}

/* Returns true only when request ownership moved into a deferred fiber. */
static bool consume_outcome(struct request *, lauberhorn_outcome_t, bool);
static void deferred_start(void *arg) {
    struct deferred_request *d = arg;
#if LH_ENABLE_TRACING
    d->request.trace.handler_enter_ns = server_trace_stage(&d->request.trace, LH_PMU_handler_enter);
#endif
    d->result = d->defer.run(d->defer.data, d->request.args,
                            d->request.desc.oncrpc_call_rx.request_token);
#if LH_ENABLE_TRACING
    d->request.trace.handler_exit_ns = server_trace_stage(&d->request.trace, LH_PMU_handler_exit);
#endif
}
static void deferred_done(void *arg) {
    struct deferred_request *d = arg;
    consume_outcome(&d->request, d->result, false);
    destroy_outcome(&d->defer);
    release_request(&d->request);
    free(d);
}
static bool consume_outcome(struct request *r, lauberhorn_outcome_t o, bool may_defer) {
    lauberhorn_worker_t w = worker_get_current();
    switch (o.kind) {
    case LAUBERHORN_REPLY: {
#if LH_ENABLE_TRACING
        r->trace.marshal_enter_ns = server_trace_stage(&r->trace, LH_PMU_marshal_enter);
#endif
        size_t capacity = r->codec->response_inline ? 64 : REPLY_CAPACITY;
        w->dp.tx_buf = core_eci_tx_get_buffer(w->dp_base, &w->dp,
                                             r->codec->response_inline);
        if (!w->dp.tx_buf) { LOG("Cannot reserve response TX"); break; }
        int len = r->codec->ops->marshal_resp(o.data, w->dp.tx_buf, o.len,
                                             capacity, r->codec->priv);
#if LH_ENABLE_TRACING
        r->trace.marshal_exit_ns = server_trace_stage(&r->trace, LH_PMU_marshal_exit);
#endif
        if (len < 0 || (size_t)len > capacity) {
            core_eci_tx_cancel(&w->dp);
            fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace));
        }
        else send_reply(&r->desc, 0, len, LH_TRACE_PTR(&r->trace));
        break;
    }
    case LAUBERHORN_FAIL:
        fail_request(&r->desc, o.error, LH_TRACE_PTR(&r->trace));
        break;
    case LAUBERHORN_DEFER: {
        struct deferred_request *d = may_defer && o.run ? malloc(sizeof(*d)) : NULL;
        struct fiber *f = d ? fiber_sched_acquire() : NULL;
        if (!f) { free(d); fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace)); break; }
#if LH_ENABLE_TRACING
        r->trace.kind |= 1;
        r->trace.defer_enqueue_ns = server_trace_stamp(&r->trace);
        r->trace.handler_active_ns = 0;
#endif
        *d = (struct deferred_request){.request = *r, .defer = o};
        fiber_prepare(f, deferred_start, deferred_done, d);
#if LH_ENABLE_TRACING
        f->trace = &d->request.trace;
#endif
        fiber_sched_submit(f);
        return true; /* request and context have moved into d */
    }
    case LAUBERHORN_FORWARD: {
        /* v1 conservatively requires the same response encoder and its context.
         * No transformation is performed: successful child bytes are relayed. */
        if (!o.endpoint || !o.endpoint->rpc_codec || !o.endpoint->rpc_codec->ops ||
            o.endpoint->rpc_codec->ops->marshal_resp != r->codec->ops->marshal_resp ||
            o.endpoint->rpc_codec->priv != r->codec->priv) {
            fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace)); break;
        }
        int encoded;
        struct pending_call *pc = prepare_call(o.endpoint, o.data, o.len, &encoded);
        if (!pc) { fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace)); break; }
        pc->kind = COMPLETION_FORWARD;
        pc->completion.parent = r->desc;
#if LH_ENABLE_TRACING
        r->trace.kind |= 2;
        r->trace.forward_submit_ns = server_trace_stamp(&r->trace);
        pc->parent_trace = r->trace;
#endif
        if (submit_call(pc, encoded)) fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace));
        break;
    }
    default:
        fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace));
    }
    destroy_outcome(&o);
    return false;
}
static void rpc_handle_call(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    // Hardware begins on incoming call delivery; completion RX alone does not
    // imply a fiber resumed. Immediate inline-to-fiber deferral keeps this interval.
    w->execution_active = w->ctx->_watchdog_enabled;
    struct lauberhorn_hw_handler *hw = desc->oncrpc_call_rx.func_ptr;
    struct request r = {.desc = *desc, .codec = &hw->srv->codec
#if LH_ENABLE_TRACING
        , .trace = w->rx_trace
#endif
    };
#if LH_ENABLE_TRACING
    r.trace.request_context = desc->oncrpc_call_rx.request_token;
    r.trace.request_bytes = desc->payload_len;
    r.trace.unmarshal_enter_ns = server_trace_stage(&r.trace, LH_PMU_unmarshal_enter);
#endif
    r.args = decode(w->dp.rx_view.data, desc->payload_len, r.codec, true);
#if LH_ENABLE_TRACING
    r.trace.unmarshal_exit_ns = server_trace_stage(&r.trace, LH_PMU_unmarshal_exit);
#endif
    if (!r.args) { fail_request(desc, errno == ENOMEM ? LAUBERHORN_RPC_SYSTEM_ERR : LAUBERHORN_RPC_GARBAGE_ARGS, LH_TRACE_PTR(&r.trace)); return; }
#if LH_ENABLE_TRACING
    struct server_trace_row *previous = server_trace_inline;
    server_trace_inline = &r.trace;
    r.trace.handler_enter_ns = server_trace_stage(&r.trace, LH_PMU_handler_enter);
    r.trace.entry_enter_ns = r.trace.handler_enter_ns;
#endif
    lauberhorn_outcome_t outcome = hw->handler.func(hw->handler.data, r.args,
                                                 desc->oncrpc_call_rx.request_token);
#if LH_ENABLE_TRACING
    server_trace_event(&r.trace, LH_TRACE_ENTRY_RETURN, outcome.kind, 0);
    r.trace.handler_exit_ns = server_trace_stage(&r.trace, LH_PMU_handler_exit);
    r.trace.entry_exit_ns = r.trace.handler_exit_ns;
    r.trace.handler_active_ns = r.trace.handler_exit_ns - r.trace.handler_enter_ns;
    server_trace_inline = previous;
#endif
    if (!consume_outcome(&r, outcome, true)) release_request(&r);
}
static void pending_call_settle(struct pending_call *c, rpc_result_t *reply) {
    c->result = *reply;
    if (c->kind == COMPLETION_FORWARD) {
        /* Only timeout/error settlement uses this path. Success is raw relay. */
#if LH_ENABLE_TRACING
        c->parent_trace.forward_complete_ns = server_trace_stamp(&c->parent_trace);
#endif
        fail_request(&c->completion.parent, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&c->parent_trace));
        pending_call_release(&pending_call_table, c);
    } else {
        struct fiber_handle waiter = c->completion.fiber_handle;
        atomic_store_explicit(&c->state, SLOT_READY, memory_order_release);
        fiber_wake(&waiter);
    }
}
static void rpc_handle_reply(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    struct pending_call *c = pending_call_get_by_cookie(&pending_call_table,
                                                       desc->oncrpc_reply_rx.cookie);
    if (!c || !pending_call_try_claim_reply(c, desc->oncrpc_reply_rx.cookie,
                                             desc->oncrpc_reply_rx.xid)) return;
    if (c->kind == COMPLETION_FORWARD) {
#if LH_ENABLE_TRACING
        c->parent_trace.forward_complete_ns = server_trace_stamp(&c->parent_trace);
#endif
        if (desc->oncrpc_reply_rx.status) fail_request(&c->completion.parent, desc->oncrpc_reply_rx.status, LH_TRACE_PTR(&c->parent_trace));
        else if (desc->payload_len > REPLY_CAPACITY) fail_request(&c->completion.parent, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&c->parent_trace));
        else {
            w->dp.tx_buf = core_eci_tx_get_buffer(w->dp_base, &w->dp, desc->payload_len <= 64);
            if (!w->dp.tx_buf) { pending_call_release(&pending_call_table, c); return; }
            memcpy(w->dp.tx_buf, w->dp.rx_view.data, desc->payload_len);
            send_reply(&c->completion.parent, 0, desc->payload_len, LH_TRACE_PTR(&c->parent_trace));
        }
        pending_call_release(&pending_call_table, c);
        return;
    }
    void *data = desc->oncrpc_reply_rx.status ? NULL :
        decode(w->dp.rx_view.data, desc->payload_len, c->codec, false);
    rpc_result_t result = {.data = data, .codec = c->codec,
        .code = data ? LAUBERHORN_RPC_SUCCESS :
            desc->oncrpc_reply_rx.status == LAUBERHORN_RPC_SYSTEM_ERR ?
                LAUBERHORN_RPC_SYSTEM_ERROR : LAUBERHORN_RPC_ERROR};
    pending_call_settle(c, &result);
}
void rpc_handle_packet(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
    /* Record every delivered call/completion, so FPGA dispatch order can be
     * joined without confusing a nested completion with a new incoming call. */
#if LH_ENABLE_TRACING
    uint32_t identity = desc->type == TY_ONCRPC_CALL_RX ?
        desc->oncrpc_call_rx.request_token : desc->oncrpc_reply_rx.xid;
    server_trace_event(&w->rx_trace, LH_TRACE_RX_PACKET, desc->type, identity);
#endif
    switch (desc->type) {
    case TY_ONCRPC_CALL_RX: rpc_handle_call(w, desc); break;
    case TY_ONCRPC_REPLY_RX: rpc_handle_reply(w, desc); break;
    default: LOG("cannot handle packet of type %d", desc->type);
    }
}

static bool rpc_call_try_timeout(struct pending_call *c, struct timespec *now) {

    // revalidate timeout under lock
    pthread_spin_lock(&c->lock);
    // verify timeout again under lock
    if (atomic_load(&c->state) == SLOT_PENDING && pending_call_has_expired(c, now)) {
        // mark as fulfilled
        atomic_store_explicit(&c->state, SLOT_FULFILLED, memory_order_relaxed);
        pthread_spin_unlock(&c->lock);

        retire_call_hardware(c);

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

bool rpc_call_cancel(struct pending_call *c, uint32_t cookie) {
    if (!pending_call_try_claim(c, cookie)) { errno = EALREADY; return false; }
    bool retired = retire_call_hardware(c);
    rpc_result_t result = {.code = retired ? LAUBERHORN_RPC_CANCELLED : LAUBERHORN_RPC_ERROR};
    pending_call_settle(c, &result);
    return retired;
}

void rpc_call_process_timeout() {

    struct timespec t_now = now();
    struct pending_call *e;

    pending_call_table_for_each(&pending_call_table, e) {
        rpc_call_try_timeout(e, &t_now);
    }
}
