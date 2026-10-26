# Hardware tracing and latency instrumentation

This page defines Lauberhorn-specific FPGA measurement boundaries and correlation.
Use the [shared hot-path methodology](../research/hot-measurement.md) for comparison
with CPU-NIC baselines and the [capture guide](../development/tracing.md) for
recording, preservation, export and readout. The generated map from the exact
bitstream defines source/event IDs, payloads, clocks and pipeline corrections.

## Hardware Collection

### TracePlugin

`TracePlugin` lives in `hw/src/lauberhorn/TracePlugin.scala` and is used by
SpinalHDL plugins inside `NicEngine`.

Its role is to collect Lauberhorn event trace declarations while the NIC engine
elaborates:

- `makePort(sourceSlr)` creates one Lauberhorn event source port.
- `trace(name, TraceData...)` allocates or reuses an event ID and returns a
  `Bool` trigger.  The caller drives that trigger when the event fires.
- Event IDs are deduplicated by event signature: event name plus the ordered list
  of trace-data key names.  Multiple trace ports can call
  `trace("RxCoreCommit", CoreID(...))` and get the same event ID.
- A duplicate event name with a different trace-data layout is rejected during
  elaboration.
- Trace data keys, such as `CoreID` and `PacketDropped`, define only a name and
  bit width.  The emitted payload packs the event ID in the low bits, followed by
  the event's declared trace data in order.

`TracePlugin` also records each port's source SLR. The trace transport uses
that declaration to choose an auto-pipeline insertion limit of five registers
per SLR crossing toward the SLR2 collector. This limit is not a measured latency.
Event payload selection uses a balanced one-hot OR; events sharing a source
must remain mutually exclusive, as checked by the generated assertions.

### LauberhornTraceDma

`LauberhornTraceDma` lives in
`hw/src/lauberhorn/LauberhornTraceDma.scala`.  It is generated as a separate RTL
module, `lauberhorn_trace_dma`, after `NicEngine` has finished elaborating.

Its role is to adapt all trace sources to `TraceBufferDMA`:

- DCS event trace sources from the even and odd directory-controller slices.
- ECI app-clock trace sources after DCS/app clock crossing.
- ECI sys-clock trace sources before app clock crossing, plus GSYNC and
  interrupt-controller ECI channels.
- ECI sys-clock trace sources at the post-gateway dynamic/static boundary.
- Returned-credit pulses from the static shell back into the dynamic gateway.
- Lauberhorn event trace sources produced by `TracePlugin`.

It assigns source IDs and inserts `TraceAutoPipeline` source/sink register banks
before `TraceBufferDMA`. Payload and valid share an auto-pipeline group. Vivado
may insert additional registers within the declared limit; source banks remain
distributed and receiving banks belong to SLR2. The generated map is unresolved
until build Tcl walks the final routed register chains. That extraction requires
equal payload/valid depths and records actual `pipeline_stages`, per-bit evidence
and artifact hashes. Decoding rejects unresolved maps.

`LauberhornTraceDma` also writes `lauberhorn_trace_dma_map.json`.  This map is
the contract between hardware and software.  It contains:

- sample layout: payload width, source width, timestamp width, sample width,
  lost-source ID, and AXI data width;
- source metadata: source ID, port, type, clock domain, DCS/channel/local-source
  information, and `pipeline_stages`;
- payload metadata used by tooling: DCS fields, ECI fields, returned-credit
  fields, Lauberhorn event names, trace-data key widths, and per-event
  trace-data key lists.

For Lauberhorn events, the map deliberately separates definitions from use:

```json
"trace_data_keys": {
  "CoreID": { "width": 3 },
  "PacketDropped": { "width": 1 }
},
"event_data": {
  "7": ["CoreID"],
  "12": ["PacketDropped"]
}
```

Events with no trace data are omitted from `event_data`.

### ECI Message Trace Points

ECI message tracing deliberately records the same logical traffic at multiple
points.  The goal is to distinguish "the directory controller produced this
message" from "the message crossed the last dynamic/static boundary toward the
static shell and CPU".

Current fixed ECI-related source allocation is:

- sources `4..15`: app-clock DCS ECI channels, after the DCS/sys-to-app clock
  crossing;
- sources `16..27`: sys-clock DCS ECI channels, before the app-clock crossing;
- sources `28..31`: sys-clock GSYNC request/response channels;
- sources `32..33`: sys-clock interrupt-controller VC12 request/response
  channels;
- sources `34..37`: post-gateway dynamic/static boundary channels
  `link{1,2}_out_{lo,hi}`;
