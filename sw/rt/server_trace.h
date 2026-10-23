#ifndef LAUBERHORN_SERVER_TRACE_H
#define LAUBERHORN_SERVER_TRACE_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdatomic.h>
#include "lauberhorn/trace.h"
/* Accounting schema 5: boundaries of work, separate from the PMU stage ABI. */
#define SERVER_TRACE_ACCOUNTING_TIMES(X) \
    X(backend_enter) X(select_enter) X(select_exit) \
    X(decode_enter) X(decode_exit) X(reserve_enter) \
    X(encode_enter) X(encode_exit) X(reply_ready) \
    X(rx_gather_enter) X(network_rx_end) X(packet_ready)
#if LH_ENABLE_TRACING
#define LH_TRACE_PTR(value) (value)
#include "pmu.h"
/* Transport adapters may add timestamp columns and supply a migrating
 * request-context slot. The writer also accepts SERVER_TRACE_SIGNAL and
 * SERVER_TRACE_GUARD_BEGIN/END; defaults preserve the pthread/fiber path. */
#ifndef SERVER_TRACE_EXTRA_TIMES
#define SERVER_TRACE_EXTRA_TIMES(X)
#endif
#define SERVER_TRACE_TIMES(X) \
    X(rx_enter) X(rx_unblock) X(rx_exit) \
    X(handler_enter) X(handler_exit)
#define PMU_ENUM(name) LH_PMU_##name,
enum lh_pmu_stage { SERVER_TRACE_TIMES(PMU_ENUM) LH_PMU_tx_enter, LH_PMU_STAGES };
#undef PMU_ENUM
struct server_activation_trace {
    uint64_t ioctl_exit_ns, ready_ns, index;
    int tid, worker;
};
extern __thread struct server_activation_trace server_activation;
extern __thread bool server_activation_pending;
struct server_trace_row {
    _Atomic unsigned committed; /* 0 pending, 1 complete, 2 discarded */
    struct server_activation_trace activation;
    bool enabled, id_valid, ok;
    uint64_t request_id;
    uint32_t request_context, request_bytes, response_bytes;
    int worker_id, tx_worker_id, rx_cpu_id, tx_cpu_id, status, transport_status;
    unsigned pmu_mode, pmu_mask;
    struct lh_pmu_snapshot pmu[LH_PMU_STAGES];
    unsigned kind; /* kind bits: deferred=1, forwarded=2 */
#define TRACE_FIELD(name) uint64_t name##_ns;
    SERVER_TRACE_TIMES(TRACE_FIELD)
    SERVER_TRACE_EXTRA_TIMES(TRACE_FIELD)
    SERVER_TRACE_ACCOUNTING_TIMES(TRACE_FIELD)
#undef TRACE_FIELD
    uint64_t defer_enqueue_ns, forward_submit_ns, forward_complete_ns;
    uint64_t tx_publish_ns; /* immediately before the commit CL read */
    uint64_t handler_active_ns, run_start_ns;
};
extern bool server_trace_enabled;
#ifdef SERVER_TRACE_CUSTOM_CONTEXT
struct server_trace_row **server_trace_inline_slot(void);
#define server_trace_inline (*server_trace_inline_slot())
#else
extern __thread struct server_trace_row *server_trace_inline;
#endif
void server_trace_init(void);
/* Call once per worker before registration/activation; allocates and prefaults. */
void server_trace_thread_init(void);
void server_trace_fini(void);
uint64_t server_trace_clock(void);
uint64_t server_trace_stamp(struct server_trace_row *row);
uint64_t server_trace_stage(struct server_trace_row *row, enum lh_pmu_stage stage);
struct server_trace_row *server_trace_reserve(int worker_id);
void server_trace_discard(struct server_trace_row *row);
void server_trace_write(struct server_trace_row *row);
void server_trace_activation_event(unsigned event, int worker);
void server_trace_run_begin(struct server_trace_row *row);
void server_trace_run_end(struct server_trace_row *row);
void server_trace_event(struct server_trace_row *row, unsigned event,
                        uint64_t arg0, uint64_t arg1);
#else
#define LH_TRACE_PTR(value) NULL
struct server_trace_row;
#endif /* LH_ENABLE_TRACING */
#endif
