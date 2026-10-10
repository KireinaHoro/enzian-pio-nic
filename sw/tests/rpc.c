#define _GNU_SOURCE
/* Runtime ownership tests with an injected transport; not FPGA/ECI evidence. */
#include "worker.h"
#include "rpc.h"
#include "lauberhorn/trace.h"
#include <assert.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include "../kmod/ioctl.h"
static int retired_calls;
static bool retirement_error;
static int test_ioctl(int fd, unsigned long command, void *arg) {
    if (command != LAUBERHORN_IOCTL_RETIRE_CALL || !arg) abort();
    retired_calls++;
    if (retirement_error) { errno = EIO; return -1; }
    return 0;
}
static struct lauberhorn_worker worker;
static lauberhorn_pkt_desc_t sent, child;
static int sends, replies;
static bool immediate;
static unsigned reply_status;
static bool reject_tx, dma_error;
lauberhorn_worker_t worker_get_current(void) { return &worker; }
static int test_tx(void *base, lauberhorn_core_state_t *state, const lauberhorn_pkt_desc_t *desc) {
    sent = *desc; sends++;
    state->tx_reserved = false;
    if (!worker.ctx->_watchdog_enabled) assert(!desc->execution && desc->type != TY_EXECUTION_CONTROL);
    if (desc->type == TY_ONCRPC_REPLY_TX) { replies++; return CORE_TX_ACCEPTED; }
    if (reject_tx) return CORE_TX_RETRY;
    child = *desc;
    if (immediate) {
        memcpy(worker.dp.rx_view.data, worker.dp.tx_buf, 4);
        lauberhorn_pkt_desc_t reply = {.type = TY_ONCRPC_REPLY_RX, .payload_len = 4,
            .oncrpc_reply_rx = {.pid = desc->oncrpc_call_tx.pid, .status = reply_status,
                .cookie = desc->oncrpc_call_tx.cookie, .xid = desc->oncrpc_call_tx.xid}};
        rpc_handle_packet(&worker, &reply);
    }
    return dma_error ? CORE_TX_ERROR : CORE_TX_ACCEPTED;
}
static void *test_tx_get(void *base, lauberhorn_core_state_t *state, size_t capacity) {
    assert(!state->tx_reserved);
    state->tx_reserved = true;
    state->tx_capacity = capacity;
    return state->tx_buf;
}
#define core_eci_tx_cancel core_eci_tx_finish
#define core_eci_tx_get_buffer test_tx_get
#define core_eci_tx_commit test_tx
#define ioctl test_ioctl
#include "../rt/rpc.c"
#undef ioctl
/* Keep test assertions and their side effects in the NDEBUG runtime build. */
#undef assert
#define assert(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); abort(); } } while (0)
struct pending_call_table pending_call_table;
void lauberhorn_free_result(rpc_result_t *r) {
    if (r->data) { r->codec->ops->free(r->data, RPC_FREE_RESP, r->codec->priv); r->data = NULL; }
}
static int allocated, destroyed, encoded_count, decoded_count, marshal_error, alloc_error, ran;
static void *allocate(rpc_alloc_kind_t kind, void *priv) { if (alloc_error) return NULL; allocated++; return calloc(1, 4); }
static void release(void *p, rpc_free_kind_t kind, void *priv) { assert(p); allocated--; free(p); }
static int encode(void *in, void *out, size_t len, size_t capacity, void *priv) {
    encoded_count++;
    if (marshal_error || capacity < 4 || !in) return -1;
    memcpy(out, in, 4); return 4;
}
static int decode_int(void *in, void *out, size_t len, void *priv) {
    decoded_count++; if (len < 4) return -1; memcpy(out, in, 4); return 0;
}
static void destroy(void *p) { destroyed++; free(p); }
static void destroy_defer(void *p) {
    assert(allocated == 1); /* decoded request outlives deferred cleanup */
    destroy(p);
}
static lauberhorn_rpc_endpoint_t ep;
static void cancel_pending(void *ctx) {
    lauberhorn_future_t f = {0};
    int value = 17;
    immediate = false;
    assert(!lauberhorn_call_async(&ep, &value, 4, &f));
    assert(lauberhorn_future_cancel(&f));
    assert(!lauberhorn_future_cancel(&f) && errno == EALREADY);
    assert(!lauberhorn_future_await(&f));
    assert(f.result.code == LAUBERHORN_RPC_CANCELLED && !ep._transport.unresolved);
    assert(!lauberhorn_future_cancel(&f) && errno == EINVAL);
    immediate = true;
    assert(!lauberhorn_call_async(&ep, &value, 4, &f));
    assert(!lauberhorn_future_cancel(&f) && errno == EALREADY);
    assert(!lauberhorn_future_await(&f));
    lauberhorn_free_result(&f.result);
    immediate = false;
    retirement_error = true;
    assert(!lauberhorn_call_async(&ep, &value, 4, &f));
    assert(!lauberhorn_future_cancel(&f) && errno == EIO);
    assert(!lauberhorn_future_await(&f));
    assert(f.result.code == LAUBERHORN_RPC_ERROR && ep._transport.unresolved == 1);
    retirement_error = false;
    ep._transport.unresolved = 0; /* failed retirement requires process cleanup */
}
static void future_pair(void *ctx) {
    lauberhorn_future_t a = {0}, b = {0};
    int av = 17, bv = 29;
    reject_tx = true;
    assert(lauberhorn_call_async(&ep, &av, 4, &a) == -1 && errno == EAGAIN);
    assert(!a.c && !ep._transport.unresolved);
    reject_tx = false;
    assert(!lauberhorn_call_async(&ep, &av, 4, &a));
    assert(!lauberhorn_call_async(&ep, &bv, 4, &b));
    lauberhorn_future_t *duplicate[] = {&a, &a};
    assert(lauberhorn_future_join(duplicate, 2) == -1 && errno == EINVAL);
    lauberhorn_future_t *all[] = {&a, &b};
    assert(!lauberhorn_future_join(all, 2));
    if (!reply_status) assert(*(int *)a.result.data == av && *(int *)b.result.data == bv);
    else {
        lauberhorn_rpc_status_t expected = reply_status == 5 ?
            LAUBERHORN_RPC_SYSTEM_ERROR : LAUBERHORN_RPC_ERROR;
        assert(a.result.code == expected && !a.result.data);
        assert(b.result.code == expected && !b.result.data);
    }
    lauberhorn_free_result(&a.result); lauberhorn_free_result(&b.result);
    assert(lauberhorn_future_await(&a) == -1 && errno == EINVAL);
}
static void indeterminate_send(void *ctx) {
    lauberhorn_future_t f = {0};
    int value = 42;
    dma_error = true;
    assert(!lauberhorn_call_async(&ep, &value, sizeof(value), &f));
    assert(!lauberhorn_future_await(&f));
    if (immediate) {
        assert(f.result.code == LAUBERHORN_RPC_SUCCESS);
        assert(!ep._transport.unresolved);
    } else {
        assert(f.result.code == LAUBERHORN_RPC_ERROR);
        assert(ep._transport.unresolved == 1);
    }
    lauberhorn_free_result(&f.result);
    dma_error = false;
}
static lauberhorn_outcome_t deferred(void *data, void *req, lauberhorn_request_context_t context) {
    assert(context == 0x1234);
    assert(fiber_in_context()); ran++;
    lauberhorn_trace_request_id((unsigned)*(int *)req);
    return lauberhorn_reply(req, 4, NULL); /* args survive serialization */
}
static int mode;
static lauberhorn_outcome_t handler(void *data, void *req, lauberhorn_request_context_t context) {
    assert(context == 0x1234);
    assert(!fiber_in_context());
    lauberhorn_trace_request_id((unsigned)*(int *)req);
    if (mode == 1) return lauberhorn_defer(deferred, malloc(1), destroy_defer);
    if (mode == 2) return lauberhorn_forward(&ep, req, 4, NULL);
    if (mode == 4) {
        int *reply = malloc(sizeof(*reply)); *reply = *(int *)req;
        return lauberhorn_reply(reply, sizeof(*reply), destroy);
    }
    if (mode == 3) return lauberhorn_fail(LAUBERHORN_RPC_PROC_UNAVAIL);
    return lauberhorn_reply(req, 4, NULL);
}
static void noop(void *p) {}
int main(void) {
    pending_call_table_init(&pending_call_table);
    fiber_sched_init();
    lauberhorn_t ctx = {0};
    char tx[LAUBERHORN_MTU], rx[LAUBERHORN_MTU];
    worker.ctx = &ctx; worker.dp.tx_buf = tx; worker.dp.rx_view.data = rx;
    rpc_ops_t ops = {.marshal_call = encode, .marshal_resp = encode,
        .unmarshal_call = decode_int, .unmarshal_resp = decode_int, .alloc = allocate, .free = release};
    rpc_codec_t codec = {.ops = &ops};
    ep = (lauberhorn_rpc_endpoint_t){.daddr = "cached", .dport = 23456,
        .prog_num = 0x12345678, .prog_ver = 0x23456789, .proc_num = 0x3456789a, .rpc_codec = &codec};
    ep._transport.self = &ep; ep._transport.owner = &ctx;
    ep._transport.pid = 4321; ep._transport.sport = 54321; ep._transport.daddr = 0xc0a88183;
    int input = 123456;
    lauberhorn_future_t invalid = {0}, saved = invalid;
    assert(lauberhorn_call_async(&ep, &input, 4, &invalid) == -1 && errno == EPERM);
    lauberhorn_future_t *set[] = {&invalid};
    assert(lauberhorn_future_await(&invalid) == -1 && errno == EPERM);
    assert(lauberhorn_future_join(set, 1) == -1 && errno == EPERM);
    assert(lauberhorn_future_select(set, 1) == -1 && errno == EPERM);
    assert(!lauberhorn_future_cancel(&invalid) && errno == EPERM);
    assert(!memcmp(&invalid, &saved, sizeof(saved)) && !encoded_count && !sends && !ep._transport.unresolved);
    struct srv srv = {.codec = codec};
    struct lauberhorn_hw_handler hw = {.srv = &srv, .handler = {.func = handler}};
    lauberhorn_pkt_desc_t req = {.type = TY_ONCRPC_CALL_RX, .payload_len = 4,
        .oncrpc_call_rx = {.func_ptr = &hw, .request_token = 0x1234}};
    memcpy(rx, &input, 4);
    rpc_handle_packet(&worker, &req);
    assert(replies == 1 && !allocated && !sent.oncrpc_reply_tx.status && *(int *)tx == input);
    mode = 1;
    rpc_handle_packet(&worker, &req);
    assert(allocated == 1 && !ran && !destroyed && replies == 1);
    fiber_sched_run_ready();
    assert(!allocated && ran == 1 && destroyed == 1 && replies == 2);
    struct fiber *held[NUM_FIBERS];
    for (int i = 0; i < NUM_FIBERS; ++i) { held[i] = fiber_sched_acquire(); assert(held[i]); }
    assert(!fiber_sched_acquire());
    rpc_handle_packet(&worker, &req); /* rejection destroys both args and context */
    assert(!allocated && destroyed == 2 && replies == 3 && sent.oncrpc_reply_tx.status == 5);
    mode = 0;
    rpc_handle_packet(&worker, &req); /* inline unaffected by exhaustion */
    assert(!allocated && replies == 4 && !sent.oncrpc_reply_tx.status);
    mode = 2;
    rpc_handle_packet(&worker, &req); /* forward also works without a fiber */
    assert(!allocated && ep._transport.unresolved == 1 && replies == 4);
    assert(child.oncrpc_call_tx.pid == 4321 && child.oncrpc_call_tx.sport == 54321);
    assert(child.oncrpc_call_tx.prog_num == 0x12345678);
    lauberhorn_pkt_desc_t completion = {.type = TY_ONCRPC_REPLY_RX, .payload_len = 4,
        .oncrpc_reply_rx = {.cookie = child.oncrpc_call_tx.cookie, .xid = child.oncrpc_call_tx.xid}};
    rpc_handle_packet(&worker, &completion);
    assert(replies == 5 && sent.oncrpc_reply_tx.request_token == 0x1234 && !ep._transport.unresolved);
    rpc_handle_packet(&worker, &completion); /* duplicate does not reply twice */
    assert(replies == 5);
    rpc_handle_packet(&worker, &req);
    completion.oncrpc_reply_rx.cookie = child.oncrpc_call_tx.cookie;
    completion.oncrpc_reply_rx.xid = child.oncrpc_call_tx.xid;
    completion.oncrpc_reply_rx.status = 3;
    rpc_handle_packet(&worker, &completion);
    assert(replies == 6 && sent.oncrpc_reply_tx.status == 3 && !ep._transport.unresolved);
    for (int i = 0; i < NUM_FIBERS; ++i) { fiber_prepare(held[i], noop, NULL, NULL); fiber_sched_submit(held[i]); }
    fiber_sched_run_ready();
    immediate = true;
    for (reply_status = 0; reply_status <= 5; ++reply_status) {
        struct fiber *f = fiber_sched_acquire(); assert(f);
        int before = decoded_count;
        fiber_prepare(f, future_pair, NULL, NULL); fiber_sched_submit(f); fiber_sched_run_ready();
        assert(!allocated && !ep._transport.unresolved);
        if (reply_status) assert(decoded_count == before);
    }
    reply_status = 0;
    struct fiber *cancel_fiber = fiber_sched_acquire(); assert(cancel_fiber);
    fiber_prepare(cancel_fiber, cancel_pending, NULL, NULL);
    fiber_sched_submit(cancel_fiber); fiber_sched_run_ready();
    for (int delivery = 1; delivery >= 0; --delivery) {
        immediate = delivery;
        struct fiber *f = fiber_sched_acquire(); assert(f);
        fiber_prepare(f, indeterminate_send, NULL, NULL); fiber_sched_submit(f); fiber_sched_run_ready();
    }
    // Only the fixture resets this quarantine. Production requires B4 retirement.
    ep._transport.unresolved = 0;
    struct pending_call *held_calls[PENDING_CALL_LIMIT];
    for (int i = 0; i < PENDING_CALL_LIMIT; ++i) {
        held_calls[i] = pending_call_acquire(&pending_call_table); assert(held_calls[i]);
    }
    mode = 2; rpc_handle_packet(&worker, &req);
    assert(sent.oncrpc_reply_tx.status == 5 && !ep._transport.unresolved && !allocated);
    for (int i = 0; i < PENDING_CALL_LIMIT; ++i)
        pending_call_release(&pending_call_table, held_calls[i]);
    int before_destroy = destroyed;
    mode = 4; rpc_handle_packet(&worker, &req);
    assert(destroyed == before_destroy + 1 && !allocated);
    marshal_error = 1; rpc_handle_packet(&worker, &req);
    assert(destroyed == before_destroy + 2 && !allocated && sent.oncrpc_reply_tx.status == 5);
    marshal_error = 0;
    mode = 3; rpc_handle_packet(&worker, &req);
    assert(sent.oncrpc_reply_tx.status == 3 && !allocated);
    mode = 0; marshal_error = 1; rpc_handle_packet(&worker, &req);
    assert(sent.oncrpc_reply_tx.status == 5 && !allocated);
    marshal_error = 0; alloc_error = 1; rpc_handle_packet(&worker, &req);
    assert(sent.oncrpc_reply_tx.status == 5 && !allocated);
    alloc_error = 0; req.payload_len = 1; rpc_handle_packet(&worker, &req);
    assert(sent.oncrpc_reply_tx.status == 4 && !allocated);
    req.payload_len = 4; mode = 2; immediate = false; reject_tx = true;
    rpc_handle_packet(&worker, &req);
    assert(sent.oncrpc_reply_tx.status == 5 && !ep._transport.unresolved && !allocated);
    reject_tx = false;
    rpc_handle_packet(&worker, &req);
    struct pending_call *timed = pending_call_get_by_cookie(&pending_call_table, child.oncrpc_call_tx.cookie);
    completion.oncrpc_reply_rx.cookie = child.oncrpc_call_tx.cookie;
    completion.oncrpc_reply_rx.xid = child.oncrpc_call_tx.xid;
    completion.oncrpc_reply_rx.status = 0;
    pthread_spin_lock(&timed->lock);
    timed->sent_at = (struct timespec){0};
    pthread_spin_unlock(&timed->lock);
    int before_timeout = replies;
    rpc_call_process_timeout();
    assert(replies == before_timeout + 1 && sent.oncrpc_reply_tx.status == 5);
    assert(!ep._transport.unresolved && !allocated && retired_calls == 3);
    rpc_handle_packet(&worker, &completion);
    assert(replies == before_timeout + 1); /* late reply cannot resurrect parent */

    /* Atomic replies end execution on their existing TX, with no control TX. */
    ctx._watchdog_enabled = true;
    mode = 0; marshal_error = alloc_error = 0; req.payload_len = 4;
    int before = sends;
    rpc_handle_packet(&worker, &req);
    assert(sends == before + 1 && sent.type == TY_ONCRPC_REPLY_TX && sent.execution == 1);
    assert(!worker.execution_active && !worker.execution_pending);
    ctx._watchdog_enabled = false;

    /* Trace ownership survives deferred scheduling and forwarded completions.
     * Reusing the worker RX scratch must not overwrite either parent's row. */
    char path[] = "/tmp/lauberhorn-trace-XXXXXX";
    int fd = mkstemp(path); assert(fd >= 0); close(fd); unlink(path);
    assert(!setenv("LAUBERHORN_SERVER_TRACE_CSV", path, 1));
    char event_path[sizeof(path)+40];
    snprintf(event_path,sizeof(event_path),"%s.events",path);
    assert(!setenv("LAUBERHORN_EVENT_TRACE_CSV",event_path,1));
    server_trace_init(); assert(server_trace_enabled);
    server_trace_thread_init();
    for (mode = 0; mode < 3; ++mode) {
        server_activation = (struct server_activation_trace) {
            .tid=900, .worker=1, .index=7, .ioctl_exit_ns=100, .ready_ns=110
        };
        input = 100 + mode; memcpy(rx, &input, 4);
        server_trace_rx_begin(&worker.rx_trace, 2);
        worker.rx_trace.rx_unblock_ns = server_trace_stamp(&worker.rx_trace);
        worker.rx_trace.rx_exit_ns = server_trace_stamp(&worker.rx_trace);
        worker.worker_id = 2;
        rpc_handle_packet(&worker, &req);
        memset(&worker.rx_trace, 0, sizeof(worker.rx_trace));
        memset(&server_activation, 0, sizeof(server_activation));
        worker.worker_id = 3;
        if (mode == 1) fiber_sched_run_ready();
        if (mode == 2) {
            completion.oncrpc_reply_rx.cookie = child.oncrpc_call_tx.cookie;
            completion.oncrpc_reply_rx.xid = child.oncrpc_call_tx.xid;
            rpc_handle_packet(&worker, &completion);
            rpc_handle_packet(&worker, &completion); /* one terminal CSV row */
        }
    }
    server_trace_fini();
    FILE *csv = fopen(path, "r"); assert(csv);
    char line[4096]; assert(fgets(line, sizeof(line), csv));
    assert(strstr(line, "request_context") && strstr(line, "server_rx_unblock_ns"));
    for (int i = 0; i < 3; ++i) {
        assert(fgets(line, sizeof(line), csv));
        char expected[64]; snprintf(expected, sizeof(expected), "%d,,2,1,4,4,", 100+i);
        assert(!strncmp(line, expected, strlen(expected)));
        /* RX worker is retained; deferred/forwarded TX uses the completing worker. */
        snprintf(expected, sizeof(expected), ",1,4660,%d,%d,0,", i ? 3 : 2, i);
        assert(strstr(line, expected));
        assert(strstr(line, ",900,7,1,100,110,0,0,"));
    }
    assert(!fgets(line, sizeof(line), csv)); fclose(csv); unlink(path);
    char status_path[sizeof(path)+32];
    snprintf(status_path, sizeof(status_path), "%s.status.json", path);
    unlink(status_path);
    FILE *events = fopen(event_path,"r"); assert(events);
    assert(fgets(line,sizeof(line),events));
    bool resumed=false, suspended=false;
    while (fgets(line,sizeof(line),events)) {
        unsigned long long stamp,id,a,b;
        unsigned context,event; int tid,cpu;
        assert(sscanf(line,"%llu,%llu,%u,%d,%d,%u,%llu,%llu",
                      &stamp,&id,&context,&tid,&cpu,&event,&a,&b)==8);
        if (event==LH_TRACE_FIBER_RESUME || event==LH_TRACE_FIBER_SUSPEND) {
            assert(id==101 && context==4660 && stamp>0);
            resumed |= event==LH_TRACE_FIBER_RESUME;
            suspended |= event==LH_TRACE_FIBER_SUSPEND;
        }
    }
    assert(resumed && suspended);
    fclose(events); unlink(event_path);
    snprintf(event_path,sizeof(event_path),"%s.events.status.json",path);
    unlink(event_path);

}
