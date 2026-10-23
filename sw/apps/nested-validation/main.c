/* Board acceptance fixture; private pool access is limited to injected exhaustion. */
#include "lauberhorn.h"
#include "lauberhorn/trace.h"
#include "worker.h"
#include "rpc.h"
#include "pending_call_table.h"
#include "fiber/scheduler.h"
#include <arpa/inet.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#if LH_BACKEND_VM
#include "vm/abi.h"
#include "lauberhorn/deadline.h"
#endif

#define PROGRAM 0x10234567u
#define VERSION 0x12345678u
#define PROCEDURE 0x23456789u
#define PEER_PROGRAM 0x456789abu
#define PEER_VERSION 0x3456789au
#define PORT 22345
#define PEER_PORT 23456
#define MAX_BYTES 1024
/* Wire words: application ID, operation, count, value, optional echo bytes. */
enum { ECHO, REMOTE, LOCAL, FORWARD_REMOTE, FORWARD_LOCAL, CANCEL, TIMEOUT,
       CAPACITY, INLINE_GUARD, FAIL, OVERSIZE, FIBERS_FULL, PENDING_FULL,
       REMOTE_ERROR, JOIN, KILL_PENDING, HOLD=100, DELAY=101, PEER_ERROR=102 };
struct message { size_t len; unsigned char data[MAX_BYTES]; };
static rpc_codec_t codec;
static lauberhorn_rpc_endpoint_t remote, local;
extern struct pending_call_table pending_call_table;
static struct pending_call *held[PENDING_CALL_LIMIT];
static unsigned get(struct message *m, unsigned i) {
    uint32_t v; memcpy(&v, m->data+4*i, 4); return ntohl(v);
}
static void put(struct message *m, unsigned i, unsigned v) {
    uint32_t n=htonl(v); memcpy(m->data+4*i, &n, 4);
}
static void *alloc_message(rpc_alloc_kind_t kind, void *p) { return calloc(1,sizeof(struct message)); }
static void free_message(void *p, rpc_free_kind_t kind, void *priv) { free(p); }
static int marshal(void *in, void *out, size_t size, size_t capacity, void *priv) {
    struct message *m=in;
    if (get(m,1)==OVERSIZE) return capacity+1;
    if (m->len>capacity) return -1;
    memcpy(out,m->data,m->len); return m->len;
}
static int unmarshal(void *in, void *out, size_t len, void *priv) {
    if (len<16 || len>MAX_BYTES || len%4) return -1;
    struct message *m=out; m->len=len; memcpy(m->data,in,len); return 0;
}
static lauberhorn_outcome_t bad(void) { return lauberhorn_fail(LAUBERHORN_RPC_SYSTEM_ERR); }
static void noop(void *p) {}
static void release_held(void *p) {
    for(unsigned i=0;i<PENDING_CALL_LIMIT;i++)
        pending_call_release(&pending_call_table,held[i]);
    free(p);
}
static lauberhorn_outcome_t nested(void *p, void *arg, lauberhorn_request_context_t token) {
    struct message *m=arg;
    unsigned op=get(m,1), n=get(m,2);
    lauberhorn_rpc_endpoint_t *ep=op==LOCAL?&local:&remote;
    lauberhorn_future_t futures[32]={0};
    lauberhorn_future_t *set[32];
    unsigned accepted=0,rejected=0;
    struct message child=*m;
    put(&child,1,(op==CANCEL || op==CAPACITY || op==KILL_PENDING)?HOLD:op==TIMEOUT?DELAY:op==REMOTE_ERROR?PEER_ERROR:ECHO);
    if(op!=CAPACITY && op!=JOIN && op!=KILL_PENDING) n=1;
    if(!n || n>32) return bad();
    for(unsigned i=0;i<n;i++) {
        if(op==JOIN) put(&child,3,get(m,3)+i);
        if(lauberhorn_call_async(ep,&child,sizeof(child),&futures[accepted])) {
            if(errno!=EAGAIN) return bad();
            rejected++; continue;
        }
        if (op==KILL_PENDING) {
            pending_call_start_timeout(futures[accepted].c,futures[accepted].xid,60000);
            rpc_deadline_refresh();
        }
        set[accepted]=&futures[accepted]; accepted++;
    }
    if(!accepted) return bad();
    if(op==CANCEL || op==CAPACITY) {
        for(unsigned i=0;i<accepted;i++)
            if(!lauberhorn_future_cancel(&futures[i])) return bad();
    }
    if(lauberhorn_future_join(set,accepted)) return bad();
    unsigned expected=(op==CANCEL || op==CAPACITY)?LAUBERHORN_RPC_CANCELLED:
        op==TIMEOUT?LAUBERHORN_RPC_TIMEOUT:op==REMOTE_ERROR?LAUBERHORN_RPC_ERROR:LAUBERHORN_RPC_SUCCESS;
    bool ok=true;
    for(unsigned i=0;i<accepted;i++) {
        ok &= futures[i].result.code==expected;
        if(expected==LAUBERHORN_RPC_SUCCESS) {
            if(op==JOIN) put(&child,3,get(m,3)+i);
            struct message *r=futures[i].result.data;
            ok &= r && r->len==child.len && !memcmp(r->data,child.data,child.len);
        }
        lauberhorn_free_result(&futures[i].result);
    }
    if((op==CANCEL || op==CAPACITY || op==TIMEOUT) && ep->_transport.unresolved) ok=false;
    if(op==CAPACITY) { put(m,2,accepted); put(m,3,rejected); }
    lauberhorn_trace_result(ok);
    return ok?lauberhorn_reply(m,sizeof(*m),NULL):bad();
}
static lauberhorn_outcome_t handle(void *p, void *arg, lauberhorn_request_context_t token) {
    struct message *m=arg;
    unsigned op=get(m,1);
    lauberhorn_trace_request_id(get(m,0));
    switch(op) {
    case ECHO: case OVERSIZE: return lauberhorn_reply(m,sizeof(*m),NULL);
    case REMOTE: case LOCAL: case CANCEL: case TIMEOUT: case CAPACITY: case REMOTE_ERROR: case JOIN: case KILL_PENDING:
        return lauberhorn_defer(nested,NULL,NULL);
    case FORWARD_REMOTE: case FORWARD_LOCAL:
        put(m,1,ECHO);
        return lauberhorn_forward(op==FORWARD_LOCAL?&local:&remote,m,sizeof(*m),NULL);
    case INLINE_GUARD: {
        lauberhorn_future_t f={0};
        if(lauberhorn_call_async(&remote,m,sizeof(*m),&f)!=-1 || errno!=EPERM) return bad();
        return lauberhorn_reply(m,sizeof(*m),NULL);
    }
    case FAIL: return lauberhorn_fail(LAUBERHORN_RPC_PROC_UNAVAIL);
    case FIBERS_FULL: {
        struct fiber *f;
        unsigned count=0;
        while((f=fiber_sched_acquire())) {
            fiber_prepare(f,noop,NULL,NULL); fiber_sched_submit(f); count++;
        }
        if(!count) return bad();
        return lauberhorn_defer(nested,NULL,NULL); /* rejection; queued noops release pool next turn */
    }
    case PENDING_FULL: {
        for(unsigned i=0;i<PENDING_CALL_LIMIT;i++) {
            held[i]=pending_call_acquire(&pending_call_table);
            if(!held[i]) abort();
        }
        struct message *copy=malloc(sizeof(*copy)); if(!copy) abort(); *copy=*m;
        return lauberhorn_forward(&remote,copy,sizeof(*copy),release_held);
    }
    default: return bad();
    }
}
int main(int argc,char **argv) {
    if(argc!=3) { fprintf(stderr,"usage: %s PEER_IP NIC_IP\n",argv[0]); return 2; }
    static rpc_ops_t ops={.marshal_call=marshal,.marshal_resp=marshal,
        .unmarshal_call=unmarshal,.unmarshal_resp=unmarshal,.alloc=alloc_message,.free=free_message};
    codec=(rpc_codec_t){.ops=&ops};
    lauberhorn_t ctx;
    if(lauberhorn_init(&ctx)) { perror("init"); return 1; }
    remote=(lauberhorn_rpc_endpoint_t){.daddr=argv[1],.dport=PEER_PORT,.prog_num=PEER_PROGRAM,
        .prog_ver=PEER_VERSION,.proc_num=PROCEDURE,.rpc_codec=&codec};
    local=(lauberhorn_rpc_endpoint_t){.daddr=argv[2],.dport=PORT,.prog_num=PROGRAM,
        .prog_ver=VERSION,.proc_num=PROCEDURE,.rpc_codec=&codec};
    if(lauberhorn_rpc_endpoint_init(&ctx,&remote) || lauberhorn_rpc_endpoint_init(&ctx,&local)) {
        perror("endpoint"); return 1;
    }
    if(lauberhorn_reg_srv(&ctx,(lauberhorn_handler_t){.func=handle},PROGRAM,VERSION,PROCEDURE,PORT,&codec)<0) {
        perror("service"); return 1;
    }
    if (lauberhorn_set_worker_count(&ctx, 1)) { perror("declare worker pool"); return 1; }
    lauberhorn_worker_t w=lauberhorn_create_worker(&ctx,NULL,NULL);
    if(!w) { perror("worker"); return 1; }
    printf("VALIDATION_READY remote_port=%u local_port=%u pid=%u\n",
        remote._transport.sport,local._transport.sport,remote._transport.pid); fflush(stdout);
#if LH_BACKEND_VM
    /* Optional read-only evidence for the VM's full-preemption acceptance.
     * This main thread never processes timers, retires calls or wakes fibers. */
    const char *state_path = getenv("LH_VM_STATE_PATH");
    if (state_path) {
        char *temporary = malloc(strlen(state_path) + 5);
        if (!temporary) return 1;
        sprintf(temporary, "%s.tmp", state_path);
        for (;;) {
            struct lh_vm_schedule_state s;
            struct lauberhorn_deadline_state d;
            if (ioctl(ctx.fd, LH_VM_SCHEDULE_STATE, &s) ||
                ioctl(ctx.fd, LAUBERHORN_IOCTL_GET_DEADLINE, &d)) return 1;
            FILE *f = fopen(temporary, "w");
            if (!f) return 1;
            fprintf(f, "{\"assigned\":%u,\"reserved\":%u,\"deadline_activations\":%llu,"
                "\"generation\":%llu,\"next_deadline_ns\":%llu}\n",
                s.assigned, s.reserved, (unsigned long long)s.deadline,
                (unsigned long long)d.generation, (unsigned long long)d.next_deadline_ns);
            if (fclose(f) || rename(temporary, state_path)) return 1;
            usleep(LH_VM_STATE_POLL_US);
        }
    }
#endif
    lauberhorn_join_worker(&ctx,w);
    return 0;
}
