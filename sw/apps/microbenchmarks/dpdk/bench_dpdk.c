/* Closed-loop latency generator. No allocation or file I/O in the timed loop. */
#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <rte_cycles.h>
#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_lcore.h>
#include <rte_mbuf.h>
#include <rte_version.h>
#include "wire.h"
#include "benchmark.h"
struct sample { uint64_t enter, ready, submitted, received, exit; int status; };
static uint64_t now(void) {
    struct timespec t;
    if(clock_gettime(CLOCK_MONOTONIC_RAW,&t)) rte_exit(1,"clock_gettime failed\n");
    return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;
}
static unsigned number(const char *s, unsigned limit) {
    char *end; errno=0; unsigned long v=strtoul(s,&end,10);
    if(errno || !*s || *end || !v || v>limit) rte_exit(1,"bad integer: %s\n",s);
    return v;
}
static void check(int rc, const char *what) {
    if(rc<0) rte_exit(1,"%s: %s\n",what,strerror(-rc));
}
int main(int argc, char **argv) {
    int consumed=rte_eal_init(argc,argv);
    if(consumed<0) return 1;
    argc-=consumed; argv+=consumed;
    if(argc!=10 && argc!=11) rte_exit(1,
        "EAL options -- SRC_IP DST_IP DST_MAC DST_PORT COUNT WARMUP TIMEOUT_US BENCHMARK OUTPUT.csv [loopback]\n"
        "Use exactly one EAL lcore and one allowlisted port (cpu40g1 VF). WARMUP may be zero.\n");
    if(rte_lcore_count()!=1 || rte_eth_dev_count_avail()!=1)
        rte_exit(1,"require exactly one lcore and one port\n");
    struct endpoint src={.port=49152}, dst={0};
    if(inet_pton(AF_INET,argv[1],src.ip)!=1 || inet_pton(AF_INET,argv[2],dst.ip)!=1)
        rte_exit(1,"invalid IPv4 address\n");
    struct rte_ether_addr dmac;
    if(rte_ether_unformat_addr(argv[3],&dmac)) rte_exit(1,"invalid destination MAC\n");
    memcpy(dst.mac,dmac.addr_bytes,6); dst.port=number(argv[4],65535);
    unsigned count=number(argv[5],10000000), warmup=0;
    if(strcmp(argv[6],"0")) warmup=number(argv[6],1000000);
    uint64_t timeout_ns=(uint64_t)number(argv[7],10000000)*1000;
    const struct benchmark *bench=benchmark_find(argv[8]);
    if(!bench) rte_exit(1,"unknown benchmark: %s\n",argv[8]);
    FILE *csv=fopen(argv[9],"wx");
    if(!csv) rte_exit(1,"cannot create CSV: %s\n",strerror(errno));
    struct sample *samples=calloc(count+warmup,sizeof(*samples));
    if(!samples) rte_exit(1,"sample allocation failed\n");
    /* Fault in each result page before measuring. */
    for(unsigned i=0;i<count+warmup;i++) ((volatile struct sample *)samples)[i].status=0;
    uint16_t port; RTE_ETH_FOREACH_DEV(port) { break; }
    struct rte_eth_dev_info info;
    check(rte_eth_dev_info_get(port,&info),"device info");
    struct rte_eth_conf config={0};
    if(argc==11) {
        if(strcmp(argv[10],"loopback") || strcmp(bench->name,"echo") ||
           memcmp(src.ip,dst.ip,4) || src.port!=dst.port)
            rte_exit(1,"loopback requires echo with identical IP/port endpoints\n");
        config.lpbk_mode=1;
    }
    check(rte_eth_dev_configure(port,1,1,&config),"configure");
    uint16_t nrx=256,ntx=256;
    check(rte_eth_dev_adjust_nb_rx_tx_desc(port,&nrx,&ntx),"descriptors");
    int socket=rte_eth_dev_socket_id(port); if(socket<0) socket=0;
    struct rte_mempool *pool=rte_pktmbuf_pool_create("bench",8191,128,0,
        RTE_MBUF_DEFAULT_BUF_SIZE,socket);
    if(!pool) rte_exit(1,"mempool failed\n");
    check(rte_eth_rx_queue_setup(port,0,nrx,socket,&info.default_rxconf,pool),"RX queue");
    check(rte_eth_tx_queue_setup(port,0,ntx,socket,&info.default_txconf),"TX queue");
    check(rte_eth_dev_start(port),"start");
    struct rte_ether_addr mac;
    check(rte_eth_macaddr_get(port,&mac),"MAC"); memcpy(src.mac,mac.addr_bytes,6);
    struct rte_eth_link link={0};
    for(unsigned i=0;i<100;i++) {
        check(rte_eth_link_get_nowait(port,&link),"link");
        if(link.link_status) break;
        rte_delay_us_sleep(100000);
    }
    if(!link.link_status) rte_exit(1,"link remained down\n");
    char mac_text[RTE_ETHER_ADDR_FMT_SIZE];
    rte_ether_format_addr(mac_text,sizeof(mac_text),&mac);
    fprintf(stderr,"source_mac=%s source_ip=%s destination_ip=%s destination_mac=%s benchmark=%s\n",
        mac_text,argv[1],argv[2],argv[3],bench->name);
    uint64_t overhead=UINT64_MAX;
    for(unsigned i=0;i<10000;i++) {
        uint64_t a=now(),b=now(); if(b>a && b-a<overhead) overhead=b-a;
    }
    /* Buffer allocation is outside the measured RPC; encoding is inside. */
    uint64_t unrelated=0, failed=0;
    unsigned completed=0;
    fprintf(stderr,"dpdk=%s driver=%s lcore=%u link_mbps=%u rx_desc=%u tx_desc=%u clock=CLOCK_MONOTONIC_RAW overhead_ns=%"PRIu64"\n",
        rte_version(),info.driver_name,rte_lcore_id(),link.link_speed,nrx,ntx,overhead);
    for(unsigned i=0;i<count+warmup;i++) {
        struct rte_mbuf *tx=rte_pktmbuf_alloc(pool);
        if(!tx) rte_exit(1,"TX allocation failed\n");
        uint8_t payload[1472];
        uint8_t *p=rte_pktmbuf_mtod(tx,uint8_t *);
        struct sample *s=&samples[i];
        uint32_t id=i+1; /* unique across warmup and measurement */
        s->enter=now();
        size_t payload_len=bench->encode(payload,sizeof(payload),id);
        if(!payload_len || payload_len>sizeof(payload)) rte_exit(1,"codec payload too large\n");
        if(!rte_pktmbuf_append(tx,42+payload_len)) rte_exit(1,"TX append failed\n");
        make_udp(p,&src,&dst,payload,payload_len);
        s->ready=now();
        while(!rte_eth_tx_burst(port,0,&tx,1)) {
            if(now()-s->enter>=timeout_ns) { rte_pktmbuf_free(tx); s->status=2; break; }
        }
        s->submitted=now();
        while(!s->status) {
            struct rte_mbuf *rx[32];
            uint16_t n=rte_eth_rx_burst(port,0,rx,32);
            uint64_t received=n?now():0;
            for(uint16_t j=0;j<n;j++) {
                uint8_t scratch[2048];
                size_t len=rte_pktmbuf_pkt_len(rx[j]);
                const uint8_t *data=len<=sizeof(scratch)?rte_pktmbuf_read(rx[j],0,len,scratch):NULL;
                const uint8_t *reply=NULL; size_t reply_len=0;
                int result=data?parse_udp(data,len,&src,&dst,&reply,&reply_len):0;
                if(result==1) result=bench->validate(reply,reply_len,id);
                if(result && !s->status) { s->received=received; s->status=result==1?1:3; }
                else unrelated++;
                rte_pktmbuf_free(rx[j]);
            }
            if(!s->status && now()-s->enter>=timeout_ns) s->status=2;
        }
        s->exit=now(); completed=i+1;
        if(s->status!=1) { failed++; break; } /* never silently discard timeouts */
    }
    fprintf(csv,"request_id,rpc_status,ok,benchmark,client_call_enter_ns,client_call_exit_ns,timestamp_overhead_ns,client_tx_ready_ns,client_tx_submit_ns,client_rx_burst_ns,warmup,transport\n");
    for(unsigned i=0;i<completed;i++) {
        struct sample *s=&samples[i]; int ok=s->status==1;
        fprintf(csv,"%u,%d,%d,%s,%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%d,dpdk\n",
            i+1,ok?0:s->status,ok,bench->name,
            s->enter,s->exit,overhead,s->ready,s->submitted,s->received,i<warmup);
    }
    int io_error=ferror(csv); if(fclose(csv)) io_error=1;
    struct rte_eth_stats stats={0}; check(rte_eth_stats_get(port,&stats),"stats");
    fprintf(stderr,"completed=%u warmup=%u failures=%"PRIu64" unrelated=%"PRIu64" ipackets=%"PRIu64" opackets=%"PRIu64" imissed=%"PRIu64" ierrors=%"PRIu64" oerrors=%"PRIu64"\n",
        completed,warmup,failed,unrelated,stats.ipackets,stats.opackets,stats.imissed,stats.ierrors,stats.oerrors);
    check(rte_eth_dev_stop(port),"stop"); check(rte_eth_dev_close(port),"close");
    rte_mempool_free(pool); free(samples); rte_eal_cleanup();
    return failed || io_error || completed!=count+warmup;
}
