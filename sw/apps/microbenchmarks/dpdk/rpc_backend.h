/* DPDK transport projection for the existing synchronous LH RPC hot path.
 * No ECI emulation: only transport-owned descriptors, buffers and submission. */
#include "lauberhorn.h"
#include "server_trace.h"
#include "../bench.h"
#include "../echo_handler.h"
#include "echo_registry.h"
#define LAUBERHORN_MTU 1500
#define REPLY_CAPACITY (LAUBERHORN_MTU - 66)
#define LOG(...) fprintf(stderr,__VA_ARGS__)
struct srv { rpc_codec_t codec; };
struct lauberhorn_hw_handler { lauberhorn_handler_t handler; struct srv *srv; };
typedef struct {
    unsigned payload_len;
    struct { void *func_ptr; uint32_t request_token; } oncrpc_call_rx;
} lauberhorn_pkt_desc_t;
struct lauberhorn_worker {
    lauberhorn_t *ctx;
    int worker_id;
    bool execution_active;
    void *dp_base;
    struct { void *tx_buf; struct { void *data; } rx_view; } dp;
#if LH_ENABLE_TRACING
    struct server_trace_row rx_trace;
#endif
};
static _Thread_local struct lauberhorn_worker *current_worker;
static lauberhorn_worker_t worker_get_current(void) { return current_worker; }
static inline void *rpc_tx_reserve(lauberhorn_worker_t w, bool inlined) {
    (void)inlined; return w->dp_base;
}
static inline void rpc_tx_cancel(lauberhorn_worker_t w) { (void)w; }
#include "rpc_request.h"
static void send_reply(lauberhorn_pkt_desc_t *,unsigned,size_t,struct server_trace_row *);
static void fail_request(lauberhorn_pkt_desc_t *d,unsigned status,struct server_trace_row *t) {
    send_reply(d,status,0,t);
}
static bool consume_outcome(struct request *r,lauberhorn_outcome_t o,bool may_defer) {
    (void)may_defer;
    lauberhorn_worker_t w=worker_get_current();
    switch(o.kind) {
#include "rpc_reply.h"
    case LAUBERHORN_FAIL: fail_request(&r->desc,o.error,LH_TRACE_PTR(&r->trace)); break;
    default: fail_request(&r->desc,LAUBERHORN_RPC_SYSTEM_ERR,LH_TRACE_PTR(&r->trace)); break;
    }
    destroy_outcome(&o); return false;
}
#include "rpc_call.h"
struct registration {
    const char *name;
    uint32_t program,version,procedure;
    size_t size;
    struct bench_def definition;
    xdr_adapter_t adapter;
    struct srv service;
    struct lauberhorn_hw_handler dispatch;
};
#define ENTRY(name,size,proc,xdr) {#name,ECHO_PROGRAM,ECHO_VERSION,proc,size,{.echo=true,.body_size=size}, \
    {.call_xdrproc=(xdrproc_t)xdr,.resp_xdrproc=(xdrproc_t)xdr, \
     .call_size=size?size:1,.resp_size=size?size:1},{0},{0}},
static struct registration registrations[]={ ECHO_VARIANTS(ENTRY) };
#undef ENTRY
static void register_echoes(void) {
    for(size_t i=0;i<sizeof(registrations)/sizeof(*registrations);i++) {
        struct registration *r=&registrations[i];
        r->service.codec=oncrpc_codec(&r->adapter);
        r->service.codec.response_inline=r->size<=64;
        r->dispatch=(struct lauberhorn_hw_handler){
            .handler={.func=bench_handler,.data=&r->definition},.srv=&r->service};
    }
}
static struct registration *lookup(uint32_t program,uint32_t version,uint32_t procedure) {
    for(size_t i=0;i<sizeof(registrations)/sizeof(*registrations);i++) {
        struct registration *r=&registrations[i];
        if(r->program==program && r->version==version && r->procedure==procedure) return r;
    }
    return NULL;
}
