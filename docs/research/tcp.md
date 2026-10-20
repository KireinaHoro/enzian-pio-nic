# TCP student project

The external implementation in `../flavian-tcp/` provides partial TCP offload;
it is not integrated into this platform. Its source and evaluation are described
in Flavian Kaufmann's *Reliable transport for the Lauberhorn smart NIC*, bachelor
thesis 607b (2026). The evidence below refers to platform `9bedb136` and report
`e39bf00`; routed-build metadata instead identifies `46bc64784c484fc0`.

Integration has lower priority than active first-paper applications. The
[gRPC proposal](../proposals/grpc.md) records the shared integration direction
and additional protocol layers required beyond TCP and Protobuf.

## Architecture and host contract

- Feature-gated IPv4 TCP parser/checksum, encoder/checksum, serialized state engine and 32-entry four-tuple connection table. One passive listener; host submits SYN-ACK or installs a prepared TCB. Hardware protocol states are `SYN_RECEIVED` and `ESTABLISHED`; a connection's worker assignment is fixed. Common SYN option framing is accepted, but only peer MSS is retained; established options fall back.
- Established in-order RX advances `rcvNxt`, emits an immediate cumulative ACK, and offers ordered payload to a worker or optional hardware consumer. The post-TCP ONC-RPC path implements record framing across split/coalesced segments, service dispatch and replies in simulation. Metadata, payload-through-`last`, and ordered claim tokens must remain paired under backpressure.
- TX remains host-assisted. Hardware validates worker, generation, ownership epoch, state, sequence range, transfer kind and MSS before encoding. New data advances `sndNxt`; retransmissions name already outstanding bytes. This does not implement congestion control or retransmission scheduling.
- Ownership states are `NIC_FAST`, `PAUSED`, `HOST_OWNED`. Out-of-order payload pauses the connection and advances its epoch; subsequent paused payload also goes to the host recovery path. Only `outOfOrderPayload` and `pausedPayload` are recoverable. The host reorders/trims bytes, reinjects the contiguous stream, drains it, then issues token-matching `tcp_resync`. FIN/unsupported established traffic hands ownership to software without a hardware return path; RST invalidates the TCB.
- Generation protects table-slot lifetime; ownership epoch protects handoffs within that lifetime (both 16 bits). This replaces the report design chapter's proposed single bypass token. Reinjection itself trusts host metadata; matching `tcp_resync` tokens alone do not prove correct stream recovery.
- TCP ECI descriptors use a four-bit request type and add payload, exception, ACK-observation, TX, resync, install and reinjection variants. RX envelopes include `valid`; TX envelopes do not. Packet header fields and TCB arithmetic fields have different byte-order conventions: read the sibling ABI before writing codecs.

The Linux module, `sw/core` and userspace runtime **do not implement the TCP ABI**. `hw/test/src/lauberhorn/sim/tcp/TcpSoftwareOwnerModel.scala` is executable test infrastructure demonstrating recovery, a TX ledger and simple timers. It is not the production stack. Intended split: kernel owns privileged transport/mappings and fallback routing; `sw/core` owns reusable TCP policy and ordering; runtime exposes ordered streams/application errors. Exceptions are not normal application data. RX buffers must be consumed before release; TX/reinjection buffers remain stable through DMA completion, which is distinct from peer acknowledgment.

## Evidence and limits

The report records 200 passing registered cases: 193 non-TAP tests plus seven TAP tests. Coverage includes component/ABI tests, full `NicEngine` TCP simulation, ownership recovery, stale-token rejection, ONC-RPC records and PCAP replay. TAP couples an unmodified Linux peer to simulated RTL and exercises retransmission, injected delay and loss. These are historical, scenario-based results with a model host; no TCP traffic ran on the FPGA and no end-to-end application service was evaluated.

Reported RX worker-descriptor latency is 43/68 cycles for 32/536-byte payloads; TX first output beat is 36/44 cycles. At the intended 200 MHz these correspond to 215/340 ns and 180/220 ns, **conditional cycle conversions**, not achieved hardware latency or complete RPC latency. Short single-worker throughput tests are admission/submission measurements, not line-rate guarantees. Routed TCP adds 46,821 LUTs (11.6%) and 56,057 registers (10.8%), with unchanged BRAM/DSP counts. Both builds miss timing: overall WNS off/on is −1.011/−1.206 ns; application-clock slack is −0.150/−0.513 ns.

