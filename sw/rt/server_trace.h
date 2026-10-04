#ifndef LAUBERHORN_SERVER_TRACE_H
#define LAUBERHORN_SERVER_TRACE_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#define SERVER_TRACE_TIMES(X) \
    X(rx_enter) X(rx_unblock) X(rx_exit) \
    X(unmarshal_enter) X(unmarshal_exit) X(handler_enter) X(handler_exit) \
    X(marshal_enter) X(marshal_exit) X(tx_enter) X(tx_exit)
struct server_activation_trace {
    uint64_t ioctl_exit_ns, ready_ns, index;
    int tid, worker;
};
extern __thread struct server_activation_trace server_activation;
struct server_trace_row {
    struct server_activation_trace activation;
    bool enabled, id_valid, ok;
    uint64_t request_id;
    uint32_t request_context, request_bytes, response_bytes;
    int worker_id, tx_worker_id, rx_cpu_id, tx_cpu_id, status, transport_status;
    unsigned kind, timestamp_calls; /* kind bits: deferred=1, forwarded=2 */
#define TRACE_FIELD(name) uint64_t name##_ns;
    SERVER_TRACE_TIMES(TRACE_FIELD)
#undef TRACE_FIELD
    uint64_t defer_enqueue_ns, forward_submit_ns, forward_complete_ns;
    uint64_t handler_active_ns, run_start_ns;
};
extern bool server_trace_enabled;
extern __thread struct server_trace_row *server_trace_inline;
void server_trace_init(void);
void server_trace_fini(void);
uint64_t server_trace_clock(void);
uint64_t server_trace_stamp(struct server_trace_row *row);
void server_trace_rx_begin(struct server_trace_row *row, int worker_id);
void server_trace_write(struct server_trace_row *row);
void server_trace_run_begin(struct server_trace_row *row);
void server_trace_run_end(struct server_trace_row *row);
#endif
