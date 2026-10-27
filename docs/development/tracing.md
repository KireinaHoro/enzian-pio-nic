# Lauberhorn Tracing

Start with [validation](validation.md)
for test selection, [capture configuration](#capture-configuration-and-commands)
for FPGA preservation, or the [trace tools README](../../data/eci/sys_trace/README.md)
for low-level exporter commands. Commands below run from the repository root. Hardware defaults
are deployment-specific; preserve the generated trace map alongside each capture.

Trace auto-pipeline delays are resolved from the final implemented netlist.
Use `eci/vivadoProject.dest/lauberhorn_trace_dma_map.json` from the same hardware
artifact set as the bitstream. Generated RTL maps contain unresolved latency;
analysis rejects them. Capture setup checks the resolved map's bitstream hash.
Timestamp correction subtracts the measured source-specific transport depth;
it does not recover variable latency through system-clock asynchronous FIFOs.

## Default agent capture pipeline

Use the [lauberhorn-trace skill](../../tools/enzian/skills/lauberhorn-trace/SKILL.md)
and [canonical capture workflow](tracing.md). Manual and
unattended benchmark capture share `tools/enzian/trace_capture.py` and its session
API. They reuse the existing CMAC receiver and JTAG reader rather than maintaining
separate paper-specific capture scripts or per-run Tcl.

Full capture uses F_MAC3 CMAC/UDP. JTAG is restricted to independent bounded
head/tail checks after successful Ethernet capture; Ethernet failure preserves
partial evidence and stops without reset or full JTAG fallback. Capture checks
current reservations and matching bitstream/LTX/map hashes, saves original VIO
controls, freezes to a stable write pointer, starts the receiver before triggering,
persists exact bytes/hash and integrity evidence, and restores controls while
leaving recording frozen. A working DUT driver is not needed for the hardware
trace responder. Keep tracing active through module load when investigating
`probe_versions` failures, and preserve static ILA evidence separately when armed.

`summary.json` certifies transport and restoration, not workload event counts or
clock joins. Retain the raw buffer and matched map and run the relevant analysis.
Wrapped buffers currently fail explicitly; never relabel a prefix as a full dump.
See the canonical workflow for transfer prerequisites, recording-only controls,
partial failure evidence and export-only recovery. Historical address examples
below describe particular board experiments, not implicit defaults for new runs.

## Shared capture implementation

The workflow reuses the existing low-level implementations:

- `data/eci/sys_trace/dump_trace_udp.py`: CMAC/UDP receiver, bounded retries and
  partial output. Its readiness marker is created only after socket bind.
- `data/eci/sys_trace/dump_trace_hw_axi.tcl`: JTAG reader, called only for two
  4 KiB head/tail integrity checks after a successful full Ethernet dump.
- `tools/enzian/trace/control.tcl`: one parameterized VIO/snapshot/trigger/check
  script staged from source, never generated per trial.
- `tools/enzian/run.py` and `console.py`: shared expect-based peer execution,
  reservation checks and console ownership. No alternative login implementation.

Workload-specific event counts, joins and latency analysis remain in paper tools;
transport integrity does not claim those measurements are valid.

## Capture configuration and commands

Use a JSON configuration with `gateway`, `programmer`, `dut`, `peer`, `vivado`,
`hw_server`, `cable`, `ltx`, `remote_artifacts` (remote bitstream/LTX paths mapped
to SHA256), `trace_map`, `trace_map_sha256`, `pipeline` (provenance), `peer_ip`,
`peer_mac`, `peer_management_ip`, `export_port`, `peer_image`, `peer_image_sha256`, `transfer_root`,
and `transfer_url`. `cable` accepts an exact `Digilent/SERIAL` ID or bare
alphanumeric serial; the shared controller normalizes it once. Select addresses and artifacts for the actual reservation.
For a board-specific trace identity, supply both `trace_ip` and `trace_mac`.
The controller applies these VIO overrides alongside the capture destination,
saves the original identity and restores it after capture. Omitting both uses
the bitstream defaults; a physical CMAC move alone does not change that identity.
The transfer endpoint must be running and reachable from the peer, with adequate
space. The existing `tools/paper/transfer_results.py` service supplies this during
campaigns; do not provision another server on an occupied endpoint. `tools/enzian/image.py` verifies the staged Nix image hash and mounts it read-only
in a private namespace for the receiver and exporter, using the same launcher
as benchmark roles. Host Python only bootstraps image verification; capture and
export execute with the image-pinned Python. Downloaded role scripts are hashed
before execution. The peer image must already be staged through the shared harness.
Capture does not reconfigure NICs, boot, reset, or change provisioning.

For a compatible campaign hardware manifest, resolve the explicit configuration
before capture:

```sh
python3 tools/enzian/trace_capture.py campaign-config --config HARDWARE.json > TRACE.json
python3 tools/enzian/trace_capture.py capture --config TRACE.json --output NEW_DIRECTORY
```

The CLI refuses to overwrite a capture. Each session saves configuration, source
hashes, the matching map, original VIO outputs, pre/post-freeze status, stable
write pointer, receiver logs, complete raw bytes and SHA256, JTAG comparisons,
restoration state and a relative-file hash manifest. A per-target lock serializes
canonical control operations. Trace recording remains frozen after restoration.
Only nonempty, unwrapped captures are supported. For traces shorter than
4096 bytes, both bounded JTAG checks cover the complete trace. Wrapped buffers
fail explicitly rather than silently persisting a prefix.

`capture.json` is durable progress/failure state. `summary.json` exists only after
successful persistence, integrity and restoration. `manifest.json` retains partial
failure evidence too. Stored trace loss/event-count validation belongs to the
matched analysis tool; recorder flags are preserved without interpreting a sticky
post-freeze loss bit as pre-freeze loss.

Ethernet failure is terminal: retain local/peer partial files, restore controls
when a saved snapshot exists, keep tracing frozen, and do not reset or fall back
to a full JTAG read. A primary failure remains visible if cleanup also fails.
If the receiver completed but export failed, explicitly retry export only:

```sh
python3 tools/enzian/trace_capture.py recover-export --config TRACE.json --output EXISTING_DIRECTORY
```

This requires a saved receiver-complete receipt and unchanged frozen recorder.
Each export attempt retains prior partial files. It does not trigger another dump.
Successful remote RAM copies are retained until the caller chooses to release
those resources after validating local persistence.

For recording-off controls without a dump, `freeze --config TRACE.json --output
CONTROL_DIRECTORY` saves the original controls and freezes only; `preserve` on
that same directory restores saved values while keeping recording frozen.
`status` reads the current recorder. Never call restore under a fresh label:
the session directory owns its snapshot, not an independently invented name.

## Capture-tool validation

```sh
nix develop -c python3 -m unittest discover -s tools/enzian/tests -p test_trace_capture.py
nix develop -c python3 -m unittest discover -s data/eci/sys_trace/tests -p test_dump_trace_udp.py
```

Normal echo campaigns freeze FPGA recording and collect unloaded wire
timestamps through Tofino; neither loaded nor unloaded normal cohorts dump FPGA
traces. Full FPGA capture preserves exceptional boot/module failures. Campaign
tests enforce the recording/capture boundary. Source changes require a reviewed compatible campaign resume, not a new
hardware acquisition of every completed cohort.


## Vivado crash recovery

All six control operations use one bounded runner: at most three attempts for
SIGSEGV (exit 139 or signal 11), with a separate retained log per attempt.
Ordinary Tcl errors, other exit statuses and timeouts remain terminal. A crash
never counts as success; a subsequent invocation must finish successfully and
the normal capture integrity checks still apply.

Operations are replayable: freeze/configure atomically save the original VIO
snapshot before mutation and never replace it on retry; trigger asserts the
existing control without creating a second rising edge; status and integrity
reads are read-only; preserve reapplies the original saved controls. No retry
resets the board or starts a full JTAG dump.

Nonempty diagnostic traces shorter than 4 KiB are captured in full; both JTAG
integrity reads cover the complete short trace. Empty recorders are reported
explicitly rather than represented as successful captures.

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

## Hardware instrumentation

[Hardware tracing](../hardware/tracing.md) defines event producers, source maps,
ECI boundary semantics, recorder timestamps and latency correlation.

## Trace Storage And Readout

The trace buffer is stored in the dedicated trace DDR attached to the Vivado
block-design DDR4 MIG instance `design_1_i/ddr4_4`.  `TraceBufferDMA` writes
samples to that DDR through the `trace_ddr_axi` AXI port.  The same MIG address
space is also reachable from the Vivado debug path: the block design connects
`jtag_axi_0/M_AXI` through the AXI smartconnect to the DDR4 memory map.

Readout is independent of the host driver and the normal ECI/NIC datapath.
Use F_MAC3 UDP for the full frozen buffer. The JTAG AXI connection supports
bounded independent head/tail integrity reads after successful Ethernet capture;
it is not a full-dump fallback. The
[shared capture workflow](tracing.md) owns freezing,
readout, persistence and restoration.

### UDP Readout

Full trace dumps use the lightweight UDP readout path.  `lauberhorn_trace_dma` reuses the read side of the same AXI
DMA that writes the trace buffer and streams bounded trace-DDR reads out through
a dedicated trace CMAC.  The path is intentionally small: it strips one combined
Ethernet/IPv4/UDP request header, parses the trace command in the UDP payload,
issues one aligned DMA read, then injects one combined Ethernet/IPv4/UDP
response header.  An AXI stream aligner is placed before the trace CMAC TX path
so partial `TKEEP` beats are only presented at packet end.

The trace CMAC is wired to `F_MAC3`, whose package pins are GTY quad 233 in SLR2
(`X1Y56` through `X1Y59`) and use CMAC site `CMACE4_X0Y8`. This is separate
from the normal `NicEngine` CMAC on `F_MAC0`. Trace dump processing, FIFOs,
reset synchronizers and trace-status VIO share SLR2 with trace DMA and DDR.
The VIO controls the responder's local identity, next-hop MAC and capture server.
With all override-valid bits left at zero, the hardware defaults are:

- self MAC: `0c:53:31:03:01:cc`
- self IPv4 address: `192.168.129.204`
- listen UDP port: `55555`
- gateway/next-hop MAC: `f4:52:14:63:a0:91`
- dump server IPv4 address: `129.132.102.8`
- dump server UDP port: `55555`

F_MAC0 uses GT RX/TX polarity mask `0011`; F_MAC3 uses `1100`, matching the
board lane wiring. The logical defaults above are retained across this port
move; they do not encode the physical port or identify the current DUT.
Confirm the board-specific switch mapping and set VIO identity overrides before
capture. Historical F_MAC1 validation was on zuestoll14; it does not validate
F_MAC3 on another board. Older F_MAC3 images used MAC `0c:53:31:03:01:d4` and
IP `192.168.129.201`; use the matching image's defaults/overrides, not old filters.

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
frozen buffer remains readable over UDP and available for bounded JTAG checks.

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
padding is discarded before the UDP command aligner. ARP replies are zero-padded
to 60 bytes excluding FCS. The trace response header occupies the high end of
its stream beat so DMA data follows without an internal `TKEEP` gap; the TX
aligner removes the leading gap before delivery to CMAC.

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
valid range, then inspect recorded loss markers with the
matching map. `sampleLost` is sticky and can become asserted after freezing
because trace inputs continue while DDR writes stop. A set flag alone cannot
date the loss relative to freeze. The canonical capture flow rejects wrapped
buffers; offline parser support for wrap does not make a partial dump complete.

The standard reader chunk is 1408 bytes, the largest 64-byte-aligned payload
within the 1440-byte hardware limit. Capture orchestration starts the receiver
before triggering metadata, supplies the actual peer's next-hop MAC/IP through
VIO overrides, and preserves the original controls. Use the
[configuration and commands](#capture-configuration-and-commands)
for full capture and export-only recovery. Low-level receiver options remain in
the [trace tools reference](../../data/eci/sys_trace/README.md).

Before debugging UDP, check the VIO at `hier_trace_cmac_ctrl_stat/vio_0`:
`stat_rx_aligned` and `stat_rx_status` must be one, with local/remote faults
clear. The trace CMAC includes RS-FEC and ties RX/TX FEC enable, RX correction,
and indication high, with no AXI register interface. The external peer must
use compatible FEC. Internal PMA loopback can check the local CMAC/GT path;
restore `loopback=0` before trying network capture.

### Command-path diagnostics

Read-only inputs to `vio_trace_status` expose the receive, parser and transmit
boundaries. FPGA RX alignment alone does not establish a usable external link;
compare FPGA counter deltas with peer captures and switch-port status.
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

For switch inspection, use
`python3 ../tofino-hw-timestamping/tools/leaf.py leaf1 -- show interfaces status`.
The helper handles the gateway/agg0/leaf1 SSH chain; credentials remain outside
this repository. Identify the port for the actual board and bitstream before
querying counters. On zuestoll14, F_MAC1 maps to Ethernet100 / front-panel 26/0;
the historical F_MAC3 path maps to Ethernet108 / front-panel 28/0.

SONiC FEC counters reported as N/A are unavailable, not zero. Vendor diagnostics
are accessible through `docker exec -it syncd /opt/bfn/install/bin/bfshell`,
then `ucli`, `pm`. For an identified front-panel port, use bounded `show -p`,
`port-pcs`, `port-ctrs ... fec` and `port-error-get` queries. Compare ordered
counter deltas when switch and peer clocks differ. Do not clear counters or
change link settings merely to diagnose a missing response.

A controlled internal near-end PMA loopback can isolate the local stream path.
Save VIO controls, use a metadata destination IP different from the trace local
IP to avoid a response loop, and trigger one metadata frame. Expect one frame
through TX queued, CMAC TX/RX, RX-after-CDC and header counters, no accepted
command, no overflow and an idle parser. Restore all controls afterward.
This checks the local stream path; it does not establish external signal
integrity or DDR-read correctness. Fabric timing margins and legal CMAC/GT
placement likewise do not establish serial-link health.

For offline analysis, start with the persisted raw buffer and matching map from
the [capture workflow](tracing.md). Convert to pcapng and
open it with the Lua dissector using the
[exporter and Wireshark commands](../../data/eci/sys_trace/README.md). Ethernet
failure leaves partial evidence and stops; it does not authorize reset or full
JTAG readout. The exporter can also read historical JTAG-produced files.

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
- [Latency measurement and attribution](../research/hot-measurement.md).

## Cooperative activation events

The current ECI preemption ports emit `YieldAccepted`, `ActivationCommitted`
and `ActivationDelivered`, with physical core, PA domain code and scheduler
slot; committed activation also records RX/TX parity. Idle dispatch is identified
at acceptance. Use the matching generated trace map. The kernel
`lauberhorn:activation` event records outgoing TID, cooperative kernel entry,
and incoming activation stages. Userspace DESCHED observation and yield entry
complete the cooperative handoff; see the [timestamp contract](rpc-breakdown.md).

## Historical B4 scheduling and recovery events

For historical B4 captures, the source extends `SchedulerDeschedCore` with `PreviousProcessID` and
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

When reproducing that historical ABI, enable `events/lauberhorn/enable` in
tracefs and preserve
its trace, per-CPU loss/overrun statistics, kernel log, hardware raw capture and
matching generated trace map. Kernel timestamps and NIC cycles are separate
clock domains: correlate the worker/PID switch sequence before fitting offsets;
do not subtract raw timestamps across domains. Reconstruct queue residence,
active execution, suspended waits, coherence drain and kernel routing/dispatch
separately. Report incomplete intervals and trace loss. These events support the
analysis; they are not themselves measured scheduling results.

## Directory allocation stalls

The allocation-trace variant of DCS defines nonfatal
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
limitations. See the [allocation-trace contract and regression](../hardware/lauberhorn-rom.md#allocation-full-tracing).

## Timestamp capture point and legacy queue bias

See the [hardware timestamp contract](../hardware/tracing.md#timestamp-capture-point-and-legacy-queue-bias)
for recorder queue bias and asynchronous ECI-source uncertainty, and
[latency correlation](../hardware/tracing.md) for event selection and clock joins.

### Vivado control recovery

See [Vivado crash recovery](#vivado-crash-recovery) for bounded retries,
replayable operations and preserved snapshots.
