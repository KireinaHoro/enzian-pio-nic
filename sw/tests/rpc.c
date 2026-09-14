/* Actual runtime dispatch with an injected descriptor transport. This tests C
 * ownership and metadata; it is not an ECI or FPGA simulation. */
#include "worker.h"
#include "rpc.h"
#include <assert.h>
#include <string.h>
static struct lauberhorn_worker worker;
static lauberhorn_pkt_desc_t sent;
static bool immediate;
lauberhorn_worker_t worker_get_current(void) { return &worker; }
static void test_tx(void *base, lauberhorn_core_state_t *state, const lauberhorn_pkt_desc_t *desc) {
    sent = *desc;
    if (immediate) {
        memcpy(worker.dp.rx_buf, worker.dp.tx_buf, 4);
        lauberhorn_pkt_desc_t reply = {.type = TY_ONCRPC_REPLY_RX, .payload_len = 4,
            .oncrpc_reply_rx = {.pid = desc->oncrpc_call_tx.pid,
                .cookie = desc->oncrpc_call_tx.cookie, .xid = desc->oncrpc_call_tx.xid}};
        rpc_handle_packet(&worker, &reply);
    }
}
#define core_eci_tx test_tx
#include "../rt/rpc.c"
struct pending_call_table pending_call_table;
void lauberhorn_free_result(rpc_result_t *r) {
    if (r->data) { r->codec->ops->free(r->data, RPC_FREE_RESP, r->codec->priv); r->data = NULL; }
}
static int allocated, callbacks, marshal_error, alloc_error;
static void *allocate(rpc_alloc_kind_t kind, void *priv) { if (alloc_error) return NULL; allocated++; return calloc(1, 4); }
static void release(void *p, rpc_free_kind_t kind, void *priv) { allocated--; free(p); }
static int encode(void *in, void *out, size_t len, void *priv) {
    if (marshal_error) return -1;
    memcpy(out, in, 4); return 4;
}
static int decode(void *in, void *out, size_t len, void *priv) { memcpy(out, in, 4); return 0; }
static void callback(rpc_result_t *r, void *priv) {
    assert(r->code == LAUBERHORN_RPC_SUCCESS && *(int *)r->data == 123456);
    assert(priv == &callbacks); callbacks++;
}
static void noop(void *ctx) {}
static void error_callback(rpc_result_t *r, void *priv) {
    assert(r->code == LAUBERHORN_RPC_ERROR && !r->data);
}
static lauberhorn_rpc_endpoint_t ep;
static void future_pair(void *ctx) {
    lauberhorn_future_t a = {0}, b = {0};
    int av = 17, bv = 29;
    assert(!lauberhorn_call_async(&ep, &av, 4, &a));
    assert(!lauberhorn_call_async(&ep, &bv, 4, &b));
    lauberhorn_future_t *all[] = {&a, &b};
    lauberhorn_future_join(all, 2);
    assert(*(int *)a.result.data == av && *(int *)b.result.data == bv);
    lauberhorn_free_result(&a.result); lauberhorn_free_result(&b.result);
}
int main(void) {
    pending_call_table_init(&pending_call_table);
    fiber_sched_init();
    lauberhorn_t ctx = {0};
    char tx[LAUBERHORN_MTU], rx[LAUBERHORN_MTU];
    worker.ctx = &ctx; worker.dp.tx_buf = tx; worker.dp.rx_buf = rx;
    rpc_ops_t ops = {.marshal_call = encode, .unmarshal_resp = decode, .alloc = allocate, .free = release};
    rpc_codec_t codec = {.ops = &ops};
    ep = (lauberhorn_rpc_endpoint_t){.daddr = "invalid-but-already-cached", .dport = 23456,
        .prog_num = 0x12345678, .prog_ver = 0x23456789, .proc_num = 0x3456789a, .rpc_codec = &codec};
    int input = 123456;
    lauberhorn_cb_handler_t cb = {.cb = callback, .data = &callbacks, .mode = EXEC_INLINE};
    assert(lauberhorn_call_cb(&ep, &input, 4, cb) == -1 && errno == EINVAL);
    ep._transport.self = &ep; ep._transport.owner = &ctx;
    ep._transport.pid = 4321; ep._transport.sport = 54321; ep._transport.daddr = 0xc0a88183;
    marshal_error = 1;
    assert(lauberhorn_call_cb(&ep, &input, 4, cb) == -1 && !ep._transport.unresolved);
    marshal_error = 0; immediate = true;
    assert(!lauberhorn_call_cb(&ep, &input, 4, cb) && callbacks == 1);
    assert(sent.oncrpc_call_tx.pid == 4321 && sent.oncrpc_call_tx.sport == 54321);
    assert(sent.oncrpc_call_tx.daddr == 0xc0a88183 && sent.oncrpc_call_tx.dport == 23456);
    assert(sent.oncrpc_call_tx.cookie == sent.oncrpc_call_tx.xid);
    assert(sent.oncrpc_call_tx.prog_num == 0x12345678 && sent.oncrpc_call_tx.proc_num == 0x3456789a);
    cb.mode = EXEC_FIBER;
    assert(!lauberhorn_call_cb(&ep, &input, 4, cb) && callbacks == 1);
    assert(ep._transport.unresolved == 1);
    fiber_sched_run_ready();
    assert(callbacks == 2 && !allocated);
    struct fiber *held[NUM_FIBERS];
    int count = 0;
    while ((held[count] = fiber_sched_acquire())) {
        if (++count == NUM_FIBERS) break;
    }
    assert(count == NUM_FIBERS && !fiber_sched_acquire());
    assert(!lauberhorn_call_cb(&ep, &input, 4, cb));
    assert(callbacks == 2 && ep._transport.unresolved == 1);
    assert(atomic_load(&pending_call_get_by_cookie(&pending_call_table,
        sent.oncrpc_call_tx.cookie)->state) == SLOT_CALLBACK_READY);
    fiber_prepare(held[0], noop, NULL, NULL); fiber_sched_submit(held[0]);
    fiber_sched_run_ready();
    rpc_call_process_ready();
    fiber_sched_run_ready();
    assert(callbacks == 3 && !ep._transport.unresolved && !allocated);
    alloc_error = 1;
    cb.cb = error_callback; cb.mode = EXEC_INLINE;
    assert(!lauberhorn_call_cb(&ep, &input, 4, cb));
    assert(!ep._transport.unresolved && !allocated);
    alloc_error = 0;
    assert(lauberhorn_call_async(&ep, &input, 4, NULL) == -1 && errno == EINVAL);
    cb.cb = NULL;
    assert(lauberhorn_call_cb(&ep, &input, 4, cb) == -1 && errno == EINVAL);
    struct fiber *f = fiber_sched_acquire();
    assert(f); fiber_prepare(f, future_pair, NULL, NULL); fiber_sched_submit(f);
    fiber_sched_run_ready();
    assert(!allocated && !ep._transport.unresolved);
}
