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
#endif
