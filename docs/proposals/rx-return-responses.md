# RX-return response transmission

This is an unimplemented protocol proposal. The [measured coherence-path baseline](../research/hot-path-performance.md#measured-coherence-path-baseline--october-14)
supplies the latency observations used below.

Software could write a small response into the received RX cache line, then
read the opposite RX control line. That next receive doorbell would invalidate
the previous line, returning the response to the FPGA for transmission.

This does **not inherently remove the observed ~245 ns forward/ack exchange**
from MAC latency. Both separate TX and coupled RX/TX still require a CPU doorbell
request, FPGA invalidation, and CPU data return on the reply's critical path.
The clearer benefit is eliminating the separate TX completion and combining
response submission with posting the next receive. For the steady-state inline
case, two control reads and two reclaim exchanges per RPC can become one of
each, reducing coherence traffic and the inter-request gap. This is a protocol
counting hypothesis, not a measured throughput gain. Simpler descriptor handling
or earlier packetization may additionally improve latency; measure separately.

Before selecting an ABI or implementing this path, establish:

- Fields, payload capacity, byte validity and framing that distinguish an
  intentional response from unchanged contents, dirty replay and reloads.
- Ownership, message identity, exactly-once consumption, cancellation and
  preemption behavior, including partial strobes and speculative overflow lines.
- Independent return service: transmitting a response must not depend on the
  next request arriving or create a resource/coherence dependency cycle.
- Response-size distributions, large-response fallback, and application benefit
  across latency/tails, throughput, CPU work and coherence contention.

Schedule this with [application performance evaluation](../research/roadmap.md#application-performance-and-coupled-rxtx-analysis).
It is not a prerequisite for address/DESCHED work or a selected replacement for
independent TX. Preserve [coherence progress](../hardware/queue-pair-design.md#motivation-avoid-head-of-line-blocking).

The implemented [RX writeback/replay protocol](../architecture/hw-sw-interface.md#independent-rx-writeback-service)
provides independent return service today, but does not transmit responses.
The [directed regression](../development/validation.md#rx-writeback-regression-reproducer)
checks its completion ordering; it is not a full DCS/NIC/THX timing model.

