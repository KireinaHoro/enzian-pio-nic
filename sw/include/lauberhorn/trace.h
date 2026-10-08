/* Optional per-request latency annotations. No effect when CSV tracing is off. */
#ifndef LAUBERHORN_TRACE_H
#define LAUBERHORN_TRACE_H
#include <stdint.h>
#include <stdbool.h>
/* Call from an entry or deferred handler with the application's correlation ID.
 * This is not the wire XID or the opaque hardware request-context token. */
void lauberhorn_trace_request_id(uint64_t request_id);
/* Optional application correctness check, combined with reply/transport status. */
void lauberhorn_trace_result(bool ok);
/* Optional buffered event stream. IDs correlate application spans, not wire XIDs.
 * Export uses the same quiescent SIGUSR2 dump as request CSVs. */
enum lauberhorn_trace_event {
    LH_TRACE_ENTRY_RETURN = 1, LH_TRACE_FIBER_RESUME, LH_TRACE_FIBER_SUSPEND,
    LH_TRACE_CALL_SUBMIT, LH_TRACE_CALL_SUBMITTED, LH_TRACE_RX_PACKET,
    LH_TRACE_APP_ENTRY = 100, LH_TRACE_APP_RESOLVED,
    LH_TRACE_FUNCTION_PREPARE, LH_TRACE_FUNCTION_EXECUTE, LH_TRACE_FUNCTION_DONE,
    LH_TRACE_CHILD_BEGIN, LH_TRACE_CHILD_SENT,
    LH_TRACE_COLLECT_BEGIN, LH_TRACE_COLLECT_RESULT, LH_TRACE_COLLECT_END,
    LH_TRACE_COMPOSITION_DONE,
};
bool lauberhorn_trace_events_enabled(void);
uint64_t lauberhorn_trace_new_id(void);
void lauberhorn_trace_event(enum lauberhorn_trace_event event,
                           uint64_t arg0, uint64_t arg1);
#endif
