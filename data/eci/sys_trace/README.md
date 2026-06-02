# Lauberhorn System Trace

This directory contains the tools for turning a trace DDR dump into pcapng and
viewing it in Wireshark/TShark.  For the architecture and role split between
hardware, Python, and Lua, see `../../../docs/TRACING.md`.

`export_trace_pcap.py` exports selected TraceBufferDMA samples to pcapng for
visualization with `lauberhorn_trace.lua`. It consumes a raw binary dump of the
trace DDR buffer plus the generated `lauberhorn_trace_dma_map.json`.

Example:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  --samples 1000000 \
  --source 4 \
  -o trace.pcapng \
  trace-dram.bin

tshark -r trace.pcapng \
  -X lua_script:data/eci/sys_trace/lauberhorn_trace.lua
```

For interactive Wireshark use, copy or symlink `lauberhorn_trace.lua` into the
personal plugin directory, or load it with the equivalent Lua script option.
The optional `colorfilters` file in this directory can be imported through
View -> Coloring Rules instead of being installed by the Lua plugin at startup.
Alternatively, use the repo-local profile, which already contains the coloring
rules:

```sh
wireshark \
  -P persconf:data/eci/sys_trace/wireshark_config \
  -C Lauberhorn \
  -r trace.pcapng \
  -X lua_script:data/eci/sys_trace/lauberhorn_trace.lua
```

The exporter intentionally targets filtered or bounded windows. A full 32 GiB
trace buffer contains billions of 128-bit samples, which is too large to treat
as one interactive packet list. Use `--source`, `--start`, and `--samples` to
select a useful window.

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

The input path is usually a raw binary DRAM dump. To capture one from Vivado
Hardware Manager through the JTAG AXI master, source the Tcl dumper inside the
Vivado Tcl console after connecting to the target:

```tcl
source data/eci/sys_trace/dump_trace_hw_axi.tcl
set LH_TRACE_OUT data/eci/sys_trace/iperf-tx-0x8000-timeout.bin
set LH_TRACE_BYTES 0x800000000
lhtrace::dump
```

`LH_TRACE_BYTES` bounds the maximum read. The dumper prints progress and
throughput while it runs, prints relevant JTAG AXI properties when Vivado
exposes them, and probes for the longest accepted Hardware Manager read length.
It trims the dump by default when it reaches a long aligned DDR default-fill
tail matching `00ff` or `ff00`. To force a specific read length, set
`LH_TRACE_AXI_LEN_AUTO 0` and `LH_TRACE_AXI_LEN` before calling
`lhtrace::dump`.

Convert the resulting binary dump to pcapng with:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  --samples 1000000 \
  -o data/eci/sys_trace/iperf-tx-0x8000-timeout.pcapng \
  data/eci/sys_trace/iperf-tx-0x8000-timeout.bin
```

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
