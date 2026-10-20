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

Schedule this after the independent RX/TX application baseline, as described in
the [paper evidence plan](../research/paper-goals.md#proposed-evidence-plan).
It is not a prerequisite for address/DESCHED work or a selected replacement for
independent TX. Preserve [coherence progress](../hardware/queue-pair-design.md#motivation-avoid-head-of-line-blocking).

The implemented [RX writeback/replay protocol](../architecture/hw-sw-interface.md#independent-rx-writeback-service)
provides independent return service today, but does not transmit responses.
The [directed regression](../development/validation.md#rx-writeback-regression-reproducer)
checks its completion ordering; it is not a full DCS/NIC/THX timing model.

## Evaluation and decision criteria

Put the RX return-half study in the application evaluation round, after the
correct independent RX/TX baseline is running. Start with RPC workloads, then the active application portfolio in the
[paper evidence plan](../research/paper-goals.md). The
[hot-path optimization note](../research/hot-path-performance.md#hardware-reuse-the-rx-return-half-for-responses) describes possible
commands/status and responses carried by intentionally dirtying an RX CL.
This is an experiment to establish benefit, not a prerequisite for the address
or DESCHED work and not a selected replacement protocol.

- Investigate [software instructions and cache-line layout](../research/hot-path-performance.md#software-and-cache-line-layout-questions)
  alongside this study, using the current contiguous borrowed-RX/replay layout
  and ownership contract. Separate copies, codecs, allocation and runtime work;
  deferred arguments/results still need owned storage.
- First measure response-size distributions, the fraction fitting in the usable
  return payload, handler time, and the share of latency/CPU work spent on TX.
- Compare the independent path with a coupled prototype on the same hardware,
  application semantics and offered loads: median/tail RPC latency, throughput,
  CPU work, coherence traffic and resource/backpressure effects. Include mixed
  small/large responses and fallback to the independent TX path.
- Before promoting the prototype, check message identity, validity, exactly-once
  consumption, preemption/kill cleanup and independent write service under stalls.
  A response depending on the next held RX must not reintroduce a service cycle.
- Keep or reject the optimization based on measured application benefit against
  implementation and protocol cost; retain artifacts and negative results too.
