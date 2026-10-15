/* Bounded, closed-loop load. Included after sample, now(), and number().
 * Requests retain unique IDs and timestamps; responses may complete out of order.
 * This measures achieved load, not an open-loop offered-rate/SLO curve. */
static unsigned load_phase(uint16_t port, struct rte_mempool *pool,
        struct endpoint src, struct endpoint dst, const struct benchmark *bench,
        struct sample *samples, unsigned first, unsigned count, unsigned window,
        uint64_t gap, uint64_t timeout, uint64_t *unrelated, int *failed) {
    unsigned sent=0, done=0, oldest=0;
    const char *flows_env = getenv("LH_BENCH_FLOWS");
    unsigned flows = flows_env ? number(flows_env, 1024) : 1;
    if (!flows || src.port + flows > 65536) rte_exit(1,"invalid flow count\n");
    uint16_t source_port = src.port;
    uint64_t next_send=0;
    while(done<count && !*failed) {
        uint64_t clock=now();
        /* Limit each enqueue batch so RX is serviced under continuous load. */
        unsigned budget=32;
        while(sent<count && sent-done<window && budget-- && clock>=next_send) {
            struct rte_mbuf *tx=rte_pktmbuf_alloc(pool);
            if(!tx) rte_exit(1,"load TX allocation failed\n");
            uint8_t payload[1472];
            struct sample *s=&samples[first+sent];
            s->enter=now();
            size_t n=bench->encode(payload,sizeof(payload),first+sent+1);
            if(!n || n>sizeof(payload)) rte_exit(1,"load codec size\n");
            uint8_t *p=(uint8_t *)rte_pktmbuf_append(tx,42+n);
            if(!p) rte_exit(1,"load TX append failed\n");
            src.port = source_port + (first + sent) % flows;
            make_udp(p,&src,&dst,payload,n);
            s->ready=now();
            if(!rte_eth_tx_burst(port,0,&tx,1)) {
                rte_pktmbuf_free(tx);
                /* Preserve failed admission; never reduce the denominator. */
                s->status=2; s->submitted=s->exit=now();
                sent++; *failed=1; break;
            }
            s->submitted=now();
            next_send=s->enter+gap;
            sent++; clock=now();
        }
        struct rte_mbuf *rx[32];
        uint16_t n=rte_eth_rx_burst(port,0,rx,32);
        uint64_t received=n?now():0;
        for(uint16_t j=0;j<n;j++) {
            uint8_t scratch[2048]; size_t len=rte_pktmbuf_pkt_len(rx[j]);
            const uint8_t *data=len<=sizeof(scratch)?rte_pktmbuf_read(rx[j],0,len,scratch):NULL;
            const uint8_t *reply=NULL; size_t size=0;
            src.port = data && len >= 42 ? get16(data + 36) : source_port;
            int parsed=data?parse_udp(data,len,&src,&dst,&reply,&size):0;
            if(src.port < source_port || src.port >= source_port + flows) parsed = 0;
            if(!parsed) (*unrelated)++;
            else if(parsed!=1 || size<4) *failed=1;
            else {
                uint32_t id=get32(reply);
                if(id<=first || id>first+sent || src.port != source_port + (id - 1) % flows) *failed=1;
                else {
                    struct sample *s=&samples[id-1];
                    if(s->status || bench->validate(reply,size,id)!=1) {
                        if(!*failed) {
                            fprintf(stderr,"invalid_load_reply id=%u previous_status=%d bytes=%zu payload=",id,s->status,size);
                            for(size_t k=0;k<size && k<96;k++) fprintf(stderr,"%02x",reply[k]);
                            fputc('\n',stderr);
                        }
                        *failed=1;
                        if(!s->status) { s->status=3; s->received=received; s->exit=now(); }
                    } else {
                        s->received=received; s->status=1; s->exit=now(); done++;
                    }
                }
            }
            rte_pktmbuf_free(rx[j]);
        }
        while(oldest<sent && samples[first+oldest].status) oldest++;
        if(oldest<sent && now()-samples[first+oldest].enter>=timeout) *failed=1;
    }
    if(*failed) {
        uint64_t end=now();
        for(unsigned i=0;i<sent;i++) if(!samples[first+i].status) {
            samples[first+i].status=2; samples[first+i].exit=end;
        }
    }
    return sent;
}

