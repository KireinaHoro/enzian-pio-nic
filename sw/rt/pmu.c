#define _GNU_SOURCE
#include "pmu.h"
#include <errno.h>
#include <inttypes.h>
#include <linux/perf_event.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifdef __aarch64__
static __thread struct perf_event_mmap_page *pages[LH_PMU_COUNT];
static __thread int fds[LH_PMU_COUNT];
static void die(const char *what) { perror(what); abort(); }
static uint64_t counter(unsigned index) {
    uint64_t value;
#ifdef LH_PMU_DIRECT
    switch (index) {
#define READ_CASE(n) case n+1: asm volatile("mrs %0, pmevcntr" #n "_el0" : "=r"(value)); break;
    READ_CASE(0) READ_CASE(1) READ_CASE(2) READ_CASE(3) READ_CASE(4) READ_CASE(5)
#undef READ_CASE
    case 32: asm volatile("mrs %0, pmccntr_el0" : "=r"(value)); break;
    default: abort();
    }
#else
    if (index == 32) asm volatile("mrs %0, pmccntr_el0" : "=r"(value));
    else {
        asm volatile("msr pmselr_el0, %0\n\tisb\n\tmrs %0, pmxevcntr_el0"
                     : "=r"(value) : "0"((uint64_t)(index - 1)) : "memory");
    }
#endif
    return value;
}
void lh_pmu_read(struct lh_pmu_snapshot *s) {
    /* ISB orders instruction execution at the boundary; deliberately no DSB:
     * this is event attribution, not an added cache-completion barrier. */
    asm volatile("isb" ::: "memory");
    for (unsigned i=0; i<LH_PMU_COUNT; ++i) {
        struct perf_event_mmap_page *p = pages[i];
        unsigned seq, index, width;
        uint64_t offset, value;
        do {
            seq = __atomic_load_n(&p->lock, __ATOMIC_ACQUIRE);
            if (seq & 1) continue;
            index = p->index; offset = p->offset; width = p->pmc_width;
            if (!p->cap_user_rdpmc || !index || !width || width > 64) {
                fputs("PMU counter unavailable/unscheduled; refusing partial counts\n", stderr);
                abort();
            }
            value = counter(index);
            if (width < 64) value = (int64_t)(value << (64-width)) >> (64-width);
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (seq == __atomic_load_n(&p->lock, __ATOMIC_RELAXED)) break;
        } while (1);
        s->value[i] = offset + value;
    }
}
void lh_pmu_init(void) {
    /* Architectural event IDs. PMCEID support is checked by the kernel.
     * This checks availability, not event accuracy: lh-pmu-probe independently
     * found nonmonotonic INST_RETIRED on zuestoll14 (2026-10-05), with both
     * userspace access methods and undercounting also through kernel perf reads.
     * See docs/development/pmu-profiling.md; do not infer valid IPC from PMCEID. */
    const uint64_t events[] = {0x08, 0x11, 0x03, 0x17};
    for (unsigned i=0; i<LH_PMU_COUNT; ++i) {
        struct perf_event_attr a = {
            .size=sizeof(a), .type=PERF_TYPE_RAW, .config=events[i],
            .config1=i==1 ? 3 : 2, /* native 64-bit cycles; 32-bit events */
            .disabled=i==0, .pinned=i==0, .exclude_kernel=1, .exclude_hv=1,
            .read_format=PERF_FORMAT_GROUP | PERF_FORMAT_TOTAL_TIME_ENABLED |
                         PERF_FORMAT_TOTAL_TIME_RUNNING,
        };
        fds[i] = syscall(SYS_perf_event_open, &a, 0, -1, i ? fds[0] : -1,
                         PERF_FLAG_FD_CLOEXEC);
        if (fds[i] < 0) die("perf_event_open PMU");
        pages[i] = mmap(NULL, sysconf(_SC_PAGESIZE), PROT_READ, MAP_SHARED, fds[i], 0);
        if (pages[i] == MAP_FAILED) die("mmap PMU");
    }
    if (ioctl(fds[0], PERF_EVENT_IOC_ENABLE, PERF_IOC_FLAG_GROUP)) die("enable PMU");
    struct lh_pmu_snapshot a,b;
    uint64_t low[LH_PMU_COUNT];
    for (unsigned j=0;j<LH_PMU_COUNT;j++) low[j]=UINT64_MAX;
    for (unsigned i=0;i<256;i++) {
        lh_pmu_read(&a); lh_pmu_read(&b);
        for (unsigned j=0;j<LH_PMU_COUNT;j++)
            if (b.value[j]-a.value[j]<low[j]) low[j]=b.value[j]-a.value[j];
    }
    struct { uint64_t nr, enabled, running, value[LH_PMU_COUNT]; } result;
    if (read(fds[0], &result, sizeof(result)) != sizeof(result) ||
        result.nr != LH_PMU_COUNT || result.enabled != result.running || !result.running) {
        fputs("PMU pinned group not running continuously\n",stderr); abort();
    }
    fprintf(stderr,"PMU_READY tid=%ld events=08,11,03,17 user_only=1 pinned=1 pair_min=%"PRIu64",%"PRIu64",%"PRIu64",%"PRIu64"\n",
            syscall(SYS_gettid),low[0],low[1],low[2],low[3]);
}
#else
void lh_pmu_init(void) { fputs("PMU profiling requires AArch64\n",stderr); abort(); }
void lh_pmu_read(struct lh_pmu_snapshot *s) { (void)s; abort(); }
#endif
#ifdef LH_PMU_PROBE
int main(void) {
    lh_pmu_init();
    struct lh_pmu_snapshot a,b;
    lh_pmu_read(&a);
#ifdef __aarch64__
    uint64_t n=100000;
    asm volatile("1: subs %0, %0, #1\n\tb.ne 1b" : "+r"(n) :: "cc","memory");
#endif
    lh_pmu_read(&b);
    printf("PMU_LOOP instructions=%"PRIu64" cycles=%"PRIu64" l1d=%"PRIu64" l2d=%"PRIu64"\n",
        b.value[0]-a.value[0],b.value[1]-a.value[1],b.value[2]-a.value[2],b.value[3]-a.value[3]);
    if (b.value[0]-a.value[0]<200000 || b.value[0]-a.value[0]>202000) return 1;
    int64_t min=INT64_MAX,max=INT64_MIN,total=0;
    unsigned negative=0;
    for (unsigned k=0;k<100000;k++) {
        lh_pmu_read(&a); lh_pmu_read(&b);
        int64_t d=b.value[0]-a.value[0];
        if (d<min) min=d;
        if (d>max) max=d;
        total+=d; negative+=d<0;
    }
    printf("PMU_REPEAT n=100000 min=%"PRId64" max=%"PRId64" mean=%.3f negative=%u\n",min,max,total/100000.,negative);
    size_t bytes=64*1024*1024;
    volatile unsigned char *memory=calloc(1,bytes);
    if (!memory) return 1;
    for (size_t i=0;i<bytes;i+=64) memory[i]=1;
    uint64_t refills[2], sum=0;
    int instructions_valid = negative == 0;
    struct { uint64_t nr, enabled, running, value[LH_PMU_COUNT]; } before, after;
    for (unsigned test=0;test<2;test++) {
        size_t mask=test ? bytes-1 : 511;
        if (read(fds[0], &before, sizeof(before)) != sizeof(before)) return 1;
        lh_pmu_read(&a);
        for (size_t i=0;i<bytes;i+=64) sum+=memory[i & mask];
        lh_pmu_read(&b);
        if (read(fds[0], &after, sizeof(after)) != sizeof(after)) return 1;
        refills[test]=b.value[2]-a.value[2];
        /* Disassembly: six instructions per iteration in this common loop.
         * Permit boundary overhead, never a workload-dependent undercount. */
        uint64_t expected=6*(bytes/64), observed=after.value[0]-before.value[0];
        instructions_valid &= observed >= expected && observed <= expected+10000;
        printf("PMU_KERNEL_READ bytes=%zu instructions=%"PRIu64" cycles=%"PRIu64"\n",
            mask+1,after.value[0]-before.value[0],after.value[1]-before.value[1]);
        printf("PMU_MEMORY bytes=%zu loads=%zu instructions=%"PRIu64" cycles=%"PRIu64" l1d=%"PRIu64" l2d=%"PRIu64"\n",
            mask+1,bytes/64,b.value[0]-a.value[0],b.value[1]-a.value[1],refills[test],b.value[3]-a.value[3]);
    }
    free((void *)memory);
    int cache_valid = sum && refills[1] > refills[0]+10000;
    printf("PMU_VALIDATION instructions=%s cache_response=%s\n",
        instructions_valid ? "pass" : "FAIL",cache_valid ? "pass" : "FAIL");
    return !cache_valid ? 1 : !instructions_valid ? 2 : 0;
}
#endif