- sources `38..39`: post-gateway returned-credit vectors
  `link{1,2}_out_credit_return`.

The generated `lauberhorn_trace_dma_map.json` is the authoritative source list;
the numbers above document the current layout to make trace inspection easier.

For ECI sources, the payload contains the 64-bit header, VC number, `accepted`
bit, and a 3-bit ECI channel `size`.  Hardware emits a stalled sample with
`accepted = 0` when `valid && !ready`, and an accepted sample with
`accepted = 1` when `valid && ready`.

The meaning of `accepted = 1` depends on the trace point:

- App-clock DCS ECI sources prove that the frame crossed into the app-clock DCS
  side and was consumed there.
- Sys-clock DCS, GSYNC, and interrupt-controller sources prove acceptance by
  the local sys-clock stream endpoint being traced.  They do not prove that the
  frame has left the dynamic gateway toward the static shell.
- Post-gateway boundary sources prove that the corresponding top-level
  `link*_out_{lo,hi}` stream handshaked at the dynamic/static boundary.  This is
  after the dynamic gateway's VC-level credit tracking, so the frame has made it
  past the dynamic-side point where VC credits can still withhold it.

DCS event sources are generated from the DCU debug interface.  A stalled input
request is traced as a bounded burst of up to 16 events for each continuous
stall, then suppressed until the stall resolves.  This keeps deadlock captures
from being overwritten by a single stuck DCU debug condition while still showing
that the stall persisted.  The DCS event `error_code` field carries the DCU
error code, with `ERR_REQ_STALLED` used for these request-stall trace events.
The original single-bit DCS `error` field remains in bit 0 for compatibility
with old captures and old decoders; `error_code` follows the DCS `request`
field in payload bits `[60:57]`.

The boundary trace point is therefore the right place to answer "did an FPGA
ECI message really leave toward the CPU?"  It still is not a CPU-retirement
trace: after this point the traffic is in static-shell/TLK/link-layer logic, and
the trace does not observe the ThunderX L2C accepting or retiring the request.
Under the normal assumption that the lower-level link remains healthy, however,
an accepted boundary sample is the dynamic-design evidence that the frame was
sent far enough that it should be visible to the CPU side.

The boundary channels carry link blocks rather than the original higher-level
DCS channel identity.  The trace records the VC and the first 64-bit header word:
`link*_out_lo_data` for the low path and `link*_out_hi_data(63 downto 0)` for
the high path.  The high path also records `link*_out_hi_size`, which lets the
software-side credit model charge multiword high-channel sends correctly.  The
trace is still not a full multiword payload capture.

Returned-credit sources record nonzero `link*_out_credit_return(12 downto 2)`
vectors in payload bits `[10:0]`.  These sources are not ECI messages; they are
there to correlate boundary sends with VC credit return behavior when checking
whether a frame could be stuck behind dynamic-gateway credit accounting.

The Wireshark dissector reconstructs the dynamic gateway's TLK transmit-credit
state from the boundary ECI samples and returned-credit samples.  The model
follows `tlk_credits.vhd`: returned-credit bits add eight credits to VC2..VC12,
accepted boundary sends subtract credits according to the selected link/path/VC,
and high-path sends use the traced ECI channel `size`.  The model is only as
complete as the exported trace window; include sources `34..39` when using it.
If a pcapng window starts after the beginning of the trace, reconstructed
credits can be higher than the real hardware count because earlier sends are
missing.  The `eci-state` CSV exporter therefore requires a full, unwrapped,
lossless trace before adding TX boundary-acceptance latency and remaining-credit
columns to the `addr_*.csv` output.

When debugging a suspected missing FPGA-to-CPU message, use the trace points as
a narrowing ladder:

1. If the frame appears at the relevant sys-clock DCS/GSYNC/interrupt source but
   not at sources `34..37`, it was still inside the dynamic gateway/static
   boundary path.
2. If the frame appears at sources `34..37` with `accepted = 1`, then the
   dynamic gateway and VC-level credit tracking accepted the send.  Look past
   the dynamic boundary unless credit-return behavior suggests a broader
   static-shell accounting issue.
3. Use sources `38..39` to check whether credits for that link/VC continue to
   return around the suspect interval.

### TraceBufferDMA

`TraceBufferDMA` is the generic sample recorder used by
`LauberhornTraceDma`.  It owns the outer trace-sample envelope:

- payload bits;
- source ID;
- hardware timestamp;
- marker/lost samples using the reserved all-ones source ID;
- AXI writes into the circular trace buffer in trace DDR.