/* Multi-core load generation. Each core owns its samples and TX queue. RSS RX
 * queues route validated replies through bounded MPSC rings to their owner.
 * Queue/core setup and the ready barrier precede the observation window. */
#include <stdatomic.h>
#include <rte_launch.h>
#include <rte_ring.h>
struct parallel_rx { uint64_t received; uint32_t id, offset, length; };
struct parallel_worker;
struct parallel_load {
    struct sample *samples;
    const struct benchmark *bench;
    struct rte_mempool *pool;
    struct endpoint src, dst;
    struct parallel_worker *workers;
    unsigned first, count, cores, flows, window;
    uint16_t port;
    uint64_t gap, timeout, start;
    atomic_uint ready, finished;
    atomic_int go, failed;
};
struct parallel_worker {
    struct parallel_load *load;
    struct rte_ring *inbox;
    unsigned index, count, sent, done;
    uint64_t unrelated;
} __rte_cache_aligned;

static void parallel_fail(struct parallel_load *load, const char *reason) {
    if(!atomic_exchange(&load->failed,1)) fprintf(stderr,"load_error=%s\n",reason);
}

static void parallel_reply(struct parallel_worker *worker, struct rte_mbuf *packet) {
    struct parallel_load *load=worker->load;
    struct parallel_rx *meta=rte_mbuf_to_priv(packet);
    unsigned slot=(meta->id-load->first-1)/load->cores;
    struct sample *s=&load->samples[meta->id-1];
    const uint8_t *payload=rte_pktmbuf_mtod_offset(packet,const uint8_t *,meta->offset);
    if(open_rate) {
        if(slot>=worker->sent || !s->submitted || s->status==OL_TX_FAILED || s->status==OL_MISSED) {
            parallel_fail(load,"reply for unsent request");
        } else {
            int valid=load->bench->validate(payload,meta->length,meta->id)==1;
            int state=ol_reply(s->status,s->enter,meta->received,load->timeout,valid);
            if(state==OL_MALFORMED) { s->status=state; parallel_fail(load,"malformed reply"); }
            else if(s->status==OL_ONTIME || s->status==OL_LATE) {
                s->duplicates++;
                /* Forwarding can reorder copies from different RX queues. */
                if(meta->received<s->received) {
                    s->received=meta->received;
                    s->status=ol_reply(OL_PENDING,s->enter,meta->received,load->timeout,true);
                }
            }
            else { s->received=meta->received; s->exit=now(); s->status=state; worker->done++; }
        }
    } else {
    if(slot>=worker->sent || s->status || meta->received<s->enter ||
       load->bench->validate(payload,meta->length,meta->id)!=1) {
        atomic_store(&load->failed,1);
        if(s->enter && !s->status) { s->status=3; s->received=meta->received; s->exit=now(); }
    } else {
        s->received=meta->received; s->status=1; s->exit=now(); worker->done++;
    }
    }
    rte_pktmbuf_free(packet);
}

