# Lauberhorn Tracing

Detailed reference, source-reviewed on 2026-09-08. Start with [validation](validation.md)
for test selection or the [trace tools README](../../data/eci/sys_trace/README.md)
for exporter commands. Commands below run from the repository root. Hardware defaults
are deployment-specific; preserve the generated trace map alongside each capture.

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

The trace CMAC is wired to `F_MAC3`, whose package pins are GTY quad 233 in SLR2
(`X1Y56` through `X1Y59`) and use CMAC site `CMACE4_X0Y8`.  This is separate
from the normal `NicEngine` CMAC on `F_MAC0`.  The trace-status VIO controls
the responder's local identity, next-hop MAC, and dump-capture server address.
With all override-valid bits left at zero, the hardware defaults are:

- self MAC: `0c:53:31:03:01:d4`
- self IPv4 address: `192.168.129.201`
- listen UDP port: `55555`
- gateway/next-hop MAC: `f4:52:14:63:a0:91`
- dump server IPv4 address: `129.132.102.8`
- dump server UDP port: `55555`

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

The software request format requires 64-byte-aligned offsets and lengths.  A
single hardware response carries at most 1440 bytes of trace data: 1500-byte
Ethernet MTU minus IPv4, UDP, and 32 bytes of trace response metadata.  The host
tool loops over chunks for multi-GB dumps.  A zero-length read is a status query
and returns `writeSlot`, `wrapped`, `sampleLost`, and `dmaError` without issuing
a DDR read.

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

Use `--chunk-bytes 1408` explicitly: the current Python default is 1440, which
is not 64-byte aligned and is rejected by the hardware alignment check. This is
a known tool/hardware mismatch, not a larger supported aligned transfer. UDP
port 55555 uses an ordinary socket and does not itself require `sudo`; the bind
address must be assigned to the capture machine and reachable in the configured
network.

Start the tool before toggling the VIO `dump over network` bit.  The tool uses a
normal UDP socket and waits for the FPGA metadata packet; it does not need the
FPGA MAC address or private FPGA IP address.

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