TraceBufferDMA does not know DCS, ECI, or Lauberhorn event semantics.  It records
fixed-width samples and marker records.  In the current ECI build samples are
128 bits, so four samples pack into each 512-bit AXI beat.

## Floorplanning And Pipeline Latency

The ECI floorplan constrains only the inner `TraceBufferDMA` instance to the
trace-DDR SLR.  The surrounding `LauberhornTraceDma` wrapper and its input
pipeline registers are intentionally allowed to cross SLRs.

Pipeline latency is part of the trace metadata.  The hardware inserts the delay
before `TraceBufferDMA`, then Python subtracts the recorded `pipeline_stages`
from each sample timestamp before sorting/exporting.  The raw hardware timestamp
is still preserved in the pcapng envelope.

The current rule of thumb is five stages per SLR crossing to the trace DMA SLR.

## MAC and recorder boundaries

`MacInterface.scala` emits CMAC/CDC markers at `lastFire`, the end of the frame.
`RxCmacEntry` and `TxCmacExit` cross `PulseCCByToggle` into the application clock.
Source pipeline correction does not remove phase-dependent synchronizer delay
or PHY serialization. Keep packet sizes and clocks fixed; these markers do not
establish first-bit wire latency or exact transceiver delay.

## Timestamp capture point and legacy queue bias

Trace selection groups sources into banks of eight and uses Spinal
`StagePipeline` to register local candidates before selecting the final bank.
These stages are after the ingress timestamp and therefore require no additional
source timestamp correction. Backpressure preserves event order and payload.