static int parallel_poll(void *argument) {
    struct parallel_worker *worker=argument;
    struct parallel_load *load=worker->load;
    if(open_rate) open_cpus[worker->index]=sched_getcpu();
    unsigned limit=load->window/load->cores+(worker->index<load->window%load->cores);
    atomic_fetch_add(&load->ready,1);
    if(!worker->index) {
        while(atomic_load(&load->ready)!=load->cores) rte_pause();
        load->start=now()+(open_rate?10000000:0); atomic_store(&load->go,1);
    } else while(!atomic_load(&load->go)) rte_pause();
    uint64_t next=open_rate?ol_due(load->start,worker->index,open_rate):load->start+worker->index*load->gap;
    unsigned oldest=0; int announced=0;
    while(!atomic_load_explicit(&load->failed,memory_order_relaxed) &&
          atomic_load_explicit(&load->finished,memory_order_relaxed)<load->cores) {
        uint64_t clock=now(); unsigned budget=8;
        while(worker->sent<worker->count && (open_rate || worker->sent-worker->done<limit) &&
              budget-- && clock>=next) {
            unsigned id=load->first+worker->index+worker->sent*load->cores+1;
            struct sample *s=&load->samples[id-1];
            if(open_rate) {
                s->scheduled=ol_due(load->start,id-1,open_rate);
                uint64_t phase_end=load->start+open_warmup;
                if(s->scheduled>=phase_end) phase_end+=open_duration;
                if(clock>=phase_end || ol_missed(clock,load->start,id-1,open_rate,load->cores)) {
                    s->status=OL_MISSED; worker->sent++;
                    next=ol_due(load->start,worker->index+(uint64_t)worker->sent*load->cores,open_rate);
                    clock=now(); continue;
                }
            }
            struct rte_mbuf *tx=rte_pktmbuf_alloc(load->pool);
            if(!tx) { parallel_fail(load,"TX buffer exhaustion"); break; }
            uint8_t payload[1472]; s->enter=now();
            size_t size=load->bench->encode(payload,sizeof(payload),id);
            uint8_t *bytes=(uint8_t *)rte_pktmbuf_append(tx,42+size);
            if(!size || size>sizeof(payload) || !bytes) rte_exit(1,"parallel codec size\n");
            struct endpoint source=load->src;
            source.port+=(id-1)%load->flows;
            make_udp(bytes,&source,&load->dst,payload,size); s->ready=now();
            if(open_rate) {
                uint64_t phase_end=load->start+open_warmup;
                if(s->scheduled>=phase_end) phase_end+=open_duration;
                if(s->ready>=phase_end || ol_missed(s->ready,load->start,id-1,open_rate,load->cores)) {
                    rte_pktmbuf_free(tx); s->status=OL_MISSED; s->exit=s->ready;
                    worker->sent++;
                    next=ol_due(load->start,worker->index+(uint64_t)worker->sent*load->cores,open_rate);
                    break;
                }
            }
            if(!rte_eth_tx_burst(load->port,worker->index,&tx,1)) {
                rte_pktmbuf_free(tx); s->status=open_rate?OL_TX_FAILED:2; s->submitted=s->exit=now();
                worker->sent++; parallel_fail(load,"TX admission failure"); break;
            }
            s->submitted=now(); worker->sent++;
            next=open_rate?ol_due(load->start,worker->index+(uint64_t)worker->sent*load->cores,open_rate):s->enter+load->gap*load->cores;
            clock=now();
            if(open_rate) break; /* Never enqueue a catch-up burst. */
        }
        struct rte_mbuf *packets[32];
        unsigned received=rte_eth_rx_burst(load->port,worker->index,packets,32);
        uint64_t stamp=received?now():0;
        for(unsigned j=0;j<received;j++) {
            struct rte_mbuf *packet=packets[j];
            if(rte_pktmbuf_linearize(packet)) {
                parallel_fail(load,"invalid RX packet or transaction identity"); rte_pktmbuf_free(packet); continue;
            }
            const uint8_t *bytes=rte_pktmbuf_mtod(packet,const uint8_t *),*reply=NULL;
            size_t size=0;
            struct endpoint source=load->src;
            if(packet->pkt_len>=42) source.port=get16(bytes+36);
            int parsed=parse_udp(bytes,packet->pkt_len,&source,&load->dst,&reply,&size);
            if(source.port<load->src.port || source.port>=load->src.port+load->flows) parsed=0;
            if(!parsed) { worker->unrelated++; rte_pktmbuf_free(packet); continue; }
            unsigned id=size>=4?get32(reply):0;
            if(parsed<0 || id<=load->first || id>load->first+load->count ||
               source.port!=load->src.port+(id-1)%load->flows) {
                parallel_fail(load,"invalid RX packet or transaction identity"); rte_pktmbuf_free(packet); continue;
            }
            struct parallel_rx *meta=rte_mbuf_to_priv(packet);
            *meta=(struct parallel_rx){.received=stamp,.id=id,.offset=reply-bytes,.length=size};
            unsigned owner=(id-load->first-1)%load->cores;
            if(owner==worker->index) parallel_reply(worker,packet);
            else if(rte_ring_enqueue(load->workers[owner].inbox,packet)) {
                parallel_fail(load,"reply forwarding ring exhaustion"); rte_pktmbuf_free(packet);
            }
        }
        void *objects[32];
        unsigned n=rte_ring_dequeue_burst(worker->inbox,objects,32,NULL);
        for(unsigned j=0;j<n;j++) parallel_reply(worker,objects[j]);
        while(oldest<worker->sent && load->samples[load->first+worker->index+oldest*load->cores].status) oldest++;
        if(!open_rate && oldest<worker->sent && now()-load->samples[load->first+worker->index+oldest*load->cores].enter>=load->timeout)
            atomic_store(&load->failed,1);
        if(!announced && ((!open_rate && worker->done==worker->count) ||
           (open_rate && now()>=load->start+open_warmup+open_duration+load->timeout+10000000))) {
            atomic_fetch_add(&load->finished,1); announced=1;
        }
    }
    if(open_rate || atomic_load(&load->failed)) {
        uint64_t end=now();
        for(unsigned i=0;i<worker->sent;i++) {
            struct sample *s=&load->samples[load->first+worker->index+i*load->cores];
            if(!s->status) { s->status=OL_EXPIRED; s->exit=end; }
        }
    }
    return 0;
}

