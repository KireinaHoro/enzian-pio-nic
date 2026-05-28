# Lauberhorn Hardware Architecture

This document summarizes the current hardware source structure and the path from
SpinalHDL elaboration to a Vivado bitstream.

## Source Languages

Lauberhorn is mixed-language hardware:

- **SpinalHDL / Scala** under `hw/src/lauberhorn`: the main NIC engine,
  protocol pipelines, host datapaths, packet buffer, scheduler, CSRs, tracing,
  tests, and the RTL elaboration entry point.
- **VHDL** under `vivado/eci`: the Enzian/ECI top-level wrapper
  `lauberhorn_eci.vhd`, ECI toolkit modules, and the static-shell integration.
  The VHDL wrapper is the Vivado top for the ECI design and instantiates the
  generated SpinalHDL modules.
- **SystemVerilog** under `vivado/eci` and generated output: DCS logic,
  DCS/ECI bridge glue, local CDC/trace glue such as `dcs_cdc.sv` and
  `dcs_2_axi.sv`, and merged generated support RTL in `NicEngine_ips.sv`.
- **Verilog** from generated SpinalHDL output and third-party AXI/AXIS glue:
  `NicEngine.v`, `lauberhorn_trace_dma.v`, `axil_regs_interconnect.v`,
  `gt_loopback_gen.v`, and imported `verilog-axi` / `verilog-axis` modules.

Vivado Tcl scripts in `vivado/eci` and `vivado/pcie` assemble the generated and
hand-written RTL into projects and bitstreams.

## Top-Level Structure

For the ECI target, Vivado uses `vivado/eci/rtl/lauberhorn_eci.vhd` as the top
module.  It connects:

- the Enzian ECI link, ECI gateway, interrupt controller, CMAC block design,
  host AXI-Lite paths, and DDR interfaces;
- two DCS instances, one for even and one for odd ECI address slices;
- the generated `NicEngine` module;
- the generated `lauberhorn_trace_dma` module, which receives DCS, ECI, and
  Lauberhorn event trace sources and writes trace samples to trace DDR.

`NicEngine` itself is deliberately small.  It owns a SpinalHDL `Database` and a
`PluginHost`; the real hardware is installed by `GenEngineVerilog.engine`.

## NicEngine Plugins

`GenEngineVerilog.engine(nw, variant)` creates `nc = nw + 1` datapaths: core 0
is the bypass datapath and the remaining `nw` cores are worker datapaths.  The
normal ECI generation path uses `nw = 4`.

Common plugins, in elaboration order:

- `PatchSignalNames`: post-elaboration IO cleanup for generated top-level port
  names and Vivado interface inference.
- `DebugPlugin`: exposes selected internal signals at the top level for ILA or
  debug wiring.
- `GlobalCSRPlugin`: global status/control registers, including magic,
  git-version, and cycle counter CSRs.
- `TracePlugin`: allocates Lauberhorn event IDs and trace ports while the other
  plugins elaborate.
- `XilinxCmacPlugin`: CMAC-facing AXI-Stream RX/TX interface, frame-length
  handling, clock crossings, and MAC statistics/control.
- RX decoder pipeline: `EthernetDecoder`, `IpDecoder`, `UdpDecoder`,
  `OncRpcCallDecoder`, and `DecoderSink`.
- TX encoder pipeline: `EthernetEncoder`, `IpEncoder`, `UdpEncoder`,
  `OncRpcReplyEncoder`, and `EncoderSource`.
- `Scheduler`: maps decoded ONC-RPC calls to worker cores and drives
  preemption decisions.
- `BypassCmdSink`: arbitrates commands for the bypass datapath.
- `DmaControlPlugin`: converts decoded packet descriptors and host requests
  into packet-buffer DMA operations.
- `PacketBuffer`: AXI DMA engine plus RX/TX packet-buffer RAM.

ECI-specific plugins:

- `EciInterfacePlugin`: connects DCS interfaces, packet data, host control
  registers, interrupt commands, scheduler outputs, and per-core protocols.
- `EciThreadClRouter`: routes and translates CPU thread cache-line traffic to
  the backing protocol state machines.
- `EciDecoupledRxTxProtocol`: one per datapath, including bypass core 0; this
  is the per-core ECI PIO protocol state machine.
- `EciPreemptionControlPlugin`: one per worker core; exposes preemption control
  cache lines and issues interrupt/preemption control state.

PCIe-specific plugins still exist for the legacy target:

- `PcieBridgeInterfacePlugin`
- `PcieDatapathPlugin`, one per datapath
- `PciePreemptionControlPlugin`, one per worker core

## Elaboration Flow

The main RTL generation entry point is `lauberhorn.GenEngineVerilog`, invoked by
Mill targets in `build.mill`.

1. `mill eci.generateVerilog` runs `GenEngineVerilog --name eci --outDir ...`.
2. `GenEngineVerilog.engine(4, "eci")` initializes the global database, creates
   `NicEngine`, installs the common plugins, then installs the ECI plugin set.
3. `Config.spinal(outDir).generateVerilog` elaborates SpinalHDL into
   `NicEngine.v`.  The Spinal config also sets synchronous active-high resets,
   200 MHz default clock metadata, XPM memory blackboxing, and std-logic-vector
   top-level IO.
4. After elaborating `NicEngine`, the generator writes the register/device
   collateral from the same database: Mackerel device files, register base C
   headers, and `config.h`.
5. The generator merges non-top generated RTL/IP support into
   `NicEngine_ips.sv`, emits `NicEngine.xdc`, and, for the ECI target,
   separately elaborates `LauberhornTraceDma` as `lauberhorn_trace_dma.v`.
   The trace DMA elaboration uses the final `TracePlugin` port count and event
   list, then writes `lauberhorn_trace_dma_map.json`.
6. `mill eci.vivadoProject` calls `vivado/eci/create_project.tcl` with the
   generated RTL and XDC.  The Tcl project adds ECI toolkit VHDL, DCS
   SystemVerilog, local VHDL/SystemVerilog/Verilog glue, third-party AXI/AXIS
   Verilog, generated SpinalHDL RTL, include directories, constraints, and
   Vivado IP/block-design products.
7. `mill eci.generateBitstream` first builds the Vivado project, downloads the
   configured ECI static-shell checkpoint, then runs
   `vivado/eci/create_bitstream.tcl` to produce the routed checkpoint,
   bitstream, and probes file.

The Nix package `.#genVerilog` runs the same `eci.generateVerilog` flow offline
with the locked Mill dependency set and installs the generated RTL, headers, and
device descriptions as Nix outputs.
