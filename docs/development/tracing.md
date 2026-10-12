# Lauberhorn Tracing

Detailed reference, source-reviewed on 2026-09-08. Start with [validation](validation.md)
for test selection or the [trace tools README](../../data/eci/sys_trace/README.md)
for exporter commands. Commands below run from the repository root. Hardware defaults
are deployment-specific; preserve the generated trace map alongside each capture.

The F_MAC1 comparison image `5cda901` passed a matched board test with 300 RPCs
and three identical full 322,322,624-byte UDP trace reads without retransmission.
See the [board evidence and limits](cmac-trace-diagnosis.md#f_mac1-board-validation-september-28).
This is a single-boot validation; routed timing remains open.

## Default agent capture pipeline

Use the dedicated **F_MAC1 CMAC/UDP path by default** for full trace captures,
including `probe_versions` failure preservation. JTAG remains the fallback when
Ethernet is unavailable and an independent content spot-check mechanism. A
working Linux driver on the DUT is not required for the hardware trace responder.
Do not reset the board to fix the capture link before preserving failure evidence.

1. Check board/peer reservations and record the programmed revision, bitstream,
   LTX, software and generated trace map. Keep tracing active through module load
   and the workload; arm the static ECI edge ILA before boot when investigating
   version-register failures. Do not substitute artifacts from current master
   for those belonging to the image actually programmed.
2. Record pre-freeze VIO loss/error status, assert `trace_stop`, and wait for a
   stable `writeSlot`. Save the pointer, wrap flag, DMA error and sticky loss flag.
   A loss bit first asserted after freeze does not prove pre-freeze loss.
3. Use a reserved peer on the FPGA VLAN. For the tested zuestoll14 deployment:
   trace F_MAC1 is `192.168.129.204`, MAC `0c:53:31:03:01:cc`, UDP 55555,
   leaf1 Ethernet100 / front-panel 26/0 at 100G with RS-FEC. The capture peer
   zuestoll12 uses `cpu40g1`, `192.168.129.129`, MAC `0c:53:31:03:01:81`.
   Main RPC F_MAC0 remains `.200` / `0c:53:31:03:01:c8`. These are board-specific
   defaults, not addresses to reuse blindly on another board. Check routing and
   actual link/FEC counters; keep management/storage on `cpu40g0`.
4. Save VIO outputs before any temporary override. Configure the capture server
   IP and next-hop MAC if required; the FPGA does not ARP for its next hop. Start
   the receiver on the peer **before** toggling `trace_dump_over_network` 0→1:

   ```sh
   python3 data/eci/sys_trace/dump_trace_udp.py \
     --bind-ip 192.168.129.129 --wait-timeout 90 \
     --timeout 1 --retries 5 --chunk-bytes 1408 --out NEW_RUN/trace-dram.bin
   ```

   Create `NEW_RUN` first. For an unwrapped buffer, omit `--bytes` to capture
   the entire valid range. For a wrapped buffer, supply its actual configured
   capacity and preserve the write pointer for chronological reconstruction.
   Do not present a prefix or the example's historical fixed-size read as a
   complete capture. The receiver's metadata wait and data retries are bounded;
   preserve partial output and diagnostics on failure, then fall back to JTAG.
5. Verify exit status and exact expected byte count (`writeSlot * 64` when
   unwrapped), save a SHA-256 hash, and inspect trace loss markers with the
   matching map. Sequence/length checks do not supply payload integrity; use
   JTAG head/tail checks or a repeated frozen-buffer read when validating a new
   image/link. The CLI does not report individual retransmissions: instrument
   or record packets if a retry count is needed. Separate recorder socket drops
   from missing UDP responses. RAM-backed packet recording avoided local drops
   in the board test; raw packet capture requires root, ordinary UDP does not.
6. Save a compact summary and retain captures, map, provenance, VIO status and
   link counters under an ignored output directory. For `probe_versions`
   failures, also preserve the armed static ILA and investigate the captured
   ECI traffic before reset. Restore temporary controls only after preservation;
   record whether tracing remains frozen or has resumed.

Measured payload throughput was **6.9 MiB/s** with the current single-outstanding
1408-byte Python reader, versus roughly 0.01–0.02 MiB/s for the observed
Vivado/Tcl JTAG dumper. This is implementation throughput, not CMAC line rate.
The board validation does not establish long-duration reliability or timing closure.

## Overview

The tracing path has four layers:

1. Hardware trace producers inside `NicEngine`, DCS glue, and ECI paths emit
   small trace payloads.
2. `LauberhornTraceDma` collects those producers into one `TraceBufferDMA`
   instance and writes timestamped fixed-width samples into trace DDR.
3. Python tooling converts a raw trace-buffer dump into pcapng.  It handles the
   outer TraceBufferDMA envelope, trace-buffer wrap detection, timestamp
   realignment, source filtering, and pipeline-latency timestamp correction.
4. `lauberhorn_trace.lua` decodes the pcapng packets in Wireshark/TShark.  It
   owns source-specific payload interpretation for DCS, ECI, returned-credit,
   and Lauberhorn event payloads.

In the pcapng export path, Python preserves raw payloads and emits metadata; Lua
uses the embedded trace map for interactive decoding. Python also has a separate
`eci-state` analysis mode that decodes ECI headers and reconstructs delivery/credit
state, and the latency plotter decodes Lauberhorn events. Do not apply the pcapng
transport boundary as a blanket restriction on Python analysis code.

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

`TracePlugin` also records each port's source SLR.  It converts that source SLR
to a pipeline-stage count using the same five-stages-per-SLR-crossing rule used
by the trace DMA.  The final per-port pipeline-stage list is passed to
`LauberhornTraceDma` when the separate trace DMA RTL is elaborated.

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

It assigns source IDs, inserts source-specific `Flow.delay(...)` pipeline stages
before `TraceBufferDMA`, and records the latency in the trace map as
`pipeline_stages`.  Fixed DCS/ECI sources derive their stage count from the
floorplan.  Lauberhorn event sources inherit stage counts from the `makePort`
source SLR declarations.

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

## Trace Storage And Readout

The trace buffer is stored in the dedicated trace DDR attached to the Vivado
block-design DDR4 MIG instance `design_1_i/ddr4_4`.  `TraceBufferDMA` writes
samples to that DDR through the `trace_ddr_axi` AXI port.  The same MIG address
space is also reachable from the Vivado debug path: the block design connects
`jtag_axi_0/M_AXI` through the AXI smartconnect to the DDR4 memory map.

This gives the tracing system an out-of-band readout path:

1. Trace samples are written continuously into the circular DDR buffer while the
   design runs.
2. After stopping or snapshotting the workload, Vivado hardware manager can use
   the JTAG-to-AXI master to issue AXI reads into the trace DDR address space.
3. A Vivado Tcl flow can dump the requested DDR address range to a raw binary
   file on the host.
4. Python tooling consumes that raw dump and converts it to pcapng.

This readout path is intentionally separate from the normal ECI/NIC datapath.  It
does not require software running on the host CPU to understand the trace buffer;
Vivado reads the MIG-backed trace storage directly over JTAG AXI.

### UDP Readout

Large trace dumps should use the lightweight UDP readout path instead of the
JTAG AXI dumper.  `lauberhorn_trace_dma` reuses the read side of the same AXI
DMA that writes the trace buffer and streams bounded trace-DDR reads out through
a dedicated trace CMAC.  The path is intentionally small: it strips one combined
Ethernet/IPv4/UDP request header, parses the trace command in the UDP payload,
issues one aligned DMA read, then injects one combined Ethernet/IPv4/UDP
response header.  An AXI stream aligner is placed before the trace CMAC TX path
so partial `TKEEP` beats are only presented at packet end.

The trace CMAC is wired to `F_MAC1`, whose package pins are GTY quad 124 in SLR1
(`X0Y20` through `X0Y23`) and use CMAC site `CMACE4_X0Y3`.  This is separate
from the normal `NicEngine` CMAC on `F_MAC0`.  The trace-status VIO controls
the responder's local identity, next-hop MAC, and dump-capture server address.
With all override-valid bits left at zero, the hardware defaults are:

- self MAC: `0c:53:31:03:01:cc`
- self IPv4 address: `192.168.129.204`
- listen UDP port: `55555`
- gateway/next-hop MAC: `f4:52:14:63:a0:91`
- dump server IPv4 address: `129.132.102.8`
- dump server UDP port: `55555`

Both active ports use GT RX/TX polarity mask `0011`: F_MAC0 for RPC and
F_MAC1 for trace. This matches the engineer's
[interfaces-stub reference, revision 331c57c](https://gitlab.inf.ethz.ch/project-openenzian/fpga-stack/applications/interfaces-stub/-/blob/331c57ccf68f8db97c3ab0ed6f9a263a64aa1a12/bd/design_1.tcl#L549).
On zuestoll14, F_MAC1 is leaf1 Ethernet100 (ZS14-FPGA2). The main RPC
interface remains `0c:53:31:03:01:c8` / `192.168.129.200`; the capture peer
remains zuestoll12 `cpu40g1`, `192.168.129.129`.

Historical F_MAC3 images used `0c:53:31:03:01:d4` / `192.168.129.201` and
polarity `1100`. Use the address and port for the actual bitstream; do not
apply old F_MAC3 capture filters or neighbor overrides to an F_MAC1 image.
The F_MAC1 move is awaiting routed and board validation; earlier successful
captures in this document were on F_MAC3.

The VIO exposes a six-bit override-valid mask plus the six override values, a
`dump over network` boolean output, and a `trace stop` boolean output:

- bit 0 enables the self MAC override;
- bit 1 enables the self IPv4 override;
- bit 2 enables the listen UDP port override;
- bit 3 enables the gateway MAC override;
- bit 4 enables the dump server IPv4 override;
- bit 5 enables the dump server UDP port override.

Asserting `trace stop` freezes trace collection into DDR by preventing
`TraceBufferDMA` from issuing new write descriptors.  If a descriptor is already
in flight when the bit is asserted, that descriptor can still complete before
`writeSlot` stops advancing.  The trace dump/read path remains active, so the
frozen buffer can be dumped with the normal UDP or JTAG tools.

The dump-capture machine runs the Python script as a UDP server.  When the VIO
`dump over network` output rises, the FPGA sends a zero-data metadata packet to
the configured dump server.  That outbound packet opens the gateway/NAT mapping.
The Python server accepts the peer address from that metadata datagram and then
sends read commands back over the same UDP mapping.  Command responses use the
request's source IPv4 address and source UDP port as the response destination.

The Ethernet destination MAC for FPGA-originated UDP packets is the configured
gateway/next-hop MAC.  The FPGA also answers ARP requests for its self IPv4
address with its self MAC, so the local gateway can discover the FPGA when it
needs to forward traffic on the FPGA-facing network.  The FPGA does not run an
ARP client for the gateway.

ARP occupies the 42-byte extracted Ethernet/ARP header. Any trailing Ethernet
padding is discarded before the UDP command aligner. Retaining that padding
would associate it with the next UDP header and reject a valid read command.

The software request format requires 64-byte-aligned offsets and lengths.  A
single hardware response carries at most 1440 bytes of trace data: 1500-byte
Ethernet MTU minus IPv4, UDP, and 32 bytes of trace response metadata.  The host
tool loops over chunks for multi-GB dumps.  A zero-length read is a status query
and returns `writeSlot`, `wrapped`, `sampleLost`, and `dmaError` without issuing
a DDR read.

The host uses one outstanding numbered read at a time. Missing or malformed
responses time out; by default it retries the identical request five times
after one-second waits, then fails. It validates sequence, offset, returned
length and DMA status before appending data. Retries are not individually
reported by the current CLI; a failed dump leaves a partial file. Initial
metadata reception is separate and requires another VIO announcement if lost.
These retries recover transport loss, not samples lost before DDR recording.
The protocol has no application payload checksum and FPGA UDP responses use
a zero UDP checksum; sequence/length checks alone are not an independent
content-integrity check.

For completeness, freeze and verify a stable write pointer, read the entire
valid range (and handle wrapping), then inspect recorded loss markers with the
matching map. `sampleLost` is sticky and can become asserted after freezing
because trace inputs continue while DDR writes stop. A set flag alone cannot
date the loss relative to freeze.

Example status query:

```sh
python3 data/eci/sys_trace/dump_trace_udp.py \
  --bind-ip 129.132.102.8 \
  --status
```

Example dump:

```sh
python3 data/eci/sys_trace/dump_trace_udp.py \
  --bind-ip 129.132.102.8 \
  --bytes 0x80000000 \
  --chunk-bytes 1408 \
  --out trace-dram.bin
```

The Python default is 1408 bytes, the largest 64-byte-aligned payload within
the hardware's 1440-byte limit. Older tools defaulted to 1440 and need an
explicit `--chunk-bytes 1408` to avoid hardware alignment errors. UDP
port 55555 uses an ordinary socket and does not itself require `sudo`; the bind
address must be assigned to the capture machine and reachable in the configured
network.

Start the tool before toggling the VIO `dump over network` bit.  The tool uses a
normal UDP socket and waits for the FPGA metadata packet; it does not need the
FPGA MAC address or private FPGA IP address.

For capture on another Enzian's FPGA-facing CPU interface, bind to that
interface's IPv4 address. Set VIO override bits 3 and 4 (`0x18`) and supply
the capture interface's MAC as `trace_dump_gateway_mac` and its IPv4 address
as `trace_dump_server_ip`. On the same subnet the next hop is the capture
host itself. Check reservations and current interface addresses first.
Assert `trace_stop`, wait for `writeSlot` to stabilize, start the receiver,
then toggle `trace_dump_over_network` from zero to one. Preserve the original
VIO values for restoration after the experiment.

Before debugging UDP, check the VIO at `hier_trace_cmac_ctrl_stat/vio_0`:
`stat_rx_aligned` and `stat_rx_status` must be one, with local/remote faults
clear. The trace CMAC includes RS-FEC and ties RX/TX FEC enable, RX correction,
and indication high, with no AXI register interface. The external peer must
use compatible FEC. Internal PMA loopback can check the local CMAC/GT path;
restore `loopback=0` before trying network capture.

### Command-path diagnostics (CMAC investigation branch)

The `fix/cmac-trace-readout` branch adds read-only inputs to `vio_trace_status`.
They have local simulation/elaboration coverage and board validation. Pipeline
514053 (`c5be158`) captured one MiB using normal ARP and UDP after fixing short
ARP replies and a mid-packet TKEEP gap. The first and last four KiB matched
independent frozen JTAG reads. External link operation is still intermittent;
FPGA RX alignment alone does not establish a usable link. Check leaf1's
Ethernet108 / ZS14-FPGA4 status through `python3 tools/enzian/leaf1.py` before
capture. See [the CMAC diagnosis](cmac-trace-diagnosis.md#framing-fix-image-udp-capture-validated-link-intermittent).
Counters wrap modulo 2^32, reset with their owning clock domain, and keep running
when `trace_stop` freezes DDR writes. Compare deltas around an isolated request;
VIO reads are not an atomic snapshot. RX/TX domain counters cross to the app
clock using registered Gray code and synchronizers.

| Probe suffix (`trace_dump_`) | Meaning |
| --- | --- |
| `rx_frames` | CMAC RX valid/TLAST frames, including frames subsequently dropped |
| `rx_overflow` | RX asynchronous FIFO overflow events |
| `rx_after_cdc` | Complete frames accepted by the responder from the RX FIFO |
| `tx_queued` | Complete aligned frames accepted by the TX FIFO |
| `tx_frames` | Complete frames accepted by CMAC TX; not proof of wire delivery |
| `headers` | Extracted headers accepted by the responder |
| `arp_requests` | Recognized ARP requests for the configured trace IP |
| `commands` | Matching UDP destination and command magic/version/opcode; alignment errors also count |
| `responses` | Complete frames handed off by the responder, including ARP and unsolicited metadata |
| `tagged_headers` | Extracted headers with outer EtherType 0x8100 or 0x88a8 |
| `last_header` | First 32 bytes of the last accepted header; byte 0 occupies bits 7:0 |
| `state` | Parser state and handshake levels, described below |

`state` bits 0..6 indicate idle, drainRequest, issueRead, sendArpReply,
sendNetHeader, sendTraceHeader and sendData respectively. Bits 7..15 are
haveRxHeader, discardArpBody, rxHeaderIsUdpCommand, extractor header valid,
aligned request valid, DMA read descriptor valid, descriptor ready, read data
valid and responder TX ready. This mapping does not depend on FSM encoding.

No tagged command support is added by these probes. RX frame counts also do
not certify FCS validity. A frame count that advances without parser progress
localizes a different failure from a generated response that fails to reach
CMAC TX or the peer. Switch counters and packet capture remain necessary for
link and forwarding diagnosis.

The intended offline flow is:

1. Program and run the design.
2. Stop or snapshot the workload.
3. Dump the trace DDR buffer to a raw binary file through the UDP trace CMAC.
   Use the Vivado hardware-manager JTAG AXI path as a fallback when Ethernet
   readout is unavailable.
4. Convert the raw dump to pcapng with `export_trace_pcap.py`.
5. Open the pcapng in Wireshark or TShark with `lauberhorn_trace.lua`.

The exporter starts from a raw dump file produced by either UDP or JTAG AXI
readout.  See [the trace parser README](../../data/eci/sys_trace/README.md) for the concrete exporter
and Wireshark commands.

## Python Tooling

The Python code under `data/eci/sys_trace` bridges the hardware recorder and
the Wireshark dissector.  For pcapng output, it handles the outer
TraceBufferDMA stream: sample width, source ID, raw timestamp, marker records,
circular-buffer ordering, timestamp wrap, and floorplan pipeline latency.

The pcapng path produces a chronologically ordered view of raw trace samples
and embeds the trace map as metadata, leaving interactive inner-payload decoding
to Lua.  The adjusted
timestamp is used for packet ordering and pcapng time; the raw hardware
timestamp is preserved so the viewer can show both.

The same path also supports legacy Vivado ILA captures by repacking old CSV rows
into the current trace-sample envelope.  Those legacy captures have no inserted
trace-source pipeline stages, so their raw and adjusted timestamps are the same.

## Lua Dissector

`data/eci/sys_trace/lauberhorn_trace.lua` is the Wireshark/TShark dissector.  It
owns source-specific payload interpretation for interactive viewing.  It loads
the trace map embedded by Python, uses source metadata to pick the right
decoder, and turns raw payload bits into DCS fields, ECI packet details, or
Lauberhorn event names and structured trace data.  Source types that do not yet
have a rich Lua decoder still carry their typed payload layout in the trace map
and remain available as raw samples.

Payload schema changes must update the trace map and relevant consumers: the
Lua dissector for interactive viewing, `eci_state_output.py` for ECI analysis,
and `data/eci/apps/plot_latency.py` for latency correlation. Outer-envelope,
timestamp, or transport changes additionally affect the generic Python exporter.  See [the trace parser README](../../data/eci/sys_trace/README.md) for TShark,
Wireshark, and profile commands.

## End-To-End Flow

The complete data path is:

1. A hardware block calls `tp.trace("EventName", TraceData...) := condition`.
2. `TracePlugin` assigns a canonical event ID and packs event ID plus trace data
   into a Lauberhorn event payload.
3. `LauberhornTraceDma` receives the event payload, applies floorplan-derived
   pipeline delay, assigns the global source ID, and forwards it to
   `TraceBufferDMA`.
4. `TraceBufferDMA` adds the hardware timestamp and writes the fixed-width sample
   to trace DDR.
5. The trace DDR circular buffer is dumped to a raw binary file.
6. `export_trace_pcap.py` reads the dump, realigns circular-buffer order,
   corrects timestamps for source pipeline stages, embeds the trace map, and
   writes pcapng packets carrying raw samples.
7. Wireshark loads `lauberhorn_trace.lua`, reads the embedded trace map, and
   decodes each payload for visualization.

This split keeps the hardware recorder generic, keeps Python focused on the
outer sample stream and timestamp ordering, and keeps human-facing payload
decode logic in the Lua dissector.

## Source map

- [Event declarations](../../hw/src/lauberhorn/TracePlugin.scala), [DMA/map generation](../../hw/src/lauberhorn/LauberhornTraceDma.scala), [UDP responder](../../hw/src/lauberhorn/LauberhornTraceDump.scala).
- [UDP capture tool](../../data/eci/sys_trace/dump_trace_udp.py), [exporter](../../data/eci/sys_trace/export_trace_pcap.py), [ECI analysis](../../data/eci/sys_trace/eci_state_output.py).
- [Latency measurement and attribution](../research/latency-measurement.md).

## Cooperative activation events

The current ECI preemption ports emit `YieldAccepted`, `ActivationCommitted`
and `ActivationDelivered`, with physical core, PA domain code and scheduler
slot; committed activation also records RX/TX parity. Idle dispatch is identified
at acceptance. Use the matching generated trace map. Kernel `worker_switch`
records outgoing and incoming task/lifetime identities at activation delivery.

## Historical B4 scheduling and recovery events

The B4 source extends `SchedulerDeschedCore` with `PreviousProcessID` and
`EciTxSubmit` with `ExecutionCommand` (0 unchanged, 1 end, 2 begin, 3 end/begin).
Incoming `SchedulerRequestDispatched` begins an inline interval; completion RX
does not imply a resumed fiber. `SchedulerWatchdogExpired` marks expiry once,
while the existing preemption events describe READY/BUSY, interrupt and drain.
The DMA acceptance/completion events also carry the physical `CoreID`, since
per-core `HostMsgID` counters are not globally unique. Per-core scheduler CSRs
count watchdog-only TX and watchdog expirations. Preserve
these counters with captures to verify the disabled-monitoring case.

Two kernel trace events cover variable software waits:

| Event | Fields and phases |
|---|---|
| `lauberhorn:worker_switch` | CPU, old/next process lifetime, TGID/TID, killed; phase 0 interrupt acknowledgement, 1 hardware drained, 2 dispatch/idle ready |
| `lauberhorn:process_recovery` | Lifetime, TGID, process slot, reason; phase 0 stopping, 1 workers/queues drained, 2 outgoing/incoming ownership and endpoints retired |

Recovery reason values are 0 close, 1 explicit stop/unmap, 2 hardware kill and
3 fatal signal. Hardware traces distinguish budget expiry from BUSY timeout.
The kernel assigns a monotonically increasing lifetime at registration so PID
or process-slot reuse does not silently merge separate applications.

Enable `events/lauberhorn/enable` in tracefs for a recovery campaign, and preserve
its trace, per-CPU loss/overrun statistics, kernel log, hardware raw capture and
matching generated trace map. Kernel timestamps and NIC cycles are separate
clock domains: correlate the worker/PID switch sequence before fitting offsets;
do not subtract raw timestamps across domains. Reconstruct queue residence,
active execution, suspended waits, coherence drain and kernel routing/dispatch
separately. Report incomplete intervals and trace loss. These events support the
analysis; they are not themselves measured scheduling results.

## Directory allocation stalls

The trace-only `trace-directory-allocation` DCS branch adds nonfatal
`ERR_RTG_ALLOC_FULL` (code 7). It identifies an allocating miss with no free RTG
way and preserves the blocked request's opcode/address even though it was not
accepted. `state=s1__1`, `action=STALL` are diagnostic values on this pre-ROM path.
Use metadata containing the new label with the matching DCS revision; old payload
field widths and enum values are unchanged.

The marker applies to local LC/LCI as well as other allocating misses. It does not
imply CPU residency, an evictable victim, or deadlock: the full set may consist of
local locks waiting for UL. At most sixteen reports for a repeated header/VC are
emitted until a different request or successful request service rearms reporting.
Do not infer stall clearance from absent samples. Correlate LC/LCI, LCIA, UL and
CPU eviction events, preserving pre-freeze loss status and incomplete-history
limitations. See the [allocation-trace contract and regression](../hardware/directory-pressure.md#allocation-full-tracing-eviction-policy-deferred).

## Timestamp capture point and legacy queue bias

The October 11 memcached campaign found that dependency `5845335` and earlier
`TraceBufferDMA` implementations sampled `cycleCount` when a trace batch left
the input FIFO. Serialization/backpressure could therefore add a variable delay
to timestamps even when every sample was retained. A loss-free dump alone does
not establish event-time accuracy. The preserved 518836 capture demonstrates
contradictory CPU/DCS timing bounds under concurrent bursts; see the
[memcached evidence](../research/memcached.md#first-joined-rtt-breakdown-and-recorder-limitation--october-11).

Correction `e24f2ef` captures the timestamp together with the batch at input and
carries it through the queue. The generated map now declares
`sample.timestamp_point = "trace_dma_input_before_batch_queue"`. Maps without
that field describe legacy dequeue timestamping. The sample bit layout is
unchanged, but maps and provenance must distinguish these contracts. The
regression checks ingress-cycle spacing under multi-source serialization and
rejects the original implementation.

System-domain ECI sources still cross an untimestamped asynchronous FIFO before
reaching the trace recorder. Fixed pipeline-delay subtraction does not bound
variable CDC residence. Report CPU/FPGA fit uncertainty as conditional on that
path and reject inconsistent fits; do not widen the acceptance threshold merely
to obtain a breakdown. App-domain NIC/MAC events with the corrected recorder
avoid the global batch-queue bias. New hardware validation is required before
claiming corrected measurements; historical fine hardware intervals, including
nullrpc comparisons, retain this newly identified limitation until reassessed.
CPU-only intervals and client RTTs are unaffected by the recorder correction.
