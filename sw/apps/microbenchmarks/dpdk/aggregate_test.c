/* Exercise the actual retirement/counter code without NIC or wall-clock pacing. */
#define main generator_main
#include "bench_dpdk.c"
#undef main
#include <assert.h>
int main(void) {
    aggregate_history_stride=((uint64_t)UINT32_MAX+63)/64*64;
    assert(aggregate_history_stride==UINT64_C(4294967296));
    assert(aggregate_index(UINT32_MAX,1)==UINT32_MAX-1ULL);
    open_aggregate=true;open_rate=1000;open_duration=1000000000;open_warmup=0;
    open_batch=1;aggregate_slots=4;aggregate_history_stride=1024;
    aggregate_history=calloc(1024,1);aggregate_histograms=calloc(AGG_BINS,8);
    struct sample samples[4]={0};
    struct parallel_load load={.samples=samples,.cores=1,.count=1000,.timeout=10000000,
        .start=now()-100000000,.bench=benchmark_find("echo8")};
    struct parallel_worker w={.load=&load,.sent=4};
    for(unsigned i=0;i<4;i++) {
        uint64_t due=parallel_batch_due(&load,i+1);
        samples[i]=(struct sample){.xid=i+1,.scheduled=due,.enter=due+100,
            .ready=due+200,.admitted=due+300,.submitted=due+400,
            .received=due+12440,.status=i==0?OL_ONTIME:i==1?OL_REJECTED:OL_PENDING,
            .rejections=i==1};
    }
    parallel_retire(&w,false);
    assert(!atomic_load(&load.failed) && w.retired==4);
    struct aggregate_counts *c=&aggregate_counts[0][1];
    assert(c->transmitted==4 && c->ontime==1 && c->rejections==1);
    assert(aggregate_histograms[123]==1 && samples[0].xid==0);
    uint8_t reply[32]={0};put32(reply,2);put32(reply+4,1);put32(reply+20,5);
    struct parallel_rx meta={.id=2,.length=24};
    parallel_retired_reply(&w,&meta,reply);
    assert(c->rejections==2 && c->duplicates==1 && !c->late);
    /* Expired request receives first rejection, then a late success, then duplicate. */
    meta.id=3;put32(reply,3);parallel_retired_reply(&w,&meta,reply);
    assert(c->rejections==3 && c->duplicates==1);
    put32(reply+20,0);meta.length=32;
    uint8_t call[48];load.bench->encode(call,sizeof(call),3);memcpy(reply+24,call+40,8);
    parallel_retired_reply(&w,&meta,reply);
    assert(c->late==1 && c->duplicates==2);
    parallel_retired_reply(&w,&meta,reply);
    assert(c->late==1 && c->duplicates==3);
    /* Reuse the bounded timestamp slot without erasing the old status ledger. */
    assert(parallel_prepare(&w,5)==&samples[0] && samples[0].xid==5);
    assert(aggregate_history[0]==OL_ONTIME);
    assert(!parallel_prepare(&w,9) && atomic_load(&load.failed));
    atomic_store(&load.failed,0);
    reply[31]^=1;parallel_retired_reply(&w,&meta,reply);
    assert(atomic_load(&load.failed) && c->malformed==1);
    puts("aggregate retirement, rollover, rejection, duplicate and corruption tests passed");
    return 0;
}
