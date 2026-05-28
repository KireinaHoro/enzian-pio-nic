# Lauberhorn System Trace

`export_trace_pcap.py` exports selected TraceBufferDMA samples to pcapng for
Wireshark/TShark visualization with `lauberhorn_trace.lua`. The Python exporter
owns dump acquisition, sample windowing, source filtering, circular-buffer
realignment, and outer sample framing. The Lua dissector owns source-specific
payload parsing using the trace-map JSON embedded as the first pcapng packet.

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
as one interactive packet list. The pcapng contains one metadata packet with the
trace-map JSON, raw packets for real samples, and marker packets for lost/bubble
samples. Sample packets are sorted by timestamp after subtracting each source's
`pipeline_stages`; the original hardware timestamp is still carried in the
packet envelope as `lhtrace.raw_timestamp`.

Packets share the same little-endian envelope after the first four magic bytes,
but use separate magics so Wireshark can display them as separate protocols:
`LHTM` for metadata, `LHTC` for lost/bubble control frames, `LHTE` for
NicEngine event trace data, `LHTD` for DCS events, `LHEA` for app-clock ECI
frames, and `LHES` for sys-clock ECI frames.

The input path is a raw binary DRAM dump. `--from-vivado` is reserved for a
future mode that streams the trace buffer via Vivado hardware manager and the
JTAG AXI master instead of first writing a raw dump file.

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
the VC and raw 64-bit header. Those fields are packed into the current 128-bit
trace sample envelope, so the Lua dissector still sees raw packets and decodes
source-specific payloads from the embedded trace-map metadata.