## Integration work and unresolved design

Before selecting a production host ABI, resolve these design gaps:

1. Enforce advertised receive-window admission and unify advertised windows. Currently even zero-window in-order payload can advance receive state. Report piggybacked ACK/window progress to the software owner even when a hardware consumer claims the payload.
2. Guard indexed invalidate/window-update maintenance against slot reuse. Define per-command results, reinjection drain completion and capacity-exhaustion responses; aggregate counters cannot establish one command's outcome. Validate or explicitly constrain reinjection tuple/tokens/sequence ordering and the resync receive frontier.
3. Reconcile descriptor types, generated Mackerel/C headers and ECI routing with the current [HW/SW interface](../architecture/hw-sw-interface.md), [borrowed-buffer APIs](../software/programming-model.md) and [scheduling contract](../software/scheduling-control-interface.md). The same four-bit type width does not establish ABI compatibility. Add ABI version/capability rules and an independently implemented codec check. Establish isolation for privileged TCP-owner commands before claiming security guarantees.
4. Build the real software owner: receive reordering/window policy, active open, retransmission/RTT/congestion policy, close/half-close/TIME-WAIT, tuple reuse and errors. The single-connection model lacks an RTT estimator, timeout backoff, adaptive congestion control, fast retransmit and a SACK scoreboard. Negotiation, fixed listener ISN/initial window and table/packet exhaustion also need production behavior.
5. Decide retention of unacknowledged **NIC-encoded** reply bytes. The report design discusses host regeneration, NIC-to-host copying and bounded NIC storage without selecting one; current retransmission descriptors source host TX-buffer bytes. This remains a real design question for reliable fully offloaded replies.
6. Close timing or revise the clock target; validate real ECI operation, multi-connection pressure, recovery failures and end-to-end applications before extending claims to hardware or gRPC. TCP record dispatch does not provide HTTP/2, gRPC framing, protobuf integration or TLS.

## Source and validation map

All paths below are relative to `../flavian-tcp/`.

| Question | Entry points |
| --- | --- |
| Report design versus implementation; limitations | `report/report.tex`; `report/sections/{04-design,05-implementation,06-evaluation,07-discussion,08-conclusion}.tex` |
| Hardware architecture and ABI details | `platform/docs/tcp/{ARCHITECTURE,ECI-ABI,FUTURE-WORK}.md` |
| TCP parser/state/table/encoder and policies | `platform/hw/src/lauberhorn/net/tcp/` |
| Feature wiring and post-TCP records | `platform/hw/src/lauberhorn/GenEngineVerilog.scala`; `platform/hw/src/lauberhorn/net/oncrpc/OncRpcTcpRecord.scala` |
| ABI and DMA routing | `platform/hw/src/lauberhorn/host/package.scala`; `platform/hw/src/lauberhorn/host/eci/{EciTcpHostCtrl,TcpDmaHostCommandRouter}.scala` |
| Engine/ABI/TAP tests; owner model | `platform/hw/test/src/lauberhorn/host/eci/tcp/`; `platform/hw/test/src/lauberhorn/sim/tcp/` |
| Historical measurements and routed reports | `platform/data/evaluation/tcp/`; `platform/data/eci/tcp/tcp_handshake_payload.pcap` |
| Commands and build flags | `platform/Makefile`, `platform/build.mill`, `platform/scripts/tcp-tap-docker.sh` |

In that sibling platform's development environment, `make test-tcp` runs unit/model and engine suites; `make test-tcp-host` isolates the owner model; `make test-tcp-tap-docker` runs the privileged Linux interop suite. `mill eciTcpOn.generateVerilog` and `mill eciTcpOn.generateEvaluationReports` select TCP; `eciTcpOff` supplies the comparison. The sibling build file exposes these targets; ordinary `eci` remains TCP-disabled. These are sibling-specific commands, not platform build targets. Use the [platform validation workflow](../development/validation.md) when integrating the port.
