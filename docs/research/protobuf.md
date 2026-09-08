# Protobuf student decoder

Surveyed 2026-09-08. External workspace `../elena-protobuf/`, relative to the platform root. Report: **Elena von Känel, _Unmarshaling unit for the Lauberhorn smart NIC_**, bachelor thesis 606b, February–August 2026, supervised by Pengcheng Xu and Timothy Roscoe. Inspected report `1e4e907`, platform `7a202c3`, spinal-blocks `98870e3`, standalone Protoacc source `8cd3aa596`. The platform gitlink pins `deps/blocks` to `526ee1b4da729fa6394330c6725a784254df2abb`, but that submodule directory is empty here; the separately inspected spinal-blocks checkout is a different revision. Reconcile these before reproducing builds. Its black box loads the tracked `spinal-blocks/deps/protoacc-ae/rtl/generated.v` resource.

Maintainer priority: preserve and integrate this finished student project soon to limit bit rot, **below Dandelion and the other first-full-system-paper applications**. Together with the TCP project, it supports a possible later paper on fully offloaded gRPC. This artifact implements UDP-fed protobuf receive decoding, not a complete gRPC stack. No code has been merged by this documentation survey.

## Source entry map

Paths below are relative to `../elena-protobuf/`; these siblings are not vendored here.

| Concern | Entry point |
| --- | --- |
| Design, evaluation, limitations, artifact instructions | `report/main.tex`, chapters 4–6 and artifact appendix |
| Streaming decoder, ADT CSRs, allocation/windowing | `platform/hw/src/lauberhorn/net/protoacc/ProtoaccDecoder.scala` |
| Memory-producing decoder interface | `platform/hw/src/lauberhorn/net/DecoderMem.scala`, `net/package.scala` |
| Buffer allocation and direct writes | `platform/hw/src/lauberhorn/{PacketAllocService,PacketBuffer,PacketBufferMem,DmaControlPlugin}.scala` |
| Routing and host ABI | `platform/hw/src/lauberhorn/net/udp/UdpDecoder.scala`, `Scheduler.scala`, `host/package.scala`, `host/eci/EciHostRxCtrlInfo.scala` |
| Standalone Protoacc black box and wrapper | `spinal-blocks/blocks/src/jsteward/blocks/protoacc/{ProtoAccelStandAlone,InterfaceWrapped}.scala` |
| Standalone generation and modified compiler | `protoacc/README.md`; `protobuf-library-for-accel-ae/` (report-listed modified protoc artifact; absent from this local workspace, must recover for ADT generation) |
| Correctness and host reconstruction model | `platform/hw/test/src/lauberhorn/host/eci/ProtoaccSim.scala`; `spinal-blocks/blocks/test/src/jsteward/blocks/protoacc/` |
| Evaluation | `platform/hw/test/src/lauberhorn/host/eci/ProtoaccEvaluationSim.scala`, `platform/scripts/{run-protoacc-evaluation.sh,aggregate-protoacc-evaluation.py}`, `platform/evaluation-results/` |

## Design and ABI

