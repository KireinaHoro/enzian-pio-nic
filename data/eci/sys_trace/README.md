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

The exporter intentionally targets filtered or bounded windows. A full 32 GiB
trace buffer contains billions of 128-bit samples, which is too large to treat
as one interactive packet list. The pcapng contains one metadata packet with the
trace-map JSON, raw packets for real samples, and marker packets for lost/bubble
samples.

The input path is a raw binary DRAM dump. `--from-vivado` is reserved for a
future mode that streams the trace buffer via Vivado hardware manager and the
JTAG AXI master instead of first writing a raw dump file.
