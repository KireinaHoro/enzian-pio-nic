# Lauberhorn System Trace

This directory contains the tools for turning a trace DDR dump into pcapng and
viewing it in Wireshark/TShark.  For the architecture and role split between
hardware, Python, and Lua, see `../../../docs/TRACING.md`.

`export_trace_pcap.py` exports selected TraceBufferDMA samples to pcapng for
visualization with `lauberhorn_trace.lua`. It consumes a raw binary dump of the
trace DDR buffer, optionally gzip-compressed with a `.gz` suffix, plus the
generated `lauberhorn_trace_dma_map.json`.

Example:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  --samples=-1000000: \
  --source 4 \
  -o trace.pcapng \
  trace-dram.bin

tshark -r trace.pcapng \
  -X lua_script:data/eci/sys_trace/lauberhorn_trace.lua
```

To feed the sys-clock ECI stream into a Python model instead of writing pcapng,
use:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --output-mode eci-state \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  trace-dram.bin
```

This mode calls `handle_eci_frame` in `eci_state_output.py` for each accepted
sys-clock ECI frame. The hook receives `time`, `time_ns`, `opcode`,
`opcode_name`, `dmask`, `unaliased_address`, and `raw_header`. It requires the
full trace: `--samples`, `--source`, legacy ILA input, circular buffer wrap,
and lost-sample control frames are rejected.

For interactive Wireshark use, copy or symlink `lauberhorn_trace.lua` and the
adjacent `lhtrace/` Lua module directory into the personal plugin directory, or
load the script with the equivalent Lua script option from this directory. The
optional `colorfilters` file in this directory can be imported through View ->
Coloring Rules instead of being installed by the Lua plugin at startup.
Alternatively, use the repo-local profile, which already contains the coloring
rules:

```sh
wireshark \
  -P persconf:data/eci/sys_trace/wireshark_config \
  -C Lauberhorn \
  -r trace.pcapng \
  -X lua_script:data/eci/sys_trace/lauberhorn_trace.lua
```

## Useful Wireshark Filters

The Lua dissector adds generated fields for common analysis views:

```text
lhtrace.eci.canonical
```

Shows one canonical packet per matched ECI frame after CDC/SLR crossing,
instead of every raw app/sys and stalled/accepted trace sample.

```text
lhtrace.eci.canonical && lhtrace.eci.crossing.direction == "dc_to_gateway"
```

Shows canonical ECI frames moving from the DC side into the ECI gateway.

```text
lhtrace.eci.canonical && lhtrace.eci.crossing.direction == "gateway_to_dc"
```

Shows canonical ECI frames moving from the ECI gateway back toward the DC side.

```text
_ws.expert.message contains "CDC/SLR"
```

Shows ECI CDC/SLR crossing match and trace-order notes/warnings. Missing
before-side matches at the beginning of a trace are reported as possibly due to
trace truncation until the dissector has seen an initial before-side event for
that crossing key.

```text
_ws.expert.message contains "trace order violates"
```

Shows ECI CDC/SLR crossings whose packet order in the trace violates the
expected happen-before relationship.

```text
lhtrace.eci.stalled_frame || lhtrace.eci.accepted_frame
```

Shows ECI stalled/accepted frame links. The canonical ECI crossing subtree also
contains generated before/after frame references.

The exporter intentionally targets filtered or bounded windows. A full 32 GiB
trace buffer contains billions of 128-bit samples, which is too large to treat
as one interactive packet list. Use `--source` and `--samples` to
select a useful window. `--samples` uses Python slice syntax without stepping:
`--samples=111:` exports from sample 111 to the end, `--samples=:1000` exports
from the beginning through sample 999, and `--samples=-10000:` exports the last
10,000 chronological samples. Negative indices count back from the end of the
dump. For example, `--samples=-2000000:-1000000` exports the million samples
before the last million.

The pcapng contains one metadata packet with the trace-map JSON, raw packets for
real samples, and marker packets for lost/bubble samples. Sample packets are
sorted by timestamp after subtracting each source's `pipeline_stages`; the
original hardware timestamp is still carried in the packet envelope as
`lhtrace.raw_timestamp`.

Packets share the same little-endian envelope after the first four magic bytes,
but use separate magics so Wireshark can display them as separate protocols:
`LHTM` for metadata, `LHTC` for lost/bubble control frames, `LHTE` for
NicEngine event trace data, `LHTD` for DCS events, `LHEA` for app-clock ECI
frames, and `LHES` for sys-clock ECI frames.

The input path is usually a raw binary DRAM dump, or the same dump compressed as
`*.gz`. To capture one from Vivado Hardware Manager through the JTAG AXI master,
source the Tcl dumper inside the Vivado Tcl console after connecting to the
target:

```tcl
source data/eci/sys_trace/dump_trace_hw_axi.tcl
lhtrace::dump \
  -out data/eci/sys_trace/iperf-tx-0x8000-timeout.bin \
  -bytes 0x800000000
```

`-bytes` bounds the maximum read. The dumper reads the trace status VIO
first: if the trace buffer has not wrapped, it dumps `writeSlot * 64` bytes; if
it has wrapped, it dumps the full configured buffer. It also prints progress and
throughput while it runs, prints relevant JTAG AXI properties when Vivado
exposes them. To force a specific read length, pass `-axi-len` to
`lhtrace::dump`. Run `lhtrace::dump -help` for all options and default values.

To resume an existing, unwrapped dump after the write slot has advanced, use:

```tcl
lhtrace::append \
  -out data/eci/sys_trace/iperf-tx-0x8000-timeout.bin \
  -bytes 0x800000000
```

Append mode reads the current trace status, checks the existing output file
size, verifies the file tail against the same bytes in the trace DDR buffer, and
then appends only the bytes between the existing file size and the current
`writeSlot * 64` boundary. It refuses to append if the current trace buffer has
wrapped, if the existing file is larger than the current capture range, if the
file size is not trace-slot aligned, or if the verified tail does not match.
The tail check size defaults to 4096 bytes and can be changed with
`-append-verify-bytes`. Run `lhtrace::append -help` for all options and default
values.

Convert the resulting binary dump to pcapng with:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  --samples=-1000000: \
  -o data/eci/sys_trace/iperf-tx-0x8000-timeout.pcapng \
  data/eci/sys_trace/iperf-tx-0x8000-timeout.bin
```

This writes the last 1,000,000 chronological samples from the dump.
While exporting, the script prints stderr progress bars for the initial sample
scan, timestamp-adjusted sample ordering, and how many matching sample packets
have been written.
The initial scan is cached automatically in a JSON sidecar next to the input
named `<input>.lhtrace-scan.json`; for `.gz` inputs, the sidecar is next to the
compressed file and stores the hash of the decompressed raw bytes. The cache is
reused only when the decompressed input hash and scan parameters still match.

Legacy Vivado ILA CSV captures under `data/eci/dcs_trace` can be converted
through the same pcapng/Lua path:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --legacy-ila data/eci/dcs_trace/iperf-timeout-0x8000-2026-03-20 \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  -o legacy-trace.pcapng
```

This mode reads the old CSV window directly. For DCS samples it extracts
`src`, `req`, `action`, `cli`, `state`, and `error`; for ECI samples it extracts
the VC and raw 64-bit header, then marks each legacy row as an accepted ECI
trace event. Those fields are packed into the current 128-bit trace sample
envelope, so the Lua dissector still sees raw packets and decodes
source-specific payloads from the embedded trace-map metadata.
