/* Loaded aggregate mode: bounded timestamp window, exact status ledger and
 * mergeable 100 ns RTT histogram. Included after open_loop.h and sample. */
#define AGG_BIN_NS 100ULL
#define AGG_BINS (10000000U / AGG_BIN_NS + 1U)
#define AGG_FORWARD_NS 10000000ULL
#define AGG_RETAIN_NS 30000000ULL
struct aggregate_counts {
    uint64_t attempted, transmitted, ontime, late, duplicates, misses, admission, malformed, rejections;
    uint64_t lag_count, lag_sum, lag_max, schedule_violations;
} __rte_cache_aligned;
static bool open_aggregate;
static unsigned aggregate_slots;
static size_t aggregate_history_stride;
static struct sample aggregate_diagnostics[32][3];
static size_t aggregate_index(unsigned id,unsigned cores) {
    return (size_t)((id-1)%cores)*aggregate_history_stride+(id-1)/cores;
}
static uint8_t *aggregate_history;
static uint64_t *aggregate_histograms;
static struct aggregate_counts aggregate_counts[32][2];
static struct aggregate_counts aggregate_total[2];
static void aggregate_add(struct aggregate_counts *c, const struct sample *s) {
    c->attempted++;c->transmitted+=s->submitted && s->status!=OL_TX_FAILED;
    c->ontime+=s->status==OL_ONTIME;c->late+=s->status==OL_LATE;
    c->misses+=s->status==OL_MISSED;c->admission+=s->status==OL_TX_FAILED;
    c->malformed+=s->status==OL_MALFORMED;c->duplicates+=s->duplicates;c->rejections+=s->rejections;
    if(s->enter) {
        uint64_t lag=s->enter>=s->scheduled?s->enter-s->scheduled:0;
        c->lag_count++;c->lag_sum+=lag;if(lag>c->lag_max)c->lag_max=lag;
    }
}
static void aggregate_merge(unsigned cores) {
    for(unsigned i=0;i<cores;i++) for(unsigned phase=0;phase<2;phase++) {
        struct aggregate_counts *a=&aggregate_total[phase],*b=&aggregate_counts[i][phase];
#define SUM(field) a->field+=b->field
        SUM(attempted);SUM(transmitted);SUM(ontime);SUM(late);SUM(duplicates);SUM(misses);
        SUM(schedule_violations);SUM(admission);SUM(malformed);SUM(rejections);SUM(lag_count);SUM(lag_sum);
#undef SUM
        if(b->lag_max>a->lag_max)a->lag_max=b->lag_max;
    }
    for(unsigned i=1;i<cores;i++) for(unsigned j=0;j<AGG_BINS;j++)
        aggregate_histograms[j]+=aggregate_histograms[(size_t)i*AGG_BINS+j];
}
static void aggregate_write(FILE *f) {
    fprintf(f,"\"observation_mode\":\"aggregate\",\"timestamp_retention_ns\":30000000,\"rx_forward_limit_ns\":10000000,\"timestamp_slots_per_core\":%u,\"histogram_bin_ns\":%u,\"latency_histogram\":[",aggregate_slots,(unsigned)AGG_BIN_NS);
    bool first=true;
    for(unsigned i=0;i<AGG_BINS;i++) if(aggregate_histograms[i]) {
        fprintf(f,"%s[%u,%"PRIu64"]",first?"":",",i,aggregate_histograms[i]);first=false;
    }
    fputs("],\"phases\":[",f);
    for(unsigned phase=0;phase<2;phase++) {
        struct aggregate_counts *c=&aggregate_total[phase];
        fprintf(f,"%s{\"attempted\":%"PRIu64",\"transmitted\":%"PRIu64",\"on_time\":%"PRIu64",\"late\":%"PRIu64",\"duplicates\":%"PRIu64",\"generator_misses\":%"PRIu64",\"tx_admission_failures\":%"PRIu64",\"malformed\":%"PRIu64",\"server_rejections\":%"PRIu64",\"lag_count\":%"PRIu64",\"lag_sum_ns\":%"PRIu64",\"lag_max_ns\":%"PRIu64",\"schedule_violations\":%"PRIu64"}",phase?",":"",c->attempted,c->transmitted,c->ontime,c->late,c->duplicates,c->misses,c->admission,c->malformed,c->rejections,c->lag_count,c->lag_sum,c->lag_max,c->schedule_violations);
    }
    fputs("],",f);
}
