/* Exercise the actual shared RPC path, including codec ownership and failure. */
#define _GNU_SOURCE
#define LH_ENABLE_TRACING 0
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rpc_backend.h"
extern void xdr_free_func(void *,rpc_free_kind_t,void *);
static unsigned replies, status, token, frees;
static size_t reply_length;
static void send_reply(lauberhorn_pkt_desc_t *d,unsigned s,size_t n,struct server_trace_row *t) {
    (void)t; replies++; status=s; token=d->oncrpc_call_rx.request_token; reply_length=n;
}
static void counted_free(void *p,rpc_free_kind_t k,void *priv) {
    frees++; xdr_free_func(p,k,priv);
}
int main(void) {
    register_echoes();
    assert(!lookup(0,1,1)); assert(!lookup(ECHO_PROGRAM,2,1));
    assert(!lookup(ECHO_PROGRAM,ECHO_VERSION,99));
    lauberhorn_t context={0};
    struct lauberhorn_worker worker={.ctx=&context}; current_worker=&worker;
    struct activity_counter activity={0}; bench_handler_activity(&activity);
    uint8_t input[1432], output[1434];
    for(size_t i=0;i<sizeof(input);i++) input[i]=(i*31+17)&255;
    for(size_t i=0;i<sizeof(registrations)/sizeof(*registrations);i++) {
        struct registration *r=&registrations[i];
        assert(lookup(r->program,r->version,r->procedure)==r);
        rpc_ops_t ops=*r->service.codec.ops; ops.free=counted_free; r->service.codec.ops=&ops;
        for(unsigned repeat=0;repeat<3;repeat++) {
            memset(output,0xa5,sizeof(output));
            worker.dp_base=output+1; worker.dp.rx_view.data=input;
            lauberhorn_pkt_desc_t d={.payload_len=r->size,.oncrpc_call_rx={&r->dispatch,0xfeed0000+repeat}};
            unsigned before=replies, before_free=frees;
            rpc_handle_call(&worker,&d);
            assert(replies==before+1 && status==0 && token==d.oncrpc_call_rx.request_token);
            assert(reply_length==r->size && !memcmp(output+1,input,r->size));
            assert(output[0]==0xa5 && output[r->size+1]==0xa5 && frees==before_free+1);
        }
        if(r->size) {
            lauberhorn_pkt_desc_t d={.payload_len=r->size-1,.oncrpc_call_rx={&r->dispatch,17}};
            unsigned before_free=frees; uint64_t calls=atomic_load(&activity.calls);
            rpc_handle_call(&worker,&d);
            assert(status==LAUBERHORN_RPC_GARBAGE_ARGS && token==17 && reply_length==0);
            assert(frees==before_free+1 && atomic_load(&activity.calls)==calls);
        }
    }
    assert(atomic_load(&activity.calls)==12);
    puts("shared RPC backend: payloads, XIDs, dispatch, XDR failure and ownership passed");
}