- Protoacc's Chisel deserializer is made standalone by removing its Rocket/RoCC and TileLink/TLB dependencies. Explicit streams carry input, ADT reads, presence-bit reads/writes and fixed writes. A Spinal black box integrates the emitted RTL. The controller issues arena setup, parse-info, parse and completion commands.
- `GenEngineVerilog` instantiates `ProtoaccDecoder`; UDP listen configuration selects `UdpNextProto.protoacc`. Incoming 512-bit payload beats become 128-bit chunks. This receives raw protobuf over UDP alongside the existing ONC RPC path; HTTP/2 framing and gRPC message/method handling are absent.
- A single host-programmed ADT (64 × 64-bit words), ADT base and PID register configure the decoder. ADTs encode field types, destination offsets and nested layouts. The implementation fixes minimum field number to 1. This is not per-connection/per-method schema lookup or a multi-tenant admission policy.
- Output follows Protoacc's object/arena representation, including presence bits and synthetic pointer values. Regions begin at `0x0000` (destination), `0x4000` (fixed arena) and `0x8000` (array arena). Each has up to 16 windows of 1 KiB, mapped on demand into packet-buffer slots. Scalars, strings, repeated fields and nested objects require the matching ADT layout; the output is not automatically a directly usable native C/C++ protobuf object.
- `PacketAllocService` arbitrates allocation with DMA control, and a new packet-buffer AXI master writes decoded data directly. `DecoderMem` forwards metadata without a downstream payload. DMA control skips ordinary allocation/copy for this type.
- Each allocated slot generates a `protoacc` host request with PID, slot descriptor, length (1 KiB), region (2 bits), slot index (4 bits) and last-slot flag. Scheduler routing uses its PID. A message therefore spans multiple independently scheduled descriptors, rather than one atomic application request.
- The Scala test helper `putProtoaccVirtualMemoryBackTogether` collects descriptors across whichever worker is running, reads each slot through simulated ECI, exits each receive critical section, and copies bytes into a zero-filled 48-KiB array at `region * 0x4000 + index * 1024`. Test accessors interpret synthetic addresses. This is a reconstruction model, **not an implemented host runtime API**; the inspected platform delta has no `sw/` changes.

## Evidence and validation

The report evaluates 22 message shapes (scalars, strings, packed/unpacked repeated fields, nesting and multiple output slots) against a forwarding-only baseline in RTL simulation. At an assumed 200 MHz it reports 11–526 additional cycles for the full decoder path versus forwarding. These are simulation cycle costs; even 526 cycles is 2.63 microseconds, so do not generalize the report's concluding “tens to low hundreds of nanoseconds” wording to every case. Inter-message spacing gives estimated throughput, not measured NIC line rate.

The baseline does **no software unmarshaling**. Results establish neither end-to-end RPC speedup nor a CPU savings comparison. The report explicitly leaves Vivado synthesis, resource/timing measurements and full Enzian system integration to future work. Demikernel remains the full-system paper baseline; this evaluation does not provide it.

Reproduction commands in the sibling platform, after satisfying its build prerequisites:

```sh
./mill gen.test.testOnly lauberhorn.host.eci.ProtoaccSim
./scripts/run-protoacc-evaluation.sh
```

The script runs 22 cases in each of `protoacc` and `baseline` modes and aggregates CSVs; the report estimates about 30 minutes. Historical artifacts were inspected; no simulation, synthesis or hardware test was rerun for this survey.

## Porting gaps and decisions

- Port the decoder, memory-producer interface, allocation/AXI changes, metadata enums, scheduler and ECI changes together; regenerate matched descriptors/headers. Audit incidental ECI and submodule changes in the student branch rather than importing its entire delta blindly. Preserve the standalone RTL source/generation recipe and compatible spinal-blocks revision.
- Define the production host ABI: schema registration, synthetic-pointer access or relocation, slot ownership/freeing, completion and failure recovery. Decide how all fragments reach one application invocation when workers are isolated and descriptors can be scheduled separately. The current test's global collector is not that protocol.
- Establish bounded malformed-input behavior, ADT validation, output bounds and allocation-failure recovery before asserting isolation. Address/index truncation and fixed windows need explicit scrutiny; global ADT/PID state must not change beneath an active message. Test large, sparse and mixed layouts, stale buffer contents/presence bits and writes crossing boundaries. These are review requirements, not reproduced failures.
- Reconcile multi-slot receive critical sections with 2F2F progress, task timeout/kill and buffer reclamation. Existing simulation success does not establish OS security or recovery guarantees.
- The later gRPC direction still needs integration with TCP stream reassembly, HTTP/2 multiplexing/flow control and gRPC framing/method/schema selection; this project supplies unmarshaling, not those layers or a response serializer. Keep that work separate from the first full system's application/evaluation milestones; see the [gRPC integration roadmap](grpc-roadmap.md), [paper goals](paper-goals.md) and [roadmap](roadmap.md).
