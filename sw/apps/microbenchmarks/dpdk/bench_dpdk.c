#define _GNU_SOURCE
#include <sched.h>
#include <fcntl.h>
/* Closed-loop latency generator. No allocation or file I/O in the timed loop. */
#include <arpa/inet.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#include <rte_cycles.h>
#include <rte_eal.h>
#include <rte_ethdev.h>
#include <rte_lcore.h>
#include <rte_mbuf.h>
#include <rte_version.h>
#include "wire.h"
#include "benchmark.h"
struct sample { uint64_t enter, ready, submitted, received, exit; uint64_t scheduled; uint32_t duplicates; int status; } __rte_cache_aligned;
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
#include "open_loop.h"
static unsigned open_rate;
static unsigned open_cpus[32];
static uint64_t open_duration, open_warmup;
#include "load.h"
/* Preparation traffic is outside the measured RPC. Preserve every reply and
 * fail the run if eviction preparation times out; state is verified by traces. */
struct flush_sample { uint64_t enter, exit; unsigned received, unrelated; int ok; };
static struct flush_sample flush_domain(uint16_t port, struct rte_mempool *pool,
        struct endpoint src, struct endpoint dst, uint32_t first, uint64_t timeout) {
    enum { WINDOW = 16 };
    const struct benchmark *bench=benchmark_find("mul");
    struct flush_sample result={.enter=now()};
    uint32_t seen=0;
    struct rte_mbuf *packets[WINDOW];
    dst.port=12346;
    for (unsigned k=0;k<WINDOW;k++) {
        struct rte_mbuf *tx=rte_pktmbuf_alloc(pool);
        if (!tx) rte_exit(1,"flush allocation failed\n");
        uint8_t payload[64];
        size_t n=bench->encode(payload,sizeof(payload),first+k);
        uint8_t *p=(uint8_t *)rte_pktmbuf_append(tx,42+n);
        if (!p) rte_exit(1,"flush append failed\n");
        make_udp(p,&src,&dst,payload,n);
        packets[k]=tx;
    }
    /* Enqueue the prepared packets together: per-packet encoding between TX
     * calls allowed resident workers to drain the queue before it could evict. */
    unsigned sent=0;
    while (sent<WINDOW) {
        sent+=rte_eth_tx_burst(port,0,packets+sent,WINDOW-sent);
        if (sent<WINDOW && now()-result.enter>=timeout) {
            for(unsigned k=sent;k<WINDOW;k++) rte_pktmbuf_free(packets[k]);
            goto done;
        }
    }
    while (result.received<WINDOW && now()-result.enter<timeout) {
        struct rte_mbuf *rx[32];
        uint16_t n=rte_eth_rx_burst(port,0,rx,WINDOW-result.received);
        int invalid=0;
        for (uint16_t k=0;k<n;k++) {
            uint8_t scratch[2048];
            size_t length=rte_pktmbuf_pkt_len(rx[k]);
            const uint8_t *data=length<=sizeof(scratch)?rte_pktmbuf_read(rx[k],0,length,scratch):NULL;
            const uint8_t *reply=NULL; size_t size=0;
            int parsed=data?parse_udp(data,length,&src,&dst,&reply,&size):0;
            if (!parsed) result.unrelated++; /* e.g. ARP after VF rebinding */
            else if (parsed==1 && size>=4) {
                uint32_t id=get32(reply), offset=id-first;
                if (offset>=WINDOW) result.unrelated++;
                else if (!(seen&(1u<<offset)) && bench->validate(reply,size,id)==1) {
                    seen |= 1u<<offset; result.received++;
                } else invalid=1;
            } else invalid=1;
            rte_pktmbuf_free(rx[k]);
        }
        if (invalid) goto done;
    }
    result.ok=result.received==WINDOW;
done:
    result.exit=now();
    return result;
}
int main(int argc, char **argv) {
    int consumed=rte_eal_init(argc,argv);
    if(consumed<0) return 1;
    argc-=consumed; argv+=consumed;
    if(argc!=10 && argc!=11) rte_exit(1,
        "EAL options -- SRC_IP DST_IP DST_MAC DST_PORT COUNT WARMUP TIMEOUT_US BENCHMARK OUTPUT.csv [loopback|flush:N[:ROUNDS]]\n"
        "Use 1-32 EAL lcores and one allowlisted port (cpu40g1 VF). WARMUP may be zero.\n");
    unsigned generators=rte_lcore_count();
    unsigned queues=generators>8?8:generators;
    if(generators>32 || rte_eth_dev_count_avail()!=1)
        rte_exit(1,"require 1-32 generator cores and one port\n");
    struct endpoint src={.port=49152}, dst={0};
    if(inet_pton(AF_INET,argv[1],src.ip)!=1 || inet_pton(AF_INET,argv[2],dst.ip)!=1)
        rte_exit(1,"invalid IPv4 address\n");
    struct rte_ether_addr dmac;
    if(rte_ether_unformat_addr(argv[3],&dmac)) rte_exit(1,"invalid destination MAC\n");
    memcpy(dst.mac,dmac.addr_bytes,6); dst.port=number(argv[4],65535);
    unsigned count=number(argv[5],10000000), warmup=0;
    const char *rate_env=getenv("LH_BENCH_RATE");
    if(rate_env) {
        open_rate=number(rate_env,100000000);
        const char *duration=getenv("LH_BENCH_DURATION_MS");
        const char *warming=getenv("LH_BENCH_WARMUP_MS");
        if(!duration || !warming || argc!=10) rte_exit(1,"open loop requires duration and warmup milliseconds\n");
        open_duration=(uint64_t)number(duration,60000)*1000000;
        open_warmup=strcmp(warming,"0")?(uint64_t)number(warming,60000)*1000000:0;
        uint64_t total=(open_duration+open_warmup)*open_rate/1000000000;
        const char *budget=getenv("LH_BENCH_TRACKING_MIB");
        uint64_t tracking_limit=(uint64_t)(budget?number(budget,262144):4096)*1024*1024;
        if(!total || total>UINT32_MAX || total*sizeof(struct sample)>tracking_limit)
            rte_exit(1,"open-loop tracking requires %"PRIu64" bytes (limit %"PRIu64"); slots %"PRIu64" must fit XID space\n",
                     total*sizeof(struct sample),tracking_limit,total);
        warmup=open_warmup*open_rate/1000000000;
        count=total-warmup;
        if((open_warmup*open_rate)%1000000000 || (open_duration*open_rate)%1000000000)
            rte_exit(1,"duration must contain an integral number of schedule slots\n");
    }
    const char *window_env=getenv("LH_BENCH_WINDOW");
    const char *gap_env=getenv("LH_BENCH_GAP_NS");
    unsigned window=window_env?number(window_env,256):1;
    uint64_t gap=gap_env && strcmp(gap_env,"0")?number(gap_env,1000000000):0;
    if(!open_rate && strcmp(argv[6],"0")) warmup=number(argv[6],1000000);
    uint64_t timeout_ns=(uint64_t)number(argv[7],10000000)*1000;
    if(open_rate && timeout_ns!=10000000) rte_exit(1,"open-loop deadline must be 10 ms\n");
    const struct benchmark *bench=benchmark_find(argv[8]);
    if(!bench) rte_exit(1,"unknown benchmark: %s\n",argv[8]);
    FILE *csv=fopen(argv[9],"wx");
    if(!csv) rte_exit(1,"cannot create CSV: %s\n",strerror(errno));
    if(open_rate) {
        int allocation=posix_fallocate(fileno(csv),0,(off_t)(count+warmup)*256);
        if(allocation) rte_exit(1,"cannot reserve observation output: %s\n",strerror(allocation));
    }
    struct sample *samples=aligned_alloc(RTE_CACHE_LINE_SIZE,(count+warmup)*sizeof(*samples));
    if(!samples) rte_exit(1,"sample allocation failed\n");
    memset(samples,0,(count+warmup)*sizeof(*samples));
    /* Fault in each result page before measuring. */
    for(unsigned i=0;i<count+warmup;i++) ((volatile struct sample *)samples)[i].status=0;
    uint16_t port; RTE_ETH_FOREACH_DEV(port) { break; }
    struct rte_eth_dev_info info;
    check(rte_eth_dev_info_get(port,&info),"device info");
    struct rte_eth_conf config={0};
    unsigned flush_every=0, flush_rounds=2;
    if(argc==11 && !strncmp(argv[10],"flush:",6)) {
        char *spec=strdup(argv[10]+6);
        if(!spec) rte_exit(1,"flush option allocation failed\n");
        char *rounds=strchr(spec,':');
        if(rounds) { *rounds++=0; flush_rounds=number(rounds,8); }
        flush_every=number(spec,10000000);
        free(spec);
        if(strcmp(bench->name,"add")) rte_exit(1,"flush mode requires add\n");
    }
    struct flush_sample *flushes=flush_every?calloc((size_t)(count+warmup)*flush_rounds,sizeof(*flushes)):NULL;
    if(flush_every && window!=1) rte_exit(1,"per-request cold preparation requires window 1\n");
    if(flush_every && !flushes) rte_exit(1,"flush result allocation failed\n");
    if(argc==11 && !flush_every) {
        if(strcmp(argv[10],"loopback") || strcmp(bench->name,"echo") ||
           memcmp(src.ip,dst.ip,4) || src.port!=dst.port)
            rte_exit(1,"loopback requires echo with identical IP/port endpoints\n");
        config.lpbk_mode=1;
    }
    if(generators>1) {
        if(argc!=10 || (!window_env && !open_rate) || !(info.flow_type_rss_offloads&RTE_ETH_RSS_NONFRAG_IPV4_UDP))
            rte_exit(1,"parallel generation requires bounded load and UDP RSS\n");
        config.rxmode.mq_mode=RTE_ETH_MQ_RX_RSS;
        config.rx_adv_conf.rss_conf.rss_hf=RTE_ETH_RSS_NONFRAG_IPV4_UDP;
    }
    check(rte_eth_dev_configure(port,queues,queues,&config),"configure");
    uint16_t nrx=256,ntx=256;
    check(rte_eth_dev_adjust_nb_rx_tx_desc(port,&nrx,&ntx),"descriptors");
    int socket=rte_eth_dev_socket_id(port); if(socket<0) socket=0;
    /* ThunderX RBDR buffers require 128-byte alignment. Private forwarding
     * metadata must preserve that alignment, beyond DPDK's generic minimum. */
    struct rte_mempool *pool=rte_pktmbuf_pool_create("bench",generators>1?16383:4095,128,RTE_ALIGN_CEIL(sizeof(struct parallel_rx),128),
        RTE_MBUF_DEFAULT_BUF_SIZE,socket);
    if(!pool) rte_exit(1,"mempool failed\n");
    for(unsigned q=0;q<queues;q++) {
        check(rte_eth_rx_queue_setup(port,q,nrx,socket,&info.default_rxconf,pool),"RX queue");
        check(rte_eth_tx_queue_setup(port,q,ntx,socket,&info.default_txconf),"TX queue");
    }
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
    /* Optional campaign barrier after NIC setup, outside every timed RPC. */
    const char *ready_file=getenv("LH_BENCH_READY_FILE");
    const char *start_file=getenv("LH_BENCH_START_FILE");
    if (!!ready_file != !!start_file) rte_exit(1,"both campaign barrier paths required\n");
    if (ready_file) {
        if (!access(ready_file,F_OK) || !access(start_file,F_OK))
            rte_exit(1,"stale campaign barrier file\n");
        FILE *ready=fopen(ready_file,"wx");
        if (!ready || fclose(ready)) rte_exit(1,"cannot publish campaign readiness\n");
        uint64_t deadline=now()+300000000000ULL;
        while (access(start_file,F_OK)) {
            if (errno!=ENOENT || now()>=deadline)
                rte_exit(1,"campaign start barrier failed or timed out\n");
            rte_delay_us_sleep(10000);
        }
    }
    unsigned prepared=0;
    uint64_t observation_start=0, observation_end=0, next_send=0;
    if(open_rate) {
        int load_failed=0;
        completed=parallel_phase(port,pool,src,dst,bench,samples,0,count+warmup,window,gap,timeout_ns,&unrelated,&load_failed,&observation_start,&observation_end);
        observation_start+=open_warmup;
        observation_end=observation_start+open_duration;
        failed=load_failed;
        /* Campaign stop control must not wait for potentially large CSV output.
         * The bounded receive drain has finished and the send interval is fixed. */
        const char *drained=getenv("LH_BENCH_DRAINED_FILE");
        if(drained) {
            FILE *signal=fopen(drained,"wx");
            if(!signal || fclose(signal)) rte_exit(1,"cannot publish drained marker\n");
        }
    } else if(generators>1) {
        int load_failed=0;uint64_t warm_start=0,warm_end=0;
        completed=parallel_phase(port,pool,src,dst,bench,samples,0,warmup,window,gap,timeout_ns,&unrelated,&load_failed,&warm_start,&warm_end);
        if(!load_failed) completed+=parallel_phase(port,pool,src,dst,bench,samples,warmup,count,window,gap,timeout_ns,&unrelated,&load_failed,&observation_start,&observation_end);
        failed=load_failed;
    } else if(window_env && !flush_every) {
        int load_failed=0;
        completed=load_phase(port,pool,src,dst,bench,samples,0,warmup,window,gap,timeout_ns,&unrelated,&load_failed);
        if(!load_failed) {
            observation_start=now();
            completed+=load_phase(port,pool,src,dst,bench,samples,warmup,count,window,gap,timeout_ns,&unrelated,&load_failed);
            observation_end=now();
        }
        failed=load_failed;
    } else {
    for(unsigned i=0;i<count+warmup;i++) {
        if(i==warmup) observation_start=now();
        while(now()<next_send) { /* Explicit closed-loop pacing, outside RTT. */ }
        if(flush_every && i%flush_every==0) {
            for(unsigned round=0;round<flush_rounds;round++) {
                flushes[prepared]=flush_domain(port,pool,src,dst,0x80000000u+prepared*16,timeout_ns);
                if(!flushes[prepared++].ok) { failed++; break; }
            }
            if(failed) break;
        }
        struct rte_mbuf *tx=rte_pktmbuf_alloc(pool);
        if(!tx) rte_exit(1,"TX allocation failed\n");
        uint8_t payload[1472];
        uint8_t *p=rte_pktmbuf_mtod(tx,uint8_t *);
        struct sample *s=&samples[i];
        uint32_t id=i+1; /* unique across warmup and measurement */
        s->enter=now();
        next_send=s->enter+gap;
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
    observation_end=now();
    }
    fprintf(csv,"request_id,rpc_status,ok,benchmark,client_call_enter_ns,client_call_exit_ns,timestamp_overhead_ns,client_tx_ready_ns,client_tx_submit_ns,client_rx_burst_ns,warmup,transport,scheduled_ns,start_lag_ns,duplicates\n");
    for(unsigned i=0;i<((generators>1 || open_rate)?count+warmup:completed);i++) {
        struct sample *s=&samples[i];
        if(!open_rate && generators>1 && !s->enter) continue;
        int ok=s->status==1;
        fprintf(csv,"%u,%d,%d,%s,%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64",%d,dpdk,%"PRIu64",%"PRIu64",%u\n",
            i+1,ok?0:s->status,ok,bench->name,
            s->enter,s->exit,overhead,s->ready,s->submitted,s->received,i<warmup,s->scheduled,s->enter>=s->scheduled?s->enter-s->scheduled:0,s->duplicates);
    }
    int io_error=ferror(csv);
    if(open_rate) {
        if(fflush(csv) || ftruncate(fileno(csv),ftello(csv))) io_error=1;
    }
    if(fclose(csv)) io_error=1;
    {
        size_t size=strlen(argv[9])+32;
        char *path=malloc(size);
        if(!path) rte_exit(1,"summary path allocation failed\n");
        snprintf(path,size,"%s.load.json",argv[9]);
        FILE *f=fopen(path,"wx");
        if(!f) rte_exit(1,"cannot create load summary\n");
        fprintf(f,"{\"generator_cores\":%u,\"window\":%u,\"gap_ns\":%"PRIu64",\"observation_start_ns\":%"PRIu64",\"observation_end_ns\":%"PRIu64",\"expected_measured\":%u,\"attempted_including_warmup\":%u,\"warmup\":%u,\"failed\":%"PRIu64",\"flush_every\":%u,\"flush_rounds\":%u}\n",
                generators,window,gap,observation_start,observation_end,count,completed,warmup,failed,flush_every,flush_rounds);
        if(ferror(f)) io_error=1;
        if(fclose(f)) io_error=1;
        free(path);
    }
    if(open_rate) {
        for(unsigned i=0;i<warmup;i++)
            failed |= samples[i].status==OL_MISSED || samples[i].status==OL_TX_FAILED || samples[i].status==OL_MALFORMED;
        uint64_t transmitted=0, ontime=0, late=0, duplicates=0, misses=0, admission=0, malformed=0;
        for(unsigned i=warmup;i<count+warmup;i++) {
            struct sample *s=&samples[i];
            transmitted+=s->submitted && s->status!=OL_TX_FAILED;
            ontime+=s->status==OL_ONTIME; late+=s->status==OL_LATE;
            misses+=s->status==OL_MISSED; admission+=s->status==OL_TX_FAILED;
            malformed+=s->status==OL_MALFORMED; duplicates+=s->duplicates;
        }
        struct rte_eth_stats stats={0}; check(rte_eth_stats_get(port,&stats),"open-loop NIC stats");
        char path[4096]; snprintf(path,sizeof(path),"%s.open.json",argv[9]);
        FILE *f=fopen(path,"wx"); if(!f) rte_exit(1,"open-loop summary output failed\n");
        fprintf(f,"{\"schema\":2,\"benchmark\":\"%s\",\"warmup_ns\":%"PRIu64",\"send_start_ns\":%"PRIu64",\"send_end_ns\":%"PRIu64",",
                bench->name,open_warmup,observation_start,observation_end);
        fprintf(f,"\"rate\":%u,\"duration_ns\":%"PRIu64",\"deadline_ns\":%"PRIu64","
          "\"transmitted\":%"PRIu64",\"on_time\":%"PRIu64",\"late\":%"PRIu64",\"duplicates\":%"PRIu64","
          "\"generator_misses\":%"PRIu64",\"tx_admission_failures\":%"PRIu64",\"malformed\":%"PRIu64","
          "\"nic_rx_no_buffer\":%"PRIu64",\"nic_rx_missed\":%"PRIu64",\"nic_rx_errors\":%"PRIu64",\"nic_tx_errors\":%"PRIu64","
          "\"valid\":%s,\"generator_cpus\":[",open_rate,open_duration,timeout_ns,transmitted,ontime,late,duplicates,misses,admission,malformed,
          stats.rx_nombuf,stats.imissed,stats.ierrors,stats.oerrors,(!failed&&!misses&&!admission&&!malformed&&!stats.ierrors&&!stats.oerrors&&!stats.imissed&&!stats.rx_nombuf)?"true":"false");
        for(unsigned i=0;i<generators;i++) fprintf(f,"%s%u",i?",":"",open_cpus[i]);
        fputs("]}\n",f);
        if(ferror(f)) io_error=1;
        if(fclose(f)) io_error=1;
        failed |= misses || admission || malformed || stats.ierrors || stats.oerrors || stats.imissed || stats.rx_nombuf;
    }
    if(flush_every) {
        size_t size=strlen(argv[9])+32;
        char *path=malloc(size);
        if(!path) rte_exit(1,"flush output allocation failed\n");
        snprintf(path,size,"%s.flush.csv",argv[9]);
        FILE *f=fopen(path,"wx");
        if(!f) rte_exit(1,"cannot create flush evidence\n");
        fprintf(f,"first_request_id,enter_ns,exit_ns,expected,received,ok,unrelated\n");
        for(unsigned i=0;i<prepared;i++) fprintf(f,"%u,%"PRIu64",%"PRIu64",16,%u,%d,%u\n",
            0x80000000u+i*16,flushes[i].enter,flushes[i].exit,flushes[i].received,flushes[i].ok,flushes[i].unrelated);
        if(ferror(f)) io_error=1;
        if(fclose(f)) io_error=1;
        free(path); free(flushes);
    }
    struct rte_eth_stats stats={0}; check(rte_eth_stats_get(port,&stats),"stats");
    fprintf(stderr,"completed=%u warmup=%u failures=%"PRIu64" unrelated=%"PRIu64" ipackets=%"PRIu64" opackets=%"PRIu64" imissed=%"PRIu64" ierrors=%"PRIu64" oerrors=%"PRIu64"\n",
        completed,warmup,failed,unrelated,stats.ipackets,stats.opackets,stats.imissed,stats.ierrors,stats.oerrors);
    check(rte_eth_dev_stop(port),"stop"); check(rte_eth_dev_close(port),"close");
    rte_mempool_free(pool); free(samples); rte_eal_cleanup();
    return failed || io_error || completed!=count+warmup;
}