static unsigned parallel_phase(uint16_t port,struct rte_mempool *pool,
        struct endpoint src,struct endpoint dst,const struct benchmark *bench,
        struct sample *samples,unsigned first,unsigned count,unsigned window,
        uint64_t gap,uint64_t timeout,uint64_t *unrelated,int *failed,
        uint64_t *start,uint64_t *end) {
    if(!count) return 0;
    unsigned cores=rte_lcore_count();
    const char *text=getenv("LH_BENCH_FLOWS");
    unsigned flows=text?number(text,1024):64;
    if((!open_rate && window<cores) || flows<cores || src.port+flows>65536)
        rte_exit(1,"parallel load requires at least one flow/window slot per core\n");
    struct parallel_worker workers[8]={0};
    struct parallel_load load={.samples=samples,.bench=bench,.pool=pool,
        .src=src,.dst=dst,.workers=workers,.first=first,.count=count,.cores=cores,
        .flows=flows,.window=window,.port=port,.gap=gap,.timeout=timeout};
    for(unsigned i=0;i<cores;i++) {
        workers[i].load=&load;workers[i].index=i;
        workers[i].count=count/cores+(i<count%cores);
        char name[32];snprintf(name,sizeof(name),"bench-replies-%u",i);
        workers[i].inbox=rte_ring_create(name,512,SOCKET_ID_ANY,RING_F_SC_DEQ);
        if(!workers[i].inbox) rte_exit(1,"parallel reply ring allocation failed\n");
    }
    unsigned index=1,cpu;
    RTE_LCORE_FOREACH_WORKER(cpu)
        check(rte_eal_remote_launch(parallel_poll,&workers[index++],cpu),"launch generator");
    parallel_poll(&workers[0]);*end=now();*start=load.start;
    rte_eal_mp_wait_lcore();
    unsigned attempted=0;
    for(unsigned i=0;i<cores;i++) {
        attempted+=workers[i].sent;*unrelated+=workers[i].unrelated;
        void *packet;
        while(!rte_ring_dequeue(workers[i].inbox,&packet)) rte_pktmbuf_free(packet);
        rte_ring_free(workers[i].inbox);
    }
    *failed=atomic_load(&load.failed);
    return attempted;
}
