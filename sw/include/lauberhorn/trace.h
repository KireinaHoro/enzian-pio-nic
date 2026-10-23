/* Optional per-request annotations. LH_ENABLE_TRACING=0 removes hooks and
 * argument evaluation; the runtime and its applications must use the same flag. */
#ifndef LAUBERHORN_TRACE_H
#define LAUBERHORN_TRACE_H
#include <stdint.h>
#include <stdbool.h>
#ifndef LH_ENABLE_TRACING
#define LH_ENABLE_TRACING 1
#endif
/* Call from an entry or deferred handler with the application's correlation ID.
 * This is not the wire XID or the opaque hardware request-context token. */
#if LH_ENABLE_TRACING
void lauberhorn_trace_request_id(uint64_t request_id);
/* Optional application correctness check, combined with reply/transport status. */
void lauberhorn_trace_result(bool ok);
#else
#define lauberhorn_trace_request_id(...) ((void)0)
#define lauberhorn_trace_result(...) ((void)0)
#endif
/* Optional buffered event stream. IDs correlate application spans, not wire XIDs.
 * Export uses the same quiescent SIGUSR2 dump as request CSVs. */
enum lauberhorn_trace_event {
    LH_TRACE_ENTRY_RETURN = 1, LH_TRACE_FIBER_RESUME, LH_TRACE_FIBER_SUSPEND,
    LH_TRACE_CALL_SUBMIT, LH_TRACE_CALL_SUBMITTED, LH_TRACE_RX_PACKET,
    LH_TRACE_DESCHED = 20, LH_TRACE_YIELD_ENTER,
    LH_TRACE_APP_ENTRY = 100, LH_TRACE_APP_RESOLVED,
    LH_TRACE_FUNCTION_PREPARE, LH_TRACE_FUNCTION_EXECUTE, LH_TRACE_FUNCTION_DONE,
    LH_TRACE_CHILD_BEGIN, LH_TRACE_CHILD_SENT,
    LH_TRACE_COLLECT_BEGIN, LH_TRACE_COLLECT_RESULT, LH_TRACE_COLLECT_END,
    LH_TRACE_COMPOSITION_DONE,
    LH_TRACE_CHILD_RETRY,
    LH_TRACE_UDP_TX_BEGIN = 200, LH_TRACE_UDP_TX_END,
    LH_TRACE_UDP_TX_DOORBELL, /* arg0: CPU timestamp before commit read; arg1: reserved (zero) */
    LH_TRACE_UDP_TX_PREPARE, /* application: length and protocol framing before reservation/copy */
};
#if LH_ENABLE_TRACING
bool lauberhorn_trace_events_enabled(void);
uint64_t lauberhorn_trace_new_id(void);
void lauberhorn_trace_event(enum lauberhorn_trace_event event,
                           uint64_t arg0, uint64_t arg1);
#else
#define lauberhorn_trace_events_enabled() false
#define lauberhorn_trace_new_id() UINT64_C(0)
#define lauberhorn_trace_event(...) ((void)0)
#endif
#endif
