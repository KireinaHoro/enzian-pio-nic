# Request-context metadata and transport extensions

These are open proposals. No application metadata-query API or TCP reply-context
variant is implemented. A future API
could accept the live request-context token and return peer IP address/port,
local endpoint, original XID, transport kind, or a connection identifier. It
must check that the request is still live and define the query/completion race;
a pointer into worker-local RX storage is insufficient across fiber suspension.
The existing privileged debug readback is not such an application API.

If TCP is integrated, extend context metadata with a transport tag selecting
UDP routing fields or a TCP connection-table reference (and any framing state
needed for that response). TCP connection lifetime and retransmission storage
are independent of per-request reply-context retirement. Handing a response to
the connection transmit machinery releases the request entry when the transport
owns all required metadata/bytes; it does not close the connection or wait for
peer acknowledgement. No TCP variant or expanded field layout is implemented.

A transport peer is not a persistent logical client. Applications needing
identity across reconnects or UDP port changes can encode their own client ID.
Sven's report describes XID bits selecting pending-call slots and mock processes;
those are caller-side completion mechanisms, not server application identity.
The benchmark client's XID tags likewise work as long as replies echo the XID.

See the implemented [reply-context lifecycle](../software/reply-contexts.md) and
[protocol roadmap](grpc.md).
