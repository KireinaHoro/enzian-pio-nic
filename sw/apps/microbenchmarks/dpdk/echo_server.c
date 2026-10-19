#define _GNU_SOURCE
/* Schema echo CPU-NIC baseline. Software stages use CLOCK_MONOTONIC_RAW.
 * External wire durations are acquired separately; no NIC hardware timestamps. */
#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <stdatomic.h>
#include <rte_launch.h>
#include <rte_cycles.h>
#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_lcore.h>
#include <rte_mbuf.h>
#ifdef LH_HAVE_MLX5
#include <rte_pmd_mlx5_lh.h>
#endif
#include <rte_version.h>
#include "wire.h"
#include "benchmark.h"
#ifndef LH_ENABLE_TRACING
#define LH_ENABLE_TRACING 1
#endif
#define SOFTWARE_STAGES(X) \
    X(rx_poll_enter) X(rx_cqe) X(rx_return) X(network_rx_begin) X(network_rx_end) \
    X(rpc_rx_end) X(handler_end) X(rpc_tx_end) X(network_tx_begin) X(network_tx_end) \
    X(tx_submit_begin) X(tx_pmd_enter) X(tx_doorbell) X(tx_return)
struct sample {
    uint32_t id; int status;
#if LH_ENABLE_TRACING
#define FIELD(name) uint64_t name;
    SOFTWARE_STAGES(FIELD)
#undef FIELD
#endif
};
#if LH_ENABLE_TRACING
static uint64_t stamp(void) {
    struct timespec ts;
    __asm__ volatile("" ::: "memory");
    if (clock_gettime(CLOCK_MONOTONIC_RAW, &ts)) rte_exit(1,"clock_gettime failed\n");
    __asm__ volatile("" ::: "memory");
    return (uint64_t)ts.tv_sec*1000000000+ts.tv_nsec;
}
#define PROBE(name) do { if(profile) s->name=stamp(); } while(0)
#else
#define PROBE(name) ((void)0)
#endif
static void check(int rc,const char *s) { if(rc<0) rte_exit(1,"%s: %s\n",s,strerror(-rc)); }
static unsigned number(const char *s,unsigned max) {
    char *end; errno=0; unsigned long v=strtoul(s,&end,10);
    if(errno || !*s || *end || v>max) rte_exit(1,"invalid number: %s\n",s);
    return v;
}
#include "rpc_backend.h"
#if LH_ENABLE_TRACING
/* Echo handlers do not create fibers; the shared trace runtime sees inline execution. */
__thread struct fiber *current_fiber, *main_fiber;
#endif
struct worker {
    uint16_t port,queue;
    unsigned total,warmup,cpu,chunk_requests;
    uint64_t completed,measured,timeout;
    struct endpoint src,peer;
    struct lauberhorn_worker runtime;
    lauberhorn_t context;
    struct activity_counter activity;
    struct sample *samples, *sample;
    struct rte_mbuf *packet;
    uint8_t *reply;
    int profile;
};
static _Thread_local struct worker *current;
static atomic_int stopped,failed;
static const char *stop_file;
static int counted;
static unsigned delay_ns;
static void send_reply(lauberhorn_pkt_desc_t *parent,unsigned status,size_t length,
                       struct server_trace_row *trace) {
    struct worker *w=current;
    struct sample *s=w->sample;
#if LH_ENABLE_TRACING
    int profile=w->profile;
    if(trace->enabled) {
        trace->tx_cpu_id=sched_getcpu();
        s->rpc_rx_end=trace->handler_enter_ns;
        s->handler_end=trace->handler_exit_ns;
        s->rpc_tx_end=trace->marshal_exit_ns;
    }
#else
    (void)trace;
#endif
    /* Body was encoded directly into the TX mbuf, as LH encodes into its TX buffer. */
    uint8_t *reply=w->reply;
    memset(reply,0,24);put32(reply,parent->oncrpc_call_rx.request_token);put32(reply+4,1);
    put32(reply+20,status);
    PROBE(network_tx_begin);
    make_udp_header(rte_pktmbuf_mtod(w->packet,uint8_t *),&w->src,&w->peer,24+length);
    PROBE(network_tx_end);
    unsigned wanted=42+24+length;
    if(wanted>w->packet->pkt_len || rte_pktmbuf_trim(w->packet,w->packet->pkt_len-wanted))
        rte_exit(1,"reply size invalid\n");
    w->packet->ol_flags=0;
    PROBE(tx_submit_begin);
#if LH_ENABLE_TRACING
    trace->tx_enter_ns=server_trace_stage(trace,LH_PMU_tx_enter);
#endif
    uint64_t end=rte_get_timer_cycles()+w->timeout;
    while(!rte_eth_tx_burst(w->port,w->queue,&w->packet,1)) {
        if(rte_get_timer_cycles()>end) {
            rte_pktmbuf_free(w->packet);w->packet=NULL;atomic_store(&failed,1);break;
        }
    }
#if LH_ENABLE_TRACING
#ifdef LH_HAVE_MLX5
    if(profile) {
        struct rte_pmd_mlx5_lh_sample *q=rte_pmd_mlx5_lh_sample();
        if(q->tx_packets!=1 || !(q->tx_enter<=q->tx_doorbell && q->tx_doorbell<=q->tx_return))
            rte_exit(1,"invalid TX queue boundaries\n");
        s->tx_pmd_enter=q->tx_enter;s->tx_doorbell=q->tx_doorbell;s->tx_return=q->tx_return;
        trace->tx_doorbell_ns=q->tx_doorbell;trace->tx_doorbell_return_ns=q->tx_return;
    }
#endif
    trace->tx_exit_ns=server_trace_stage(trace,LH_PMU_tx_exit);
    trace->tx_worker_id=w->runtime.worker_id;trace->response_bytes=length;
    trace->status=status;trace->transport_status=w->packet?1:0;
    trace->ok &= status==0 && w->packet!=NULL;
    server_trace_write(trace);
#endif
    s->status=w->packet?1:-ETIMEDOUT;
}
static int serve_queue(void *argument) {
    struct worker *w=argument;current=w;current_worker=&w->runtime;
    bench_handler_activity(stop_file?&w->activity:NULL);w->cpu=rte_lcore_id();
    w->runtime.ctx=&w->context;w->runtime.worker_id=w->queue;
#if LH_ENABLE_TRACING
    server_trace_thread_init();
    int profile=w->profile;
#ifdef LH_HAVE_MLX5
    struct rte_pmd_mlx5_lh_sample *q=rte_pmd_mlx5_lh_sample();
    if(profile && !q) rte_exit(1,"PMD probes unavailable\n");
#else
    if(profile) rte_exit(1,"profiling requires mlx5\n");
#endif
#endif
    uint64_t deadline=rte_get_timer_cycles()+120*rte_get_timer_hz();
    unsigned polls=0;
    while(!atomic_load(&stopped) && !atomic_load(&failed) && (!counted || w->completed<w->total)) {
        if(stop_file && !(++polls%1024) && !access(stop_file,F_OK)) {atomic_store(&stopped,1);break;}
        struct rte_mbuf *packet=NULL;
        if(!rte_eth_rx_burst(w->port,w->queue,&packet,1)) {
            if(!stop_file && rte_get_timer_cycles()>deadline) atomic_store(&failed,1);
            continue;
        }
        struct sample temporary={0};
        struct sample *s=counted?&w->samples[w->completed]:&temporary;w->sample=s;
#if LH_ENABLE_TRACING
        server_trace_rx_begin(&w->runtime.rx_trace,w->queue);
#ifdef LH_HAVE_MLX5
        if(profile) {
            if(q->rx_packets!=1 || !(q->rx_enter<=q->rx_cqe && q->rx_cqe<=q->rx_return))
                rte_exit(1,"invalid RX queue boundaries\n");
            s->rx_poll_enter=q->rx_enter;s->rx_cqe=q->rx_cqe;s->rx_return=q->rx_return;
        }
#endif
#endif
        PROBE(network_rx_begin);
        const uint8_t *bytes=rte_pktmbuf_mtod(packet,const uint8_t *),*payload=NULL;
        size_t length=0;
        w->peer.port=packet->pkt_len>=42?get16(bytes+34):0;
        int valid=packet->nb_segs==1?parse_udp(bytes,packet->pkt_len,&w->src,&w->peer,&payload,&length):-1;
        PROBE(network_rx_end);
        if(!valid) {rte_pktmbuf_free(packet);continue;}
        if(valid<0 || length<40 || get32(payload+4)!=0 || get32(payload+8)!=2 ||
           get32(payload+24) || get32(payload+28) || get32(payload+32) || get32(payload+36)) {
            rte_pktmbuf_free(packet);atomic_store(&failed,1);break;
        }
        s->id=get32(payload);
        struct registration *registration=lookup(get32(payload+12),get32(payload+16),get32(payload+20));
        if(!registration || length!=40+registration->size) {
            rte_pktmbuf_free(packet);atomic_store(&failed,1);break;
        }
        if(counted && w->chunk_requests && s->id!=w->completed%w->chunk_requests+1) {
            rte_pktmbuf_free(packet);atomic_store(&failed,1);break;
        }
        w->packet=packet;w->reply=rte_pktmbuf_mtod(packet,uint8_t *)+42;
        w->runtime.dp.rx_view.data=(void *)(payload+40);
        w->runtime.dp_base=w->reply+24;
        lauberhorn_pkt_desc_t desc={.payload_len=length-40,
            .oncrpc_call_rx={.func_ptr=&registration->dispatch,.request_token=s->id}};
#if LH_ENABLE_TRACING
        w->runtime.rx_trace.rx_exit_ns=server_trace_stage(&w->runtime.rx_trace,LH_PMU_rx_exit);
        w->runtime.rx_trace.rx_cpu_id=sched_getcpu();
        server_trace_event(&w->runtime.rx_trace,LH_TRACE_RX_PACKET,0,s->id);
#endif
        if(delay_ns) {uint64_t end=rte_get_timer_cycles()+delay_ns*rte_get_timer_hz()/1000000000;while(rte_get_timer_cycles()<end) {}}
        rpc_handle_call(&w->runtime,&desc);
        if(s->status!=1) {atomic_store(&failed,1);break;}
        w->completed++;w->measured+=s->id>w->warmup;
        deadline=rte_get_timer_cycles()+w->timeout;
    }
    return 0;
}
static int multi_server(char **argv, const struct benchmark *bench) {
    stop_file=getenv("LH_ECHO_STOP_FILE");
    if(stop_file && !access(stop_file,F_OK)) rte_exit(1,"stale server stop file\n");
    unsigned workers=rte_lcore_count();
    counted=stop_file==NULL;
    if(workers<1 || workers>4 || rte_eth_dev_count_avail()!=1 || (counted && workers!=1)) rte_exit(1,"invalid worker configuration\n");
    int profile=0;
#if LH_ENABLE_TRACING
    profile=getenv("LH_ECHO_PROFILE")!=NULL;
    if(profile) {
        char trace_path[4096];snprintf(trace_path,sizeof(trace_path),"%s.runtime.csv",argv[9]);
        setenv("LAUBERHORN_SERVER_TRACE_CSV",trace_path,1);server_trace_init();
    }
#else
    if(getenv("LH_ECHO_PROFILE")) rte_exit(1,"tracing compiled out\n");
#endif
    bench_handler_configure(0,0);
    register_echoes();
    unsigned count = number(argv[5], 10000000), warmup = number(argv[6], stop_file ? UINT32_MAX : 1000000);
    if (!count) rte_exit(1,"positive count required\n");
    FILE *csv = fopen(argv[9], "wx"); if (!csv) rte_exit(1,"output exists or cannot open\n");
    uint16_t port; RTE_ETH_FOREACH_DEV(port) { break; }
    struct rte_eth_dev_info info; check(rte_eth_dev_info_get(port, &info), "device info");
    struct rte_eth_conf config = {0};
    if (workers>1) {
        config.rxmode.mq_mode = RTE_ETH_MQ_RX_RSS;
        config.rx_adv_conf.rss_conf.rss_hf = RTE_ETH_RSS_NONFRAG_IPV4_UDP;
        if (!(info.flow_type_rss_offloads & RTE_ETH_RSS_NONFRAG_IPV4_UDP))
            rte_exit(1,"UDP RSS unsupported\n");
    }
    check(rte_eth_dev_configure(port, workers, workers, &config), "multi configure");
    int socket = rte_eth_dev_socket_id(port); if (socket < 0) socket = 0;
    struct rte_mempool *pool = rte_pktmbuf_pool_create("loopback", workers>1?16383:4095, 128, 0, RTE_MBUF_DEFAULT_BUF_SIZE, socket);
    if (!pool) rte_exit(1,"mempool failed\n");
    uint16_t nrx = 256, ntx = 256;
    check(rte_eth_dev_adjust_nb_rx_tx_desc(port, &nrx, &ntx), "descriptors");
    for (unsigned q = 0; q < workers; q++) {
        check(rte_eth_rx_queue_setup(port, q, nrx, socket, &info.default_rxconf, pool), "RX setup");
        check(rte_eth_tx_queue_setup(port, q, ntx, socket, &info.default_txconf), "TX setup");
    }
    check(rte_eth_dev_start(port), "start");
    struct rte_ether_addr mac, peer_mac;
    check(rte_eth_macaddr_get(port, &mac), "MAC");
    if (rte_ether_unformat_addr(argv[3], &peer_mac)) rte_exit(1,"invalid MAC\n");
    struct worker state[4] = {0};
    (void)bench;
    for (unsigned q = 0; q < workers; q++) {
        state[q] = (struct worker){.port=port, .queue=q, .total=count+warmup, .warmup=warmup};
        state[q].src.port = 12345;
        if (inet_pton(AF_INET, argv[1], state[q].src.ip) != 1 ||
            inet_pton(AF_INET, argv[2], state[q].peer.ip) != 1) rte_exit(1,"invalid IP\n");
        memcpy(state[q].src.mac, mac.addr_bytes, 6);
        memcpy(state[q].peer.mac, peer_mac.addr_bytes, 6);
        state[q].profile=profile;state[q].timeout=(uint64_t)number(argv[7],120000000)*rte_get_timer_hz()/1000000;
        state[q].chunk_requests=getenv("LH_ECHO_CHUNK_REQUESTS")?number(getenv("LH_ECHO_CHUNK_REQUESTS"),1024):0;
        if(counted) {
            state[q].samples=calloc(count+warmup,sizeof(struct sample));
            if(!state[q].samples) rte_exit(1,"samples allocation failed\n");
            for(unsigned i=0;i<count+warmup;i++)
                ((volatile struct sample *)state[q].samples)[i].status=0;
        }

    }
    struct rte_eth_link link = {0};
    for (unsigned i=0; i<100; i++) {
        check(rte_eth_link_get_nowait(port, &link), "link");
        if (link.link_status) break;
        rte_delay_us_sleep(100000);
    }
    if (!link.link_status) rte_exit(1,"link down\n");
    unsigned q = 1, cpu;
    RTE_LCORE_FOREACH_WORKER(cpu) check(rte_eal_remote_launch(serve_queue, &state[q++], cpu), "launch");
    const char *ready = getenv("LH_ECHO_READY_FILE");
    if (ready) { FILE *f=fopen(ready,"wx"); if(!f) rte_exit(1,"ready failed\n"); fputs("ready\n",f); fclose(f); }
    serve_queue(&state[0]); rte_eal_mp_wait_lcore();
    uint64_t total_completed=0;
    if(!counted) fprintf(csv,"queue,lcore,completed,measured\n");
    else {
        fputs("request_id,warmup,status,profile",csv);
#define HEADER(name) fputs("," #name "_ns",csv);
        SOFTWARE_STAGES(HEADER)
#undef HEADER
        fputs(",probe_min_ns\n",csv);
        for(unsigned i=0;i<state[0].completed;i++) {
            struct sample *s=&state[0].samples[i];
            fprintf(csv,"%u,%d,%d,%d",s->id,s->id<=warmup,s->status,profile);
#if LH_ENABLE_TRACING
#define VALUE(name) fprintf(csv,",%"PRIu64,s->name);
            SOFTWARE_STAGES(VALUE)
#undef VALUE
#else
#define ZERO(name) fputs(",0",csv);
            SOFTWARE_STAGES(ZERO)
#undef ZERO
#endif
            fputs(",0\n",csv);
        }
    }
    for (q=0; q<workers; q++) {
        if(!counted) fprintf(csv,"%u,%u,%"PRIu64",%"PRIu64"\n",q,state[q].cpu,state[q].completed,state[q].measured);
        total_completed+=state[q].completed;
        if (!state[q].measured) atomic_store(&failed,1);
        free(state[q].samples);
    }
    int error = ferror(csv); if (fclose(csv)) error=1;
    struct rte_eth_stats stats; check(rte_eth_stats_get(port,&stats),"stats");
    char stats_path[4096];
    if (snprintf(stats_path,sizeof(stats_path),"%s.nic.json",argv[9]) >= (int)sizeof(stats_path))
        rte_exit(1,"NIC counter path too long\n");
    FILE *nic=fopen(stats_path,"wx");
    if (!nic) rte_exit(1,"cannot create NIC counters\n");
    fprintf(nic,"{\"rx_packets\":%"PRIu64",\"tx_packets\":%"PRIu64",\"rx_missed\":%"PRIu64",\"rx_no_buffer\":%"PRIu64",\"rx_errors\":%"PRIu64",\"tx_errors\":%"PRIu64"}\n",
            stats.ipackets,stats.opackets,stats.imissed,stats.rx_nombuf,stats.ierrors,stats.oerrors);
    error |= ferror(nic); if (fclose(nic)) error=1;
    error |= atomic_load(&failed) || (counted && total_completed != count+warmup) ||
              stats.ierrors || stats.oerrors || stats.rx_nombuf || (!stop_file && stats.imissed);
    fprintf(stderr,"multi-worker completed=%"PRIu64" expected=%u passed=%s\n",total_completed,count+warmup,error?"false":"true");
    check(rte_eth_dev_stop(port),"stop"); check(rte_eth_dev_close(port),"close");
    rte_mempool_free(pool); rte_eal_cleanup();
    #if LH_ENABLE_TRACING
    server_trace_fini();
#endif
    return !!error;
}

int main(int argc,char **argv) {
    int n=rte_eal_init(argc,argv);if(n<0)return 1;argc-=n;argv+=n;
    if(argc!=10) rte_exit(1,"EAL -- SRC_IP PEER_IP PEER_MAC PEER_PORT COUNT WARMUP TIMEOUT_US echoSIZE OUTPUT.csv\n");
    const struct benchmark *bench=benchmark_find(argv[8]);
    if(!bench || strncmp(argv[8],"echo",4)) rte_exit(1,"schema echo required\n");
    delay_ns=getenv("LH_ECHO_DELAY_NS")?number(getenv("LH_ECHO_DELAY_NS"),1000000):0;
    return multi_server(argv,bench);
}
