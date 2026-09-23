# TX completion status: extension uses and boundaries

Recorded September 17, 2026. This note extends the
[nested-RPC admission design](nested-rpc.md#outstanding-acceptance-and-resource-contract).
The B3 implementation is in progress; these potential extensions are not
implemented capabilities or board-validated behavior.

## Current facility and proposed extension

The B3 TX doorbell read returns a local result: accepted/buffer consumed (1),
rejected before nested-call reservation (2), or DMA error (3). For a payload
requiring DMA, successful consumption waits for DMA completion; this does not
establish downstream protocol success, physical transmission or peer receipt.
The current internal result is two bits. The host reads its value from the
returned control cache line.

The older generic proposal includes validity, a status code, protocol-specific
detail and an optional correlation cookie. Preserve room to extend the response
with operation-specific result fields, rather than treating the three current
codes as the final interface. No expanded field layout is selected here.
Changes must regenerate matching hardware/software collateral and retain stable
readback across voluntary cache-line eviction and reload. A response remains
valid until the host consumes it and reuses the line for the next descriptor.

## Potential consumers

| Consumer | Useful result | Evidence and required boundary |
| --- | --- | --- |
| Nested RPC admission | Accepted, table/queue full, invalid or unsupported request; optional cookie/reason | Explicit existing proposal in `nested-rpc.md`. Reservation must precede acceptance; definite rejection must release unsubmitted state. B3 implements a subset. |
| Reply-session lookup and software spill | Missing/evicted/software-owned session, reply identity and resend reason | The [spill design](../hardware/lookup-spill.md#hostreqsessionlookupmissruntime) proposes software resend or parking a reply. Returning a synchronous miss through the doorbell is a possible implementation, not its currently specified notification path. It requires feedback from reply lookup and retaining enough state/bytes to retry. |
| Immediate route/neighbor failure | Definite rejection and cleanup information | Explicit in the original nested-RPC status proposal. Distinguish a dropped request from one retained by neighbor-resolution fallback; software must not retry while another owner may still send it. Downstream feedback is required. |
| TCP transmit validation | Accepted/rejected, reason such as stale generation/epoch, wrong owner, invalid sequence range or excessive length | Flavian has an internal validation decision but no per-command host result. DMA consumption alone cannot report TCP acceptance; connect the validation outcome to the completion boundary. |
| TCP TCB installation and resynchronization | Per-command result, capacity/stale-token failure, optional resulting state/token | Flavian's report explicitly identifies missing per-command results. A command enqueue acknowledgement is insufficient; report the state-engine decision. |
| TCP recovery reinjection | Drain result or a per-connection progress watermark | Flavian explicitly identifies the missing drain record before `tcp_resync`. Establish what downstream consumption means; DMA completion alone may precede repaired-stream processing. Use asynchronous completion if progress can require further host work. |
| Table promotion/eviction | Accepted, stale generation, unauthorized operation or capacity failure | Potential use inferred from the [table-management proposal](../hardware/lookup-spill.md#hostreqtablepromote--hostreqtableevict). Applies if these operations use the host command path; it does not replace kernel/resource-manager authorization. |
| Future protobuf encoder | Encoding success/failure, encoded byte length, schema/output-capacity error and optional output location | Proposed use from this design discussion. Elena's current project only decodes protobuf. To report encoding results, completion must wait for the encoder, beyond today's DMA-consumed boundary. No encoder or status interface is implemented. |
| Retention of NIC-encoded TCP payload | Potential returned payload bytes, or a handle/length identifying retained bytes | Flavian's report leaves host retention, NIC-to-host copying and NIC storage unresolved. Reusing TX readback for this is a new possibility, not a recovered implementation or explicit doorbell proposal. Returning complete encoded bytes requires a separate bulk-data/lifetime design; a small status record does not solve it. |

## Keep completion meanings separate

Define the completion stage for each operation: local admission, source-buffer
consumption, encoding/validation, downstream drain, wire transmission and peer
acknowledgement are different events. In particular, TCP retransmission storage
must survive until peer acknowledgement even when the original TX staging
buffer can be reused after DMA.

Do not delay a doorbell indefinitely for peer ACKs, RPC responses, software
recovery or other work that may require the blocked worker to run. Those events
need asynchronous delivery. A synchronous encoder status is suitable only when
its completion can progress independently of the waiting host and its latency
fits the ECI critical-section/progress contract. Review the
[coherence notes](../hardware/coherence-progress.md) before extending the wait.

Elena's existing protobuf results arrive through RX descriptors; they are not
TX completions. Sven's proposed hardware-held continuation pointer likewise
returns with the eventual RPC reply, not the submission acknowledgement. Neither
currently requires this facility.

## Evidence for follow-on work

Sibling paths are research references, not build dependencies:

- `../flavian-tcp/platform/docs/ONCRPC-NESTED-RPC.md`, lines 260–308:
  explicit generic doorbell-status proposal, including detail/cookie and route
  failure. The local nested-RPC document retains its summary.
- `../flavian-tcp/report/sections/07-discussion.tex`, lines 48–60:
  missing per-command results and reinjection completion.
- `../flavian-tcp/platform/hw/src/lauberhorn/net/tcp/TcpEncoder.scala`,
  TX validation handshake: its Boolean decision currently stays inside hardware.
- `../flavian-tcp/report/sections/04-design.tex`, “Retaining Unacknowledged
  Data”: unresolved retention of bytes produced by NIC encoders.
- `../flavian-tcp/platform/docs/tcp/FUTURE-WORK.md`, “Rules the host must
  preserve”: buffer lifetime, reinjection drain and DMA completion versus peer ACK.
- `../elena-protobuf/report/main.tex`, “Future Work”, and
  [protobuf integration notes](../research/protobuf.md): receive-side decoder
  integration and result delivery, with no response encoder implementation.
- `../sven-dandelion/report/chapters/LauberhornRuntime.tex`,
  “Hardware-Managed Continuation State”: pointer returned with a matching reply.
