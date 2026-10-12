# FPGA architecture: agent map

Scope: current source tree, inspected 2026-09-08. Source paths below are relative to the repository root. This is a navigation guide, not a claim of measured performance. See [implementation status](implementation-status.md) before making capability claims.

## Composition and boundaries

`hw/src/lauberhorn/NicEngine.scala` owns the SpinalHDL database/plugin host. `GenEngineVerilog.scala` is the authoritative composition: common network/scheduler/buffer plugins plus ECI or PCIe host plugins. Normal generation calls `engine(4, name)`: four worker datapaths plus bypass datapath 0. These logical datapath IDs are distinct from physical CPU IDs (ThunderX IDs 0–47).

| Boundary | Source and responsibility |
| --- | --- |
| Ethernet ↔ engine | `hw/src/lauberhorn/MacInterface.scala`: Xilinx CMAC AXI-Stream, frame buffering/lengths, clock crossings, statistics |
| Network parsing/serialization | `hw/src/lauberhorn/net/{Decoder,Encoder,ProtoState}.scala`; protocol-specific directories |
| Packet storage | `hw/src/lauberhorn/{DmaControlPlugin,PacketBuffer,PacketAlloc}.scala`: descriptors, allocation, AXI DMA, FPGA RAM |
| Request dispatch | `hw/src/lauberhorn/Scheduler.scala`: process/core state and worker requests; `host/BypassCmdSink.scala`: bypass arbitration |
| Host protocol abstraction | `hw/src/lauberhorn/host/{DatapathService,DatapathPlugin,DeschedulingService}.scala` |
| Coherent CPU link | `hw/src/lauberhorn/host/eci/`: ECI routing, per-worker 2F2F RX/TX, preemption |
| Physical ECI integration | `vivado/eci/rtl/lauberhorn_eci.vhd`: NicEngine, even/odd DCS, ECI transport, interrupts, CMAC, trace DMA/DDR |

## Packet paths

See [packet processing](packet-processing.md) for ingress/egress stages, metadata/payload ownership and bypass behavior. Both ONC-RPC call and reply paths are installed.

## ECI protocol and routing

`EciInterfacePlugin.scala` accepts both DCS slices, removes ECI aliasing and passes original addresses through `EciAxiIngress`. `EciAddressDecoder` identifies the physical worker and lifetime domain code. `EciOwnership` authorizes fresh accesses against the installed owner. Accepted transactions retain their original addresses through responses and coherence completion; changing owners cannot retag them. Old RX writebacks continue through the independent sink.

`EciDecoupledRxTxProtocol.scala` implements alternating control lines and twelve overflow lines per direction. The selected 8 KiB layout places RX controls at `0x300/0x380`, TX controls at `0x1300/0x1380`, and overflow at `0x400/0x1400`. `EciAddressMap` and the generated C helper encode color-neutral 19-bit lifetime codes separately from worker identities.

Transport version 3 uses the control CL upper half only for complete
payloads up to 64 bytes. Larger RX payloads start at overflow byte zero, including
reconstructed RPC prefixes and bypass headers. TX uses explicit inline placement
(bit 23), allowing a short commit from a large reservation. Normal invalidation
uses actual length; byte 56 is reserved. Inline bytes take priority over overflow
writebacks during commit. Full invalidation remains at preemption. Mutable RX writebacks are retained privately for replay until
retirement. See [RX writeback](rx-writeback.md) and the
[runtime ownership contract](../software/runtime.md#request-and-transport-ownership).

`EciDeschedulingControlPlugin.scala` delivers payload-free DESCHED through idle RX. A cooperative kernel info read acknowledges relinquishment and waits for autonomous transport cleanup. Only initial/idle activation sends a scheduling IRQ. See the [control contract](../software/scheduling-control-interface.md) and [implementation evidence](eci-implementation.md); board acceptance remains outstanding.

## Defaults and invariants

`Global.scala` sets a 64-byte network datapath width, maximum Ethernet frame 1518 bytes, 16 KiB RX budget per datapath, and one rounded-MTU TX buffer per datapath. `PacketBuffer.scala` uses 512-bit AXI dual-port RAM; its DMA moves data inside the FPGA packet store, not a conventional host-DRAM RX descriptor ring. Default capacities: 8 services, 16 processes, 16 sessions, 8 neighbors, 16 listening ports, 16 queued RX packets/process; these are prototype limits.

- `DecoderSink` serializes packet admission until payload acknowledgment to keep metadata and payload matched. Independent round-robin arbitration can mismatch packets or deadlock. Refactoring requires a deliberate metadata/payload association scheme.
- RX allocations are released through host acknowledgment. Follow every error/drop/preemption path through allocation ownership.
- Lifetime domain codes are monotonic until a full coherence reset. Worker reuse follows final control invalidation and completion.
- `Config.scala` specifies synchronous active-high reset, XPM memory blackboxing, and 200 MHz default clock metadata. Do not infer achieved timing from metadata or older 250 MHz comments.
- Generated register/device/C-header collateral comes from the elaborated database. Edit Scala/register definitions, regenerate, and consume matching headers; do not patch generated ABI constants independently.

## Source languages and plugin support

Main engine logic and tests are Scala/SpinalHDL under `hw/`. The ECI Vivado top is VHDL (`vivado/eci/rtl/lauberhorn_eci.vhd`); DCS and CDC/AXI glue include SystemVerilog, while generated `NicEngine.v`, `lauberhorn_trace_dma.v`, and imported AXI/AXIS modules use Verilog. Generated support RTL is merged into `NicEngine_ips.sv`. Vivado Tcl assembles the mixed-language project.

Besides the data-path plugins listed above, `GenEngineVerilog.scala` installs `PatchSignalNames` (IO naming/Vivado inference), `DebugPlugin` (debug signals), `GlobalCSRPlugin` (magic/version/cycles), and `TracePlugin` (event IDs and ports). `EncoderSource`/`DecoderSink` connect protocol plugins to DMA. ECI installs one preemption plugin per worker, none for bypass. Legacy PCIe installs `PcieBridgeInterfacePlugin`, per-datapath `PcieDatapathPlugin`, and per-worker `PcieDeschedulingControlPlugin`; the last is unfinished, so this composition is not evidence of a working current PCIe build.

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
6. The Nix-built bundle's `vivado/eci/ci_build.tcl` calls `create_project.tcl` with the
   generated RTL and XDC.  The Tcl project adds ECI toolkit VHDL, DCS
   SystemVerilog, local VHDL/SystemVerilog/Verilog glue, third-party AXI/AXIS
   Verilog, generated SpinalHDL RTL, include directories, constraints, and
   Vivado IP/block-design products.
7. In build mode, the same entry point uses the static-shell checkpoint pinned
   and fetched by Nix, then runs `vivado/eci/create_bitstream.tcl` to produce the routed checkpoint,
   bitstream, and probes file.

The Nix package `.#genVerilog` runs the same `eci.generateVerilog` flow offline
with the locked Mill dependency set and installs the generated RTL, headers, and
device descriptions as Nix outputs.

For build prerequisites and command selection, see [development workflow](../development/workflow.md). For trace capture/decoding, see [tracing](../development/tracing.md).
