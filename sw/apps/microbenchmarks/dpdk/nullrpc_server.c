/* CPU-NIC baseline: same AUTH_NONE ONC-RPC add call/reply as Lauberhorn.
 * Buffer results until the run ends. Hardware RX/TX share the CN88xx clock. */
#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <rte_cycles.h>
#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_lcore.h>
#include <rte_mbuf.h>
#include <rte_mbuf_dyn.h>
#include <rte_version.h>
#include "wire.h"
struct sample {
    uint32_t id; uint64_t rx, tx; int status;
    uint64_t cpu_rx, network_rx_begin, network_rx_end, network_tx_begin, network_tx_end, cpu_tx;
};
/* Opt-in raw software boundaries. Keep compiler motion across probes bounded;
 * durations use the same nanosecond scale as the NIC, never a fitted epoch. */
static uint64_t stamp(void) {
    struct timespec ts;
    __asm__ volatile("" ::: "memory");
    if (clock_gettime(CLOCK_MONOTONIC_RAW, &ts)) rte_exit(1,"clock_gettime failed\n");
    __asm__ volatile("" ::: "memory");
    return (uint64_t)ts.tv_sec*1000000000+ts.tv_nsec;
}
static void check(int rc,const char *s) { if(rc<0) rte_exit(1,"%s: %s\n",s,strerror(-rc)); }
static unsigned number(const char *s,unsigned max) {
    char *end; errno=0; unsigned long v=strtoul(s,&end,10);
    if(errno || !*s || *end || v>max) rte_exit(1,"invalid number: %s\n",s);
    return v;
}
int main(int argc,char **argv) {
    int n=rte_eal_init(argc,argv); if(n<0) return 1;
    argc-=n; argv+=n;
    if(argc!=10 || strcmp(argv[8],"add")) rte_exit(1,
        "EAL -- SRC_IP PEER_IP PEER_MAC PEER_PORT COUNT WARMUP TIMEOUT_US add OUTPUT.csv\n");
    if(rte_lcore_count()!=1 || rte_eth_dev_count_avail()!=1) rte_exit(1,"one lcore and port required\n");
    struct endpoint src={.port=12345},peer={0};
    if(inet_pton(AF_INET,argv[1],src.ip)!=1 || inet_pton(AF_INET,argv[2],peer.ip)!=1) rte_exit(1,"bad IP\n");
    struct rte_ether_addr mac;
    if(rte_ether_unformat_addr(argv[3],&mac)) rte_exit(1,"bad MAC\n");
    memcpy(peer.mac,mac.addr_bytes,6); peer.port=number(argv[4],65535);
    unsigned count=number(argv[5],10000000),warmup=number(argv[6],1000000);
    unsigned timeout_us=number(argv[7],10000000);
    if(!count || !timeout_us) rte_exit(1,"positive count and timeout required\n");
    const char *delay_env=getenv("LH_NULLRPC_DELAY_NS");
    unsigned delay=delay_env?number(delay_env,1000000):0;
    int hardware=getenv("LH_NULLRPC_SOFTWARE_CONTROL")==NULL;
    int profile=getenv("LH_NULLRPC_PROFILE")!=NULL;
    FILE *csv=fopen(argv[9],"wx"); if(!csv) rte_exit(1,"CSV: %s\n",strerror(errno));
    struct sample *samples=calloc(count+warmup,sizeof(*samples));
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
    int timestamp_offset=0; uint64_t timestamp_flag=0;
    if(hardware) {
        check(rte_mbuf_dyn_rx_timestamp_register(&timestamp_offset,&timestamp_flag),"timestamp field");
        check(rte_eth_timesync_enable(port),"timesync enable");
    }
    uint64_t hz=rte_get_timer_hz(),timeout=(uint64_t)timeout_us*hz/1000000;
    uint64_t delay_ticks=(uint64_t)delay*hz/1000000000;
    fprintf(stderr,"dpdk=%s driver=%s lcore=%u hardware=%d delay_ns=%u link_mbps=%u\n",
        rte_version(),info.driver_name,rte_lcore_id(),hardware,delay,link.link_speed);
    const char *ready=getenv("LH_NULLRPC_READY_FILE");
    if(ready) { FILE *f=fopen(ready,"wx"); if(!f) rte_exit(1,"ready file failed\n"); fputs("ready\n",f); fclose(f); }
    uint64_t probe_min=UINT64_MAX;
    if(profile) for(unsigned i=0;i<10000;i++) {
        uint64_t a=stamp(), b=stamp(); if(b-a<probe_min) probe_min=b-a;
    }
    struct rte_mbuf *pending=NULL;
    uint64_t pending_stamp=0;
    unsigned completed=0,attempted=0; uint64_t unrelated=0;
    int error=0;
    for(unsigned i=0;i<count+warmup;i++) {
        struct sample *s=&samples[i]; attempted=i+1;
        uint64_t deadline=rte_get_timer_cycles()+(i?timeout:120*hz);
        struct rte_mbuf *rx=NULL;
        while(!rx) {
            if(pending) { rx=pending; pending=NULL; s->cpu_rx=pending_stamp; }
            else {
                rte_eth_rx_burst(port,0,&rx,1);
                if(rx && profile) s->cpu_rx=stamp();
            }
            if(rx) {
                const uint8_t *payload=NULL; size_t size=0;
                if(profile) s->network_rx_begin=stamp();
                int valid=parse_udp(rte_pktmbuf_mtod(rx,const uint8_t *),rx->pkt_len,&src,&peer,&payload,&size);
                if(profile) s->network_rx_end=stamp();
                if(valid==0) { unrelated++; rte_pktmbuf_free(rx); rx=NULL; }
                else if(valid<0 || size!=52 || get32(payload)!=i+1 || get32(payload+4)!=0 ||
                    get32(payload+8)!=2 || get32(payload+12)!=0x23451111 ||
                    get32(payload+16)!=1 || get32(payload+20)!=1 ||
                    get32(payload+24) || get32(payload+28) || get32(payload+32) || get32(payload+36) ||
                    get32(payload+40)!=i+1 || get32(payload+44)!=7 || get32(payload+48)!=11) {
                    error=1; s->status=-EPROTO; break;
                } else s->id=get32(payload);
            }
            if(rte_get_timer_cycles()>deadline) { error=1; s->status=-ETIMEDOUT; break; }
        }
        if(error) { if(rx) rte_pktmbuf_free(rx); break; }
        if(hardware) {
            if(!(rx->ol_flags&timestamp_flag)) { error=1; s->status=-ENODATA; rte_pktmbuf_free(rx); break; }
            s->rx=*RTE_MBUF_DYNFIELD(rx,timestamp_offset,uint64_t *);
        }
        if(delay_ticks) { uint64_t end=rte_get_timer_cycles()+delay_ticks; while(rte_get_timer_cycles()<end) {} }
        uint8_t reply[32]={0}; put32(reply,s->id); put32(reply+4,1); put32(reply+24,s->id); put32(reply+28,18);
        if(profile) s->network_tx_begin=stamp();
        make_udp(rte_pktmbuf_mtod(rx,uint8_t *),&src,&peer,reply,sizeof(reply));
        if(profile) s->network_tx_end=stamp();
        if(rte_pktmbuf_trim(rx,rx->pkt_len-74)) rte_exit(1,"trim failed\n");
        rx->ol_flags=hardware?RTE_MBUF_F_TX_IEEE1588_TMST:0;
        deadline=rte_get_timer_cycles()+timeout;
        if(profile) s->cpu_tx=stamp();
        while(!rte_eth_tx_burst(port,0,&rx,1)) {
            if(rte_get_timer_cycles()>deadline) { s->status=-ETIMEDOUT; error=1; rte_pktmbuf_free(rx); break; }
        }
        if(error) break;
        if(hardware) {
            struct timespec ts;
            for(;;) {
                int rc=rte_eth_timesync_read_tx_timestamp(port,&ts);
                if(!rc) { s->tx=(uint64_t)ts.tv_sec*1000000000+ts.tv_nsec; break; }
                if(rc!=-EAGAIN && rc!=-EINVAL) { s->status=rc; error=1; break; }
                /* The next closed-loop request can precede SEND_PTP in the CQ. */
                if(!pending) {
                    rte_eth_rx_burst(port,0,&pending,1);
                    if(pending && profile) pending_stamp=stamp();
                }
                if(rte_get_timer_cycles()>deadline) { s->status=-ETIMEDOUT; error=1; break; }
            }
            if(!error && (!s->rx || s->tx<=s->rx || (i && s->rx<=samples[i-1].rx))) {
                s->status=-ERANGE; error=1;
            }
        }
        if(error) break;
        s->status=1; completed++;
    }
    if(pending) rte_pktmbuf_free(pending);
    struct rte_eth_stats stats={0}; check(rte_eth_stats_get(port,&stats),"stats");
    fprintf(csv,"request_id,warmup,status,rx_hw_ns,tx_hw_ns,mac_to_mac_ns,injected_delay_ns,profile,cpu_rx_ns,network_rx_begin_ns,network_rx_end_ns,network_tx_begin_ns,network_tx_end_ns,cpu_tx_ns,probe_min_ns\n");
    for(unsigned i=0;i<attempted;i++) {
        struct sample *s=&samples[i];
        fprintf(csv,"%u,%d,%d,%"PRIu64",%"PRIu64",%"PRIu64",%u,%d,%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64"\n",s->id,i<warmup,s->status,s->rx,s->tx,
            s->tx>=s->rx?s->tx-s->rx:0,delay,profile,
            s->cpu_rx,s->network_rx_begin,s->network_rx_end,s->network_tx_begin,s->network_tx_end,s->cpu_tx,profile?probe_min:0);
    }
    int io_error=ferror(csv); if(fclose(csv)) io_error=1;
    fprintf(stderr,"{\"expected\":%u,\"completed\":%u,\"unrelated\":%"PRIu64",\"hardware\":%d,\"rx_errors\":%"PRIu64",\"tx_errors\":%"PRIu64",\"rx_missed\":%"PRIu64",\"passed\":%s}\n",
        count+warmup,completed,unrelated,hardware,stats.ierrors,stats.oerrors,stats.imissed,
        !error&&!io_error&&completed==count+warmup?"true":"false");
    /* dev_stop also resets an outstanding failed timestamp operation. */
    check(rte_eth_dev_stop(port),"stop"); check(rte_eth_dev_close(port),"close");
    rte_mempool_free(pool); free(samples); rte_eal_cleanup();
    return error||io_error||completed!=count+warmup;
}
