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

The input path is usually a raw binary DRAM dump. The exporter can also acquire
that dump from the Vivado hardware path first. The Tcl backend runs Vivado in
batch mode, connects to `hw_server`, selects the requested FPGA and JTAG AXI
master, reads trace DDR, and writes the same little-endian binary format that
the normal parser expects:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --from-vivado \
  --vivado-readout tcl \
  --hw-server-host 192.0.2.10 \
  --hw-server-port 3121 \
  --fpga-jtag-id 0x14b31093 \
  --vivado-address 0x0 \
  --vivado-dump-bytes 0x10000000 \
  --vivado-dump-out trace-dram.bin \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  --samples 1000000 \
  -o trace.pcapng
```

For a faster direct-read loop, start an XSDB command server from a Vivado/Vitis
2023.2 XSDB shell:

```tcl
xsdbserver start -host 0.0.0.0 -port 3010
```

Then use the Python-driven XSDB backend. It still connects XSDB to the requested
`hw_server`, selects the `JTAG2AXI` target, and reads the trace DDR over JTAG
AXI, but the outer fetch loop stays in Python:

```sh
python3 data/eci/sys_trace/export_trace_pcap.py \
  --from-vivado \
  --vivado-readout xsdb \
  --xsdb-server-host localhost \
  --xsdb-server-port 3010 \
  --hw-server-host 192.0.2.10 \
  --hw-server-port 3121 \
  --fpga-jtag-id 210357B4B301A \
  --vivado-address 0x0 \
  --vivado-dump-bytes 0x10000000 \
  --map out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json \
  --samples 1000000 \
  -o trace.pcapng
```

`--vivado-dump-bytes` is required for both readout backends. Use
`--jtag-axi-name` with the Tcl backend when there is more than one JTAG AXI
master. With Vivado/Vitis 2023.2, the XSDB backend connects to `hw_server` with
`connect -url TCP:<host>:<port>` and selects the `JTAG2AXI` target under the
cable serial given by `--fpga-jtag-id`. Both `210357B4B301A` and
`Digilent/210357B4B301A` are accepted.

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
