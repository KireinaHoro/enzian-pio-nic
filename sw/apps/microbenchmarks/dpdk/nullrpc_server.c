/* CPU-NIC baseline: same AUTH_NONE ONC-RPC add call/reply as Lauberhorn.
 * Buffer results until the run ends. Hardware RX/TX share the CN88xx clock. */
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
#include <rte_mbuf_dyn.h>
#include <rte_version.h>
#include "wire.h"
#include "benchmark.h"
#ifndef LH_ENABLE_TRACING
#define LH_ENABLE_TRACING 1
#endif
struct sample {
    uint32_t id; int status;
#if LH_ENABLE_TRACING
    uint64_t rx, tx;
    uint64_t cpu_rx, network_rx_begin, network_rx_end, network_tx_begin, network_tx_end, cpu_tx;
#endif
};
/* Opt-in raw software boundaries. Keep compiler motion across probes bounded;
 * durations use the same nanosecond scale as the NIC, never a fitted epoch. */
#if LH_ENABLE_TRACING
static uint64_t stamp(void) {
    struct timespec ts;
    __asm__ volatile("" ::: "memory");
    if (clock_gettime(CLOCK_MONOTONIC_RAW, &ts)) rte_exit(1,"clock_gettime failed\n");
    __asm__ volatile("" ::: "memory");
    return (uint64_t)ts.tv_sec*1000000000+ts.tv_nsec;
}
#endif
static void check(int rc,const char *s) { if(rc<0) rte_exit(1,"%s: %s\n",s,strerror(-rc)); }
static unsigned number(const char *s,unsigned max) {
    char *end; errno=0; unsigned long v=strtoul(s,&end,10);
    if(errno || !*s || *end || v>max) rte_exit(1,"invalid number: %s\n",s);
    return v;
}
/* RSS assigns UDP flows to independent queue-owning polling workers. No PTP. */
struct multi_worker {
    uint16_t port, queue;
    unsigned total, warmup, size, cpu;
    uint64_t completed, measured;
    struct endpoint src, peer;
    uint8_t header[40];
} __rte_cache_aligned;
static atomic_uint multi_done;
static atomic_int multi_error;
static atomic_int multi_stop;
static const char *stop_file;
static int serve_queue(void *argument) {
    struct multi_worker *w = argument;
    w->cpu = rte_lcore_id();
    uint64_t deadline = rte_get_timer_cycles() + 120 * rte_get_timer_hz();
    unsigned polls=0;
    while (!atomic_load_explicit(&multi_stop, memory_order_relaxed) &&
           !atomic_load_explicit(&multi_error, memory_order_relaxed) &&
           (stop_file || atomic_load_explicit(&multi_done, memory_order_relaxed) < w->total)) {
        if(stop_file && !(++polls % 1024) && !access(stop_file,F_OK)) {
            atomic_store(&multi_stop,1); break;
        }
        struct rte_mbuf *packets[32];
        unsigned received = rte_eth_rx_burst(w->port, w->queue, packets, 32);
        unsigned completed = 0;
        for (unsigned j = 0; j < received; j++) {
            struct rte_mbuf *packet = packets[j];
            const uint8_t *bytes = rte_pktmbuf_mtod(packet, const uint8_t *);
            const uint8_t *payload = NULL; size_t size = 0;
            if (packet->nb_segs != 1 || packet->pkt_len < 42) {
                atomic_store(&multi_error, 1); rte_pktmbuf_free(packet); continue;
            }
            struct endpoint peer = w->peer;
            peer.port = get16(bytes + 34);
            int valid = parse_udp(bytes, packet->pkt_len, &w->src, &peer, &payload, &size);
            if (!valid) { rte_pktmbuf_free(packet); continue; }
            unsigned id = size >= 4 ? get32(payload) : 0;
            if (valid < 0 || size != 40 + w->size || !id || (!stop_file && id > w->total) ||
                memcmp(payload + 4, w->header + 4, 36)) {
                atomic_store(&multi_error, 1); rte_pktmbuf_free(packet); continue;
            }
            uint8_t reply[1472] = {0};
            put32(reply, id); put32(reply + 4, 1);
            memcpy(reply + 24, payload + 40, w->size);
            make_udp(rte_pktmbuf_mtod(packet, uint8_t *), &w->src, &peer, reply, 24 + w->size);
            if (rte_pktmbuf_trim(packet, packet->pkt_len - (42 + 24 + w->size)))
                rte_exit(1, "multi-worker trim failed\n");
            packet->ol_flags = 0;
            uint64_t end = rte_get_timer_cycles() + 2 * rte_get_timer_hz();
            while (!rte_eth_tx_burst(w->port, w->queue, &packet, 1)) {
                if (rte_get_timer_cycles() > end) {
                    rte_pktmbuf_free(packet); packet = NULL;
                    atomic_store(&multi_error, 1); break;
                }
            }
            if (packet) {
                w->completed++; w->measured += id > w->warmup;
                completed++;
                if(!stop_file) deadline = rte_get_timer_cycles() + 2 * rte_get_timer_hz();
            }
        }
        if(completed && !stop_file) atomic_fetch_add_explicit(&multi_done, completed, memory_order_relaxed);
        if (rte_get_timer_cycles() > deadline) atomic_store(&multi_error, 1);
    }
    return 0;
}
static int multi_server(char **argv, const struct benchmark *bench) {
    stop_file=getenv("LH_NULLRPC_STOP_FILE");
    if(stop_file && !access(stop_file,F_OK)) rte_exit(1,"stale server stop file\n");
    unsigned workers = rte_lcore_count();
    if (workers < 1 || workers > 4 || rte_eth_dev_count_avail() != 1 ||
        (strncmp(argv[8], "loopback", 8) && (strncmp(argv[8], "echo", 4) || !argv[8][4])) || !getenv("LH_NULLRPC_SOFTWARE_CONTROL") ||
        getenv("LH_NULLRPC_PROFILE"))
        rte_exit(1,"batched throughput requires 1-4 CPUs, one port, loopback, software control and no profiling\n");
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
    struct multi_worker state[4] = {0};
    uint8_t call[1472]; size_t size = bench->encode(call, sizeof(call), 1) - 40;
    for (unsigned q = 0; q < workers; q++) {
        state[q] = (struct multi_worker){.port=port, .queue=q, .total=count+warmup, .warmup=warmup, .size=size};
        state[q].src.port = 12345;
        if (inet_pton(AF_INET, argv[1], state[q].src.ip) != 1 ||
            inet_pton(AF_INET, argv[2], state[q].peer.ip) != 1) rte_exit(1,"invalid IP\n");
        memcpy(state[q].src.mac, mac.addr_bytes, 6);
        memcpy(state[q].peer.mac, peer_mac.addr_bytes, 6);
        memcpy(state[q].header, call, 40);
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
    const char *ready = getenv("LH_NULLRPC_READY_FILE");
    if (ready) { FILE *f=fopen(ready,"wx"); if(!f) rte_exit(1,"ready failed\n"); fputs("ready\n",f); fclose(f); }
    serve_queue(&state[0]); rte_eal_mp_wait_lcore();
    uint64_t total_completed=0;
    fprintf(csv,"queue,lcore,completed,measured\n");
    for (q=0; q<workers; q++) {
        fprintf(csv,"%u,%u,%"PRIu64",%"PRIu64"\n",q,state[q].cpu,state[q].completed,state[q].measured);
        total_completed+=state[q].completed;
        if (!state[q].measured) atomic_store(&multi_error,1);
    }
    int failed = ferror(csv); if (fclose(csv)) failed=1;
    struct rte_eth_stats stats; check(rte_eth_stats_get(port,&stats),"stats");
    char stats_path[4096];
    if (snprintf(stats_path,sizeof(stats_path),"%s.nic.json",argv[9]) >= (int)sizeof(stats_path))
        rte_exit(1,"NIC counter path too long\n");
    FILE *nic=fopen(stats_path,"wx");
    if (!nic) rte_exit(1,"cannot create NIC counters\n");
    fprintf(nic,"{\"rx_packets\":%"PRIu64",\"tx_packets\":%"PRIu64",\"rx_missed\":%"PRIu64",\"rx_no_buffer\":%"PRIu64",\"rx_errors\":%"PRIu64",\"tx_errors\":%"PRIu64"}\n",
            stats.ipackets,stats.opackets,stats.imissed,stats.rx_nombuf,stats.ierrors,stats.oerrors);
    failed |= ferror(nic); if (fclose(nic)) failed=1;
    failed |= atomic_load(&multi_error) || (!stop_file && atomic_load(&multi_done) != count+warmup) ||
              stats.ierrors || stats.oerrors || stats.rx_nombuf || (!stop_file && stats.imissed);
    fprintf(stderr,"multi-worker completed=%"PRIu64" expected=%u passed=%s\n",total_completed,count+warmup,failed?"false":"true");
    check(rte_eth_dev_stop(port),"stop"); check(rte_eth_dev_close(port),"close");
    rte_mempool_free(pool); rte_eal_cleanup();
    return !!failed;
}

int main(int argc,char **argv) {
    int n=rte_eal_init(argc,argv); if(n<0) return 1;
    argc-=n; argv+=n;
    if(argc!=10) rte_exit(1,
        "EAL -- SRC_IP PEER_IP PEER_MAC PEER_PORT COUNT WARMUP TIMEOUT_US add|loopbackSIZE OUTPUT.csv\n");
    const struct benchmark *bench = benchmark_find(argv[8]);
    uint8_t expected_call[1472];
    int loopback = !strncmp(argv[8], "loopback", 8) || (!strncmp(argv[8], "echo", 4) && argv[8][4]);
    if (!bench || (!loopback && strcmp(argv[8], "add"))) rte_exit(1,"unsupported RPC codec\n");
    if (rte_lcore_count() > 1 || getenv("LH_NULLRPC_BATCHED")) return multi_server(argv, bench);
    size_t expected_size = bench->encode(expected_call, sizeof(expected_call), 1);
    if(rte_lcore_count()!=1 || rte_eth_dev_count_avail()!=1) rte_exit(1,"one lcore and port required\n");
    struct endpoint src={.port=12345},peer={0};
    if(inet_pton(AF_INET,argv[1],src.ip)!=1 || inet_pton(AF_INET,argv[2],peer.ip)!=1) rte_exit(1,"bad IP\n");
    struct rte_ether_addr mac;
    if(rte_ether_unformat_addr(argv[3],&mac)) rte_exit(1,"bad MAC\n");
    memcpy(peer.mac,mac.addr_bytes,6); peer.port=number(argv[4],65535);
    uint16_t peer_port=peer.port;
    const char *flows_env=getenv("LH_BENCH_FLOWS");
    unsigned flows=flows_env?number(flows_env,1024):1;
    if(!flows || peer_port+flows>65536) rte_exit(1,"invalid flow count\n");
    unsigned count=number(argv[5],10000000),warmup=number(argv[6],1000000);
    unsigned timeout_us=number(argv[7],10000000);
    if(!count || !timeout_us) rte_exit(1,"positive count and timeout required\n");
    const char *delay_env=getenv("LH_NULLRPC_DELAY_NS");
    unsigned delay=delay_env?number(delay_env,1000000):0;
#if LH_ENABLE_TRACING
    int hardware=getenv("LH_NULLRPC_SOFTWARE_CONTROL")==NULL;
    int profile=getenv("LH_NULLRPC_PROFILE")!=NULL;
#else
    const int hardware=0;
    if(getenv("LH_NULLRPC_PROFILE")) rte_exit(1,"profiling is compiled out\n");
#endif
    FILE *csv=fopen(argv[9],"wx"); if(!csv) rte_exit(1,"CSV: %s\n",strerror(errno));
    struct sample *samples=calloc(count+warmup,sizeof(*samples));
    uint8_t *seen=calloc(count+warmup,1);
    if(!seen) rte_exit(1,"identity allocation failed\n");
    for(unsigned i=0;i<count+warmup;i++) ((volatile uint8_t *)seen)[i]=0;
    if(!samples) rte_exit(1,"sample allocation failed\n");
    for(unsigned i=0;i<count+warmup;i++) ((volatile struct sample *)samples)[i].status=0;
    uint16_t port; RTE_ETH_FOREACH_DEV(port) { break; }
    struct rte_eth_dev_info info; check(rte_eth_dev_info_get(port,&info),"device info");
    struct rte_eth_conf cfg={0}; check(rte_eth_dev_configure(port,1,1,&cfg),"configure");
    uint16_t nrx=256,ntx=256; check(rte_eth_dev_adjust_nb_rx_tx_desc(port,&nrx,&ntx),"descriptors");
    int socket=rte_eth_dev_socket_id(port); if(socket<0) socket=0;
    struct rte_mempool *pool=rte_pktmbuf_pool_create("nullrpc",8191,128,0,RTE_MBUF_DEFAULT_BUF_SIZE,socket);
    if(!pool) rte_exit(1,"mempool failed\n");
    check(rte_eth_rx_queue_setup(port,0,nrx,socket,&info.default_rxconf,pool),"RX setup");
    check(rte_eth_tx_queue_setup(port,0,ntx,socket,&info.default_txconf),"TX setup");
    check(rte_eth_dev_start(port),"start");
    check(rte_eth_macaddr_get(port,&mac),"MAC"); memcpy(src.mac,mac.addr_bytes,6);
    struct rte_eth_link link={0};
    for(unsigned i=0;i<100;i++) {
        check(rte_eth_link_get_nowait(port,&link),"link");
        if(link.link_status) break;
        rte_delay_us_sleep(100000);
    }
    if(!link.link_status) rte_exit(1,"link down\n");
#if LH_ENABLE_TRACING
    int timestamp_offset=0; uint64_t timestamp_flag=0;
    if(hardware) {
        check(rte_mbuf_dyn_rx_timestamp_register(&timestamp_offset,&timestamp_flag),"timestamp field");
        check(rte_eth_timesync_enable(port),"timesync enable");
    }
#endif
    uint64_t hz=rte_get_timer_hz(),timeout=(uint64_t)timeout_us*hz/1000000;
    uint64_t delay_ticks=(uint64_t)delay*hz/1000000000;
    fprintf(stderr,"dpdk=%s driver=%s lcore=%u hardware=%d delay_ns=%u link_mbps=%u\n",
        rte_version(),info.driver_name,rte_lcore_id(),hardware,delay,link.link_speed);
    const char *ready=getenv("LH_NULLRPC_READY_FILE");
    if(ready) { FILE *f=fopen(ready,"wx"); if(!f) rte_exit(1,"ready file failed\n"); fputs("ready\n",f); fclose(f); }
#if LH_ENABLE_TRACING
    uint64_t probe_min=UINT64_MAX;
    if(profile) for(unsigned i=0;i<10000;i++) {
        uint64_t a=stamp(), b=stamp(); if(b-a<probe_min) probe_min=b-a;
    }
    /* RX and TX timestamp completions share the CQ. Drain RX while awaiting
     * SEND_PTP, retaining every packet in a bounded FIFO for later dispatch. */
    struct rte_mbuf *pending[256];
    uint64_t pending_stamp[256];
    unsigned pending_head=0, pending_tail=0;
#endif
    unsigned completed=0,attempted=0; uint64_t unrelated=0;
    int error=0;
    for(unsigned i=0;i<count+warmup;i++) {
        struct sample *s=&samples[i]; attempted=i+1;
        uint64_t deadline=rte_get_timer_cycles()+(i?timeout:120*hz);
        struct rte_mbuf *rx=NULL;
        const uint8_t *request_payload = NULL;
        size_t request_size = 0;
        while(!rx) {
#if LH_ENABLE_TRACING
            if(pending_head != pending_tail) {
                rx=pending[pending_head % 256];
                s->cpu_rx=pending_stamp[pending_head++ % 256];
            }
            else
#endif
            {
                rte_eth_rx_burst(port,0,&rx,1);
#if LH_ENABLE_TRACING
                if(rx && profile) s->cpu_rx=stamp();
#endif
            }
            if(rx) {
                const uint8_t *payload=NULL; size_t size=0;
#if LH_ENABLE_TRACING
                if(profile) s->network_rx_begin=stamp();
#endif
                const uint8_t *bytes=rte_pktmbuf_mtod(rx,const uint8_t *);
                peer.port=rx->pkt_len>=42?get16(bytes+34):peer_port;
                int valid=parse_udp(bytes,rx->pkt_len,&src,&peer,&payload,&size);
                if(peer.port<peer_port || peer.port>=peer_port+flows) valid=0;
#if LH_ENABLE_TRACING
                if(profile) s->network_rx_end=stamp();
#endif
                if(valid==0) { unrelated++; rte_pktmbuf_free(rx); rx=NULL; }
                else {
                    unsigned id=size>=4?get32(payload):0;
                    if (valid < 0 || size != expected_size || !id || id>count+warmup || seen[id-1] ||
                        peer.port!=peer_port+(id-1)%flows ||
                        memcmp(payload + 4, expected_call + 4, 36) ||
                        (!loopback && (get32(payload + 40) != id ||
                         get32(payload + 44) != 7 || get32(payload + 48) != 11))) {
                        error=1; s->status=-EPROTO; break;
                    }
                    s->id=id; seen[id-1]=1;
                    request_payload = payload; request_size = size;
                }
            }
            if(rte_get_timer_cycles()>deadline) { error=1; s->status=-ETIMEDOUT; break; }
        }
        if(error) { if(rx) rte_pktmbuf_free(rx); break; }
#if LH_ENABLE_TRACING
        if(hardware) {
            if(!(rx->ol_flags&timestamp_flag)) { error=1; s->status=-ENODATA; rte_pktmbuf_free(rx); break; }
            s->rx=*RTE_MBUF_DYNFIELD(rx,timestamp_offset,uint64_t *);
        }
#endif
        if(delay_ticks) { uint64_t end=rte_get_timer_cycles()+delay_ticks; while(rte_get_timer_cycles()<end) {} }
        uint8_t reply[1472]={0};
        size_t reply_size = 32;
        put32(reply,s->id); put32(reply+4,1);
        if (loopback) {
            reply_size = request_size - 40 + 24;
            memcpy(reply + 24, request_payload + 40, request_size - 40);
        } else {
            put32(reply+24,s->id); put32(reply+28,18);
        }
#if LH_ENABLE_TRACING
        if(profile) s->network_tx_begin=stamp();
#endif
        make_udp(rte_pktmbuf_mtod(rx,uint8_t *),&src,&peer,reply,reply_size);
#if LH_ENABLE_TRACING
        if(profile) s->network_tx_end=stamp();
#endif
        if(rte_pktmbuf_trim(rx,rx->pkt_len-(42+reply_size))) rte_exit(1,"trim failed\n");
        rx->ol_flags=hardware?RTE_MBUF_F_TX_IEEE1588_TMST:0;
        deadline=rte_get_timer_cycles()+timeout;
#if LH_ENABLE_TRACING
        if(profile) s->cpu_tx=stamp();
#endif
        while(!rte_eth_tx_burst(port,0,&rx,1)) {
            if(rte_get_timer_cycles()>deadline) { s->status=-ETIMEDOUT; error=1; rte_pktmbuf_free(rx); break; }
        }
        if(error) break;
#if LH_ENABLE_TRACING
        if(hardware) {
            struct timespec ts;
            for(;;) {
                int rc=rte_eth_timesync_read_tx_timestamp(port,&ts);
                if(!rc) { s->tx=(uint64_t)ts.tv_sec*1000000000+ts.tv_nsec; break; }
                if(rc!=-EAGAIN && rc!=-EINVAL) { s->status=rc; error=1; break; }
                /* Multiple admitted requests can precede SEND_PTP in the CQ. */
                if (pending_tail - pending_head == 256) {
                    s->status=-ENOBUFS; error=1; break;
                }
                struct rte_mbuf *next=NULL;
                if (rte_eth_rx_burst(port,0,&next,1)) {
                    pending[pending_tail % 256]=next;
                    pending_stamp[pending_tail++ % 256]=profile?stamp():0;
                }
                if(rte_get_timer_cycles()>deadline) { s->status=-ETIMEDOUT; error=1; break; }
            }
            if(!error && (!s->rx || s->tx<=s->rx || (i && s->rx<=samples[i-1].rx))) {
                s->status=-ERANGE; error=1;
            }
        }
#endif
        if(error) break;
        s->status=1; completed++;
    }
#if LH_ENABLE_TRACING
    while(pending_head != pending_tail) rte_pktmbuf_free(pending[pending_head++ % 256]);
#endif
    struct rte_eth_stats stats={0}; check(rte_eth_stats_get(port,&stats),"stats");
    fprintf(csv,"request_id,warmup,status,rx_hw_ns,tx_hw_ns,mac_to_mac_ns,injected_delay_ns,profile,cpu_rx_ns,network_rx_begin_ns,network_rx_end_ns,network_tx_begin_ns,network_tx_end_ns,cpu_tx_ns,probe_min_ns\n");
    for(unsigned i=0;i<attempted;i++) {
        struct sample *s=&samples[i];
#if LH_ENABLE_TRACING
        fprintf(csv,"%u,%d,%d,%"PRIu64",%"PRIu64",%"PRIu64",%u,%d,%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64"\n",s->id,s->id<=warmup,s->status,s->rx,s->tx,
            s->tx>=s->rx?s->tx-s->rx:0,delay,profile,
            s->cpu_rx,s->network_rx_begin,s->network_rx_end,s->network_tx_begin,s->network_tx_end,s->cpu_tx,profile?probe_min:0);
#else
        fprintf(csv,"%u,%d,%d,0,0,0,%u,0,0,0,0,0,0,0,0\n",s->id,s->id<=warmup,s->status,delay);
#endif
    }
    int io_error=ferror(csv); if(fclose(csv)) io_error=1;
    fprintf(stderr,"{\"expected\":%u,\"completed\":%u,\"unrelated\":%"PRIu64",\"hardware\":%d,\"rx_errors\":%"PRIu64",\"tx_errors\":%"PRIu64",\"rx_missed\":%"PRIu64",\"passed\":%s}\n",
        count+warmup,completed,unrelated,hardware,stats.ierrors,stats.oerrors,stats.imissed,
        !error&&!io_error&&completed==count+warmup?"true":"false");
    /* dev_stop also resets an outstanding failed timestamp operation. */
    check(rte_eth_dev_stop(port),"stop"); check(rte_eth_dev_close(port),"close");
    rte_mempool_free(pool); free(samples); free(seen); rte_eal_cleanup();
    return error||io_error||completed!=count+warmup;
}
