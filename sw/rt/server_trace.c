#define _GNU_SOURCE
#include "server_trace.h"
#include <sched.h>
#include "lauberhorn/trace.h"
#ifndef SERVER_TRACE_CUSTOM_CONTEXT
#include "fiber/scheduler.h"
#endif
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <errno.h>
#include <unistd.h>
#include <sys/syscall.h>

#ifndef SERVER_TRACE_SIGNAL
#define SERVER_TRACE_SIGNAL SIGUSR2
#endif
#ifndef SERVER_TRACE_GUARD_BEGIN
#define SERVER_TRACE_GUARD_BEGIN() ((void)0)
#define SERVER_TRACE_GUARD_END() ((void)0)
#endif

/* Keep pthread-TLS accesses inside the guarded call for migrating runtimes. */
#ifdef SERVER_TRACE_CUSTOM_CONTEXT
#define TRACE_IMPL_ATTR __attribute__((noinline))
#else
#define TRACE_IMPL_ATTR
#endif
bool server_trace_enabled;
#ifndef SERVER_TRACE_CUSTOM_CONTEXT
__thread struct server_trace_row *server_trace_inline;
#endif
__thread struct server_activation_trace server_activation;
/* Append-only, single-producer buffers. Registration and export happen off path. */
#define TRACE_MAX_THREADS 64
struct trace_event {
    uint64_t ns, request_id, arg0, arg1;
    uint32_t context;
    int cpu;
    unsigned event;
};
struct trace_buffer {
    struct server_trace_row *rows;
    size_t count, dropped_count;
    _Atomic size_t published, dropped;
    int tid;
    struct trace_event *events;
    size_t event_count, event_drops;
    _Atomic size_t events_published, events_dropped;
} __attribute__((aligned(64)));
static struct trace_buffer buffers[TRACE_MAX_THREADS];
static __thread struct trace_buffer *local_buffer;
static size_t capacity, registered;
static bool pmu_enabled;
static size_t pmu_skip_rows, pmu_whole_rows;
static _Atomic size_t unregistered_drops;
static pthread_mutex_t control_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t dump_thread;
static bool dump_thread_started, dumped;
static _Atomic bool stopping;
static char *output_path;
static char *event_path;
static size_t event_capacity;
static _Atomic uint64_t next_trace_id = UINT64_C(1) << 32;
static FILE *output;
static void write_header(void);
static void write_row(const struct server_trace_row *r);
static void dump_records(void) {
    pthread_mutex_lock(&control_lock);
    if (dumped || !output_path) goto done;
    /* No traffic may remain when export is requested. Snapshot committed rows;
     * never race a writer's next slot or silently overwrite a previous dump. */
    size_t counts[TRACE_MAX_THREADS], drops = atomic_load(&unregistered_drops);
    for (size_t i=0;i<registered;i++) {
        counts[i] = atomic_load_explicit(&buffers[i].published, memory_order_acquire);
        drops += atomic_load_explicit(&buffers[i].dropped, memory_order_acquire);
    }
    output = fopen(output_path, "wx");
    if (!output) { perror("server trace export"); goto done; }
    write_header();
    size_t total = 0;
    for (size_t i=0;i<registered;i++) {
        for (size_t j=0;j<counts[i];j++) write_row(&buffers[i].rows[j]);
        total += counts[i];
    }
    int failed = ferror(output);
    if (fclose(output)) failed = 1;
    output = NULL;
    char *status_path;
    if (asprintf(&status_path, "%s.status.json", output_path) < 0) abort();
    char *status_temp;
    if (asprintf(&status_temp, "%s.tmp", status_path) < 0) abort();
    FILE *status = fopen(status_temp, "wx");
    if (!status) { perror("server trace export status"); failed = 1; }
    else {
        fprintf(status, "{\"rows\":%zu,\"dropped\":%zu,\"threads\":%zu,\"capacity_per_thread\":%zu,\"loss_free\":%s,\"snapshot_only\":true}\n",
                total, drops, registered, capacity, !failed && !drops ? "true" : "false");
        int status_failed = ferror(status);
        if (fclose(status)) status_failed = 1;
        /* Publish completion atomically, without replacing an earlier capture. */
        if (status_failed || link(status_temp, status_path)) failed = 1;
        unlink(status_temp);
    }
    free(status_temp);
    free(status_path);
    dumped = true;
    if (failed || drops) fprintf(stderr,"server trace capture failed: dropped=%zu io_error=%d\n",drops,failed);
    if (event_path) {
        FILE *events = fopen(event_path, "wx");
        size_t lost = atomic_load(&unregistered_drops), total_events = 0;
        if (!events) { perror("event trace export"); goto done; }
        fputs("timestamp_ns,request_id,request_context,tid,cpu,event,arg0,arg1\n", events);
        for (size_t i=0;i<registered;i++) {
            struct trace_buffer *b = &buffers[i];
            size_t n = atomic_load_explicit(&b->events_published, memory_order_acquire);
            lost += atomic_load_explicit(&b->events_dropped, memory_order_acquire);
            for (size_t j=0;j<n;j++) {
                const struct trace_event *e = &b->events[j];
                fprintf(events,"%" PRIu64 ",%" PRIu64 ",%u,%d,%d,%u,%" PRIu64 ",%" PRIu64 "\n",
                        e->ns,e->request_id,e->context,b->tid,e->cpu,e->event,e->arg0,e->arg1);
            }
            total_events += n;
        }
        int event_failed = ferror(events);
        if (fclose(events)) event_failed = 1;
        char *name;
        if (asprintf(&name,"%s.status.json",event_path)<0) abort();
        FILE *status = fopen(name,"wx");
        if (status) {
            fprintf(status,"{\"events\":%zu,\"dropped\":%zu,\"loss_free\":%s,\"clock\":\"CLOCK_MONOTONIC_RAW\"}\n",
                    total_events,lost,!lost && !event_failed ? "true":"false");
            fclose(status);
        } else perror("event trace status");
        free(name);
    }
done:
    pthread_mutex_unlock(&control_lock);
}
static void *dump_control(void *unused) {
    (void)unused;
    sigset_t set;
    sigemptyset(&set); sigaddset(&set, SERVER_TRACE_SIGNAL);
    for (;;) {
        int signo;
        if (sigwait(&set, &signo)) abort();
        if (atomic_load(&stopping)) break;
        dump_records();
    }
    return NULL;
}
void server_trace_thread_init(void) {
    if (!server_trace_enabled || local_buffer) return;
    pthread_mutex_lock(&control_lock);
    if (registered == TRACE_MAX_THREADS) abort();
    struct trace_buffer *b = &buffers[registered++];
    b->rows = calloc(capacity, sizeof(*b->rows));
    if (!b->rows) abort();
    /* Fault in every page on the startup path, before registering/activating. */
    volatile unsigned char *bytes = (volatile unsigned char *)b->rows;
    size_t size = capacity * sizeof(*b->rows);
    for (size_t i=0;i<size;i+=4096) bytes[i] = 0;
    bytes[size-1] = 0;
    if (event_path) {
        b->events = calloc(event_capacity, sizeof(*b->events));
        if (!b->events) abort();
        volatile unsigned char *p = (volatile unsigned char *)b->events;
        size_t n = event_capacity * sizeof(*b->events);
        for (size_t i=0;i<n;i+=4096) p[i]=0;
        p[n-1]=0;
    }
    b->tid = (int)syscall(SYS_gettid);
    local_buffer = b;
    pthread_mutex_unlock(&control_lock);
    if (pmu_enabled) lh_pmu_init();
}
static uint64_t overhead_ns;
uint64_t server_trace_clock(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC_RAW, &t);
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
uint64_t server_trace_stamp(struct server_trace_row *row) {
    if (!row || !row->enabled) return 0;
    row->timestamp_calls++;
    return server_trace_clock();
}
uint64_t server_trace_stage(struct server_trace_row *row, enum lh_pmu_stage stage) {
    if (row && row->enabled && row->pmu_mode &&
        (row->pmu_mode == 2 || stage == LH_PMU_rx_enter ||
         stage == LH_PMU_rx_exit || stage == LH_PMU_tx_enter)) {
        lh_pmu_read(&row->pmu[stage]);
        row->pmu_mask |= 1u << stage;
    }
    return server_trace_stamp(row);
}
static size_t pmu_rows(const char *name) {
    const char *s=getenv(name);
    if (!s) return 0;
    char *end; errno=0;
    unsigned long n=strtoul(s,&end,10);
    if (errno || !*s || *end || n>1000000) abort();
    return n;
}
void server_trace_init(void) {
    const char *path = getenv("LAUBERHORN_SERVER_TRACE_CSV");
    if (!path || !*path) path = getenv("ADDER_SERVER_TRACE_CSV");
    if (!path || !*path || output_path) return;
    pmu_enabled = getenv("LAUBERHORN_PMU") && !strcmp(getenv("LAUBERHORN_PMU"),"1");
    pmu_skip_rows = pmu_rows("LAUBERHORN_PMU_SKIP_ROWS");
    pmu_whole_rows = pmu_rows("LAUBERHORN_PMU_WHOLE_ROWS");
    output_path = strdup(path);
    if (!output_path) abort();
    capacity = 16384;
    const char *configured = getenv("LAUBERHORN_TRACE_CAPACITY");
    if (configured) {
        char *end; errno=0;
        unsigned long n = strtoul(configured, &end, 10);
        if (errno || !*configured || *end || !n || n>1000000) abort();
        capacity=n;
    }
    const char *events = getenv("LAUBERHORN_EVENT_TRACE_CSV");
    if (events && *events) {
        event_path = strdup(events);
        if (!event_path) abort();
        event_capacity = capacity * 32;
    }
    /* Reserve the dump signal before creating workers; they inherit the mask.
     * No signal handler or I/O on workers; allocation/locks are startup-only. */
    sigset_t set;
    sigemptyset(&set); sigaddset(&set, SERVER_TRACE_SIGNAL);
    if (pthread_sigmask(SIG_BLOCK, &set, NULL)) abort();
    if (pthread_create(&dump_thread, NULL, dump_control, NULL)) abort();
    dump_thread_started = true;
    overhead_ns = UINT64_MAX;
    for (int i = 0; i < 256; ++i) {
        uint64_t a = server_trace_clock(), b = server_trace_clock();
        if (b > a && b-a < overhead_ns) overhead_ns = b-a;
    }
    if (overhead_ns == UINT64_MAX) overhead_ns = 0;
    server_trace_enabled = true;
}
static void write_header(void) {
    fputs("request_id,xid,worker_id,ok,request_bytes,response_bytes,", output);
#define TRACE_HEADER(name) fputs("server_" #name "_ns,", output);
    SERVER_TRACE_TIMES(TRACE_HEADER)
    SERVER_TRACE_EXTRA_TIMES(TRACE_HEADER)
    SERVER_TRACE_ACCOUNTING_TIMES(TRACE_HEADER)
#undef TRACE_HEADER
    fputs("timestamp_overhead_ns,timestamp_call_count,request_id_valid,request_context,"
          "tx_worker_id,execution_kind,reply_status,transport_status,rx_cpu_id,tx_cpu_id,"
          "server_defer_enqueue_ns,server_forward_submit_ns,server_forward_complete_ns,"
          "server_handler_active_ns,server_handler_wait_ns,server_tid,activation_index,"
          "activation_worker,server_activation_ioctl_exit_ns,server_activation_ready_ns", output);
    fputs(",pmu_mode,pmu_mask",output);
    fputs(",server_tx_publish_ns,accounting_version",output);
#define PMU_HEADER(name) for (unsigned i=0;i<LH_PMU_COUNT;i++) fprintf(output,",pmu_" #name "_%u",i);
    SERVER_TRACE_TIMES(PMU_HEADER)
#undef PMU_HEADER
    fputc('\n',output);
}
void server_trace_fini(void) {
    /* Caller must have stopped all trace producers. Unsupported process exit
     * still uses _exit and is NOT an export mechanism. */
    if (!output_path) return;
    server_trace_enabled = false;
    dump_records();
    atomic_store(&stopping, true);
    if (dump_thread_started) {
        pthread_kill(dump_thread, SERVER_TRACE_SIGNAL);
        pthread_join(dump_thread, NULL);
    }
    for (size_t i=0;i<registered;i++) {
        free(buffers[i].rows); buffers[i].rows=NULL;
        free(buffers[i].events); buffers[i].events=NULL;
    }
    free(event_path); event_path=NULL;
    free(output_path); output_path=NULL;
}
void server_trace_rx_init(struct server_trace_row *r, int worker_id) {
    *r = (struct server_trace_row){.enabled=server_trace_enabled, .ok=true,
        .request_id=UINT64_MAX, .worker_id=worker_id, .tx_worker_id=-1,
        .activation=server_activation};
    if (pmu_enabled && local_buffer && local_buffer->count >= pmu_skip_rows)
        r->pmu_mode = local_buffer->count - pmu_skip_rows < pmu_whole_rows ? 1 : 2;
}
static struct server_trace_row *current_trace(void) {
#ifdef SERVER_TRACE_CUSTOM_CONTEXT
    return server_trace_inline;
#else
    return fiber_in_context() ? current_fiber->trace : server_trace_inline;
#endif
}
bool lauberhorn_trace_events_enabled(void) { return event_path != NULL; }
uint64_t lauberhorn_trace_new_id(void) {
    return event_path ? atomic_fetch_add_explicit(&next_trace_id,1,memory_order_relaxed) : 0;
}
static TRACE_IMPL_ATTR void trace_event_impl(struct server_trace_row *r, unsigned event, uint64_t a, uint64_t b) {
    if (!event_path || !r || !r->enabled) return;
    struct trace_buffer *buf = local_buffer;
    if (!buf) { atomic_fetch_add_explicit(&unregistered_drops,1,memory_order_relaxed); return; }
    if (buf->event_count == event_capacity) {
        atomic_store_explicit(&buf->events_dropped,++buf->event_drops,memory_order_release);
        return;
    }
    struct trace_event *e = &buf->events[buf->event_count];
    *e = (struct trace_event){.ns=server_trace_stamp(r),.request_id=r->request_id,
        .context=r->request_context,.cpu=sched_getcpu(),.event=event,.arg0=a,.arg1=b};
    atomic_store_explicit(&buf->events_published,++buf->event_count,memory_order_release);
}
void server_trace_event(struct server_trace_row *r, unsigned event, uint64_t a, uint64_t b) {
    SERVER_TRACE_GUARD_BEGIN();
    trace_event_impl(r, event, a, b);
    SERVER_TRACE_GUARD_END();
}
void lauberhorn_trace_event(enum lauberhorn_trace_event event, uint64_t a, uint64_t b) {
    server_trace_event(current_trace(),event,a,b);
}
void lauberhorn_trace_request_id(uint64_t id) {
    struct server_trace_row *r = current_trace();
    if (r && r->enabled) { r->request_id=id; r->id_valid=true; }
}
void lauberhorn_trace_result(bool ok) {
    struct server_trace_row *r = current_trace();
    if (r && r->enabled) r->ok &= ok;
}
void server_trace_activation_event(unsigned event, int worker) {
    if (!event_path) return;
    struct server_trace_row row = {.enabled=server_trace_enabled, .request_id=UINT64_MAX};
    server_trace_event(&row,event,worker,server_activation.index);
}
void server_trace_run_begin(struct server_trace_row *r) {
    server_trace_event(r,LH_TRACE_FIBER_RESUME,0,0);
    if (r && r->enabled) r->run_start_ns = server_trace_stamp(r);
}
void server_trace_run_end(struct server_trace_row *r) {
    server_trace_event(r,LH_TRACE_FIBER_SUSPEND,0,0);
    if (r && r->enabled && r->run_start_ns) {
        r->handler_active_ns += server_trace_stamp(r) - r->run_start_ns;
        r->run_start_ns = 0;
    }
}
static TRACE_IMPL_ATTR void trace_write_impl(struct server_trace_row *r) {
    if (!r || !r->enabled) return;
    r->enabled = false; /* terminal event: never write this ownership twice */
    struct trace_buffer *b = local_buffer;
    if (!b) { atomic_fetch_add_explicit(&unregistered_drops,1,memory_order_relaxed); return; }
    if (b->count == capacity) {
        atomic_store_explicit(&b->dropped,++b->dropped_count,memory_order_release);
        return;
    }
    b->rows[b->count] = *r;
    atomic_store_explicit(&b->published,++b->count,memory_order_release);
}
void server_trace_write(struct server_trace_row *r) {
    SERVER_TRACE_GUARD_BEGIN();
    trace_write_impl(r);
    SERVER_TRACE_GUARD_END();
}
static void write_row(const struct server_trace_row *r) {
    uint64_t elapsed = r->handler_exit_ns - r->handler_enter_ns;
    uint64_t active = r->handler_active_ns < elapsed ? r->handler_active_ns : elapsed;
    uint64_t wait = elapsed-active;
    /* Wire XID is unavailable in the opaque-context ABI: leave it empty. */
    fprintf(output, "%" PRIu64 ",,%d,%u,%u,%u,", r->request_id, r->worker_id,
            r->ok, r->request_bytes, r->response_bytes);
#define TRACE_VALUE(name) fprintf(output, "%" PRIu64 ",", r->name##_ns);
    SERVER_TRACE_TIMES(TRACE_VALUE)
    SERVER_TRACE_EXTRA_TIMES(TRACE_VALUE)
    SERVER_TRACE_ACCOUNTING_TIMES(TRACE_VALUE)
#undef TRACE_VALUE
    fprintf(output, "%" PRIu64 ",%u,%u,%u,%d,%u,%d,%d,%d,%d,%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%d,%" PRIu64 ",%d,%" PRIu64 ",%" PRIu64,
            overhead_ns, r->timestamp_calls, r->id_valid, r->request_context,
            r->tx_worker_id, r->kind, r->status, r->transport_status, r->rx_cpu_id, r->tx_cpu_id,
            r->defer_enqueue_ns, r->forward_submit_ns, r->forward_complete_ns,
            active, wait, r->activation.tid, r->activation.index,
            r->activation.worker, r->activation.ioctl_exit_ns, r->activation.ready_ns);
    fprintf(output,",%u,%u",r->pmu_mode,r->pmu_mask);
    fprintf(output,",%" PRIu64 ",4",r->tx_publish_ns);
    for (unsigned j=0;j<LH_PMU_STAGES;j++)
        for (unsigned i=0;i<LH_PMU_COUNT;i++) fprintf(output,",%"PRIu64,r->pmu[j].value[i]);
    fputc('\n',output);
}