Current `TraceBufferDMA` captures the timestamp together with each input batch
before the batch queue and carries it through serialization. The generated map
declares `sample.timestamp_point = "trace_dma_input_before_batch_queue"`.
Maps without that field describe legacy dequeue timestamping: FIFO residence
and serialization/backpressure can then add variable timestamp delay even when
no sample is lost. The sample bit layout is unchanged, so map/provenance must
distinguish the two contracts. The regression checks ingress-cycle spacing under
multi-source serialization. Historical affected measurements are described in
[the recorder limitation and evidence](../applications/memcached.md#joined-rtt-measurements-and-recorder-limitation).

System-domain ECI sources still cross an untimestamped asynchronous FIFO before
reaching the trace recorder. Fixed pipeline-delay subtraction does not bound
variable CDC residence. Report CPU/FPGA fit uncertainty as conditional on that
path and reject inconsistent fits; do not widen the acceptance threshold merely
to obtain a breakdown. App-domain NIC/MAC events with the corrected recorder
avoid the global batch-queue bias. Use a matching recorder/map for new
measurements; historical fine hardware intervals from dequeue-timestamped
recorders retain that limitation until reassessed.
CPU-only intervals and client RTTs are unaffected by the recorder correction.


## Event selection and bounded correlation

RX follows CMAC/CDC, Ethernet/IP/UDP/RPC decode, host enqueue, scheduler queue /
dispatch and `EciRxDescSent`. That descriptor marker is an internal handoff,
not the DCS response leaving for the CPU. RX invalidation/unlock events belong
to a later opposite-parity read and must not be used as the current delivery.

TX follows `EciTxCommitRead`, control or overflow invalidation completion,
`EciTxSubmit`, host acceptance, DMA/no-DMA markers, RPC/UDP/IP/Ethernet encode,
CDC and CMAC. The decoder supports legacy completion names
`EciTxCtrlUnlocked` / `EciTxDataLciaUlDone` and current
`EciTxCtrlInvalidated` / `EciTxDataLciaDone`. Completion must lie between this
commit and submission. DMA completion can overlap encoding; its milestones are
not independent additive stages. `EciTxAcquire` is a parity-protocol event,
not necessarily preparation of this response.

RX/TX HostMsgIDs are independent six-bit wrapping counters. Match by worker,
order and bounded lifetime, not equality alone. Descriptor delivery and every
later TX stage are bounded by the next dispatch on that worker, including other
domains. TX submission needs a unique candidate within delivery/next-dispatch
and CPU bounds; never choose the nearest ambiguous candidate. Queue/wakeup
attribution must form an ordered chain and partition queue residence only once.

`OncRpcCallDecoder` consumes metadata and can occur after sibling
`RxRpcEnqueueToHost`. The legacy analyzer requires one matching decoder event
within ±1,000 ns of enqueue; untagged CMAC/CDC markers must also be unique nearby.
`--combine-rpc-ingress` reports UDP decode through host enqueue as one stage;
the signed internal metadata difference is diagnostic, not another duration.
This is an isolated foreground-request heuristic, not general concurrent identity.

For historical captures with sentinel descriptor ID 63,
`--rx-trace-invalid-host-id 63` permits only a unique same-core descriptor between
current and next dispatch. Each recovery is recorded. It neither repairs the
capture nor establishes concurrent correlation. Warmups stay in alignment and
are excluded explicitly afterwards; missing measured requests fail analysis.

Use one outstanding foreground request and an isolated service for the legacy
sequence correlator, with flush traffic fully drained between measured calls.
`--trace-process-id` selects the scheduler ProcessID while other-domain dispatches
still bound joins. In the recorded driver this is Linux TGID, distinct from the
PA lifetime code. Record both identities and reject filters with no dispatches.
Raw loss markers reject sequence correlation. `--allow-partial-trace` is diagnostic.

## Clock alignment

CPU raw-monotonic and FPGA clocks do not share an epoch. The legacy fit used the
lower envelope of CPU RX-unblock minus internal descriptor delivery minus half
ECI RTT; older rows used CPU RX-return minus descriptor delivery. These fits
mistake internal delivery for the DCS response and can place CPU TX return before
the FPGA response. Do not interpret their cross-clock components as calibrated
one-way latency. Client RTT and CPU-only intervals are independent of this fit;
FPGA-only intervals retain their recorder limitations.

`--transport-anchors` uses [transport_anchors.py](../../tools/enzian/transport_anchors.py)
to join DCS RX `PEMD`, TX `RLDD` and TX `PEMD` by core, domain, address/parity
and bounded time. Joins must be unique and input hashes must match. The seed audit
supplies identities, never its fitted epoch. Alignment uses the lower envelope:

```text
CPU_RX_unblock − DCS_RX_response − half_observed_forward_ack_RTT
```

The half-RTT term assumes symmetric transit. Bound the constant epoch by
CPU-before-request and response-before-CPU causality across every request;
inconsistent fits fail rather than clipping negative intervals. Report the full
causal range and recorder/CDC limitations, not merely the selected point estimate.
For example, the October 10 kernel-off hot fit allowed −137.5 to +122.5 ns around
its selected offset; median probe-to-DCS arrival could be 10–270 ns without the
symmetry assumption. These are conditional systematic bounds, not confidence
intervals. Historical 200 MHz traces use nominal 5 ns quantization.

Partition the matched MAC interval once, retaining gaps as `lh_unattributed`.
Detailed components plus this gap must equal MAC duration; the external residual
is outside that interval. FPGA queue residence can include host reactivation,
not just scheduler execution. Kernel diagnostics refine these intervals rather
than adding to them. See [runtime probe boundaries](../development/runtime-tracing.md#probe-boundaries).

## Analysis commands

Run from the repository root with matching CSVs, full trace and map:

```sh
python3 data/eci/apps/plot_latency.py CLIENT.csv SERVER.csv \
  --worker-cpu-base 44 --trace-dump TRACE.bin --trace-map MATCHING_MAP.json \
  --audit-json NEW-audit.json --no-plots
python3 tools/enzian/benchmark_summary.py CLIENT.csv \
  --audit NEW-audit.json --baseline-audit OLD-audit.json --output NEW-summary.json
```

The analyzer defaults to `--trace-cycle-ns 5`; use the capture's actual period.
Retain input hashes, revisions, request counts, exclusions, identity mappings,
correlation warnings and the audit's per-request conservation checks. Offline
analysis does not establish a new board measurement.

## Native UDP correlation fields

The RX UDP control descriptor exposes `trace_ref`: bit 15 is valid, bits 5:0
are the existing RX `HostMsgID`, and other bits are zero. It occupies the aligned
16-bit field at byte 20, padded to a 32-byte Mackerel datatype. RX enqueue
tracing already links that ID to ingress `PacketID`. TX reuses the aligned
16-bit reserved field at byte 14 for `related_rx`. Software echoes the RX
reference on every response segment; zero means unrelated. `EciTxSubmit` emits
`RelatedRxTrace` together with the existing TX `HostMsgID` and core. This is
untrusted diagnostic metadata only: no effect on admission, destination,
ownership, DESCHED, packet retention or wire bytes. All 64 ID values are usable;
validity is independent of the all-ones ID. Copying the scalar after RX release
is permitted; copying or retaining the borrowed packet pointer is not.

Payload layout version 3 headers declare
`ECI_UDP_TRACE_VERSION=1`, required by the updated core runtime. Regenerate
Mackerel/config/trace-map collateral and deploy matching hardware/software;
historical 518689 hardware does not supply this metadata.

The [memcached analyzer](../applications/memcached.md#request-tracing-and-latency-analysis)
checks reference lifetimes and joins responses to application/client records.
