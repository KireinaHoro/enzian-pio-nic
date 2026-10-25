# AArch64 VM and RTL co-simulation

## Goal and status

Proposed validation route: run real AArch64 Linux, the production ECI kernel
module, runtime and applications against the production Spinal-generated NIC
RTL. After a sweep of all RTL simulation tests, migrate every test that emulates
software behavior in a Scala testbench to this co-simulation route. Preserve
the coverage of each migrated case and retire its superseded software model.
This document proposes the architecture and migration criteria; it does not
implement the bridge or establish VM acceptance.

The intended constraints are stock QEMU and an unmodified ECI kernel module and
runtime, including direct-memory 2F2F operations. The ioctl-based software VM
backend is not the transport for this route. Do not silently relax either
constraint if platform compatibility requires changes: resolve that design
decision before migration.

The software VM work on `userspace-tcp-phase-three` supplies useful NixOS guest,
network-peer and application acceptance patterns. Its device emulator supplies
neither the NIC behavior nor the scheduling policy for this proposal. Features
implemented only by that emulator, such as its native TCP transport, do not
become RTL-supported merely by moving the tests into a VM.

## Existing simulation boundary

[NicSim.scala](../../hw/test/src/lauberhorn/host/eci/NicSim.scala) elaborates the
production ECI engine and connects AXI-Lite control, even/odd DCS interfaces,
interrupts and CMAC streams. Its
[GenericHostCPUModel](../../hw/test/src/lauberhorn/sim/GenericHostCPUModel.scala)
reimplements domain registration, worker assignment and interrupt-side software
behavior. ECI test helpers also synthesize descriptor accesses and 2F2F parity
transitions that production software should exercise itself.

The blocks dependency's `DcsAppMaster` and `DcsStateMachineSim`, under
`deps/blocks/tester/src/jsteward/blocks/eci/sim/`, model cache-line state,
writeback and invalidation responses at the DCS application boundary. These are
candidate reusable hardware-environment models; they are not substitutes for
running the production software. The full physical DCS/ThunderX fabric remains
outside this simulation boundary.

## Proposed architecture

```text
AArch64 Linux guest
  applications → production runtime → ECI mappings / kernel control
                    │ loads, stores, register accesses
                    ▼
Stock QEMU external-device frontend
                    │ forwarded accesses / interrupt notifications
                    ▼
External bridge + modeled CPU-side cache / DCS environment
                    │ AXI-Lite, DCS transactions, LCI / LCIA
                    ▼
SpinalSim production ECI NIC RTL
                    │ CMAC Ethernet streams
                    ▼
Network peer / packet stimulus and independent checkers
```

The bridge transports operations and models the host-side hardware environment.
It must not decide which domain receives a worker, synthesize successful
activation, implement RPC contexts, or bypass ownership checks. Guest software
programs actual RTL registers; RTL makes allocation and packet-processing
decisions. RTL interrupt requests reach the guest interrupt path, replacing
Scala callbacks that directly perform kernel scheduling work. Keep datapath
IDs, guest CPU IDs, domain identities and registered worker identities distinct.

Use the production engine composition and matched generated headers. Nix should
pin QEMU, the guest kernel, module/runtime, simulator and source dependencies;
Mill remains the source-defined RTL elaboration/test entry point. Reuse the
existing guest orchestration where practical without retaining its C device
emulator underneath the ECI software.

### External device without a QEMU patch

The leading candidate is QEMU's
[`vfio-user-pci` frontend](https://www.qemu.org/docs/master/system/devices/vfio-user.html).
It supports an arbitrary PCI device implemented in an external process.
For regions without a direct host mapping, the
[vfio-user protocol](https://www.qemu.org/docs/master/interop/vfio-user.html)
forwards guest reads/writes to that process and provides interrupt signaling.
This is a candidate transport for ECI semantics, not a switch to Lauberhorn's
unfinished PCIe datapath. Availability in the selected Nix QEMU build and its
AArch64 configuration is a feasibility gate.

The coherent aperture must trap accesses into the external cache model rather
than expose directly mapped shared RAM. A shared-memory/virtio region alone
does not notify the bridge of every load or allow it to govern line ownership.
Guest page attributes and QEMU's host-side backing mechanism are separate:
preserve the attributes used by the ECI driver while checking that accesses
still reach the frontend. Start by evaluating AArch64 TCG; KVM is a separate
compatibility question, not a prerequisite.

The frontend being PCI-shaped does not establish compatibility with the ECI
platform. The current driver maps fixed shell and FPGA physical addresses in
[misc.c](../../sw/kmod/misc.c) and
[virt_node1.h](../../sw/kmod/virt_node1.h), uses platform interrupt paths, and
executes ThunderX-specific cache maintenance through
[common.h](../../sw/kmod/common.h). Establish whether stock machine/firmware
configuration can supply these requirements. A BAR at an arbitrary address or
a replacement guest virtio transport does not meet the unmodified-driver goal.

### Cache and transaction semantics

Model 128-byte ECI lines, fills, dirty stores, writebacks and RTL-originated
invalidations. Repeated guest accesses hit modeled cached contents; requests to
the alternate 2F2F line drive actual RTL protocol transitions. An invalidation
must collect required dirty data and complete its modeled ownership transition
before acknowledgement. Preserve address aliases, domain checks, access widths,
byte enables and byte order. Avoid implementing descriptor or parity logic in
the bridge that belongs in the guest runtime.

Audit actual guest access sequences, including split/vector accesses, barriers,
exclusive accesses if used, and cache-maintenance instructions. vfio-user
memory transactions do not themselves transport CPU barrier or system-register
instructions. Determine which semantics stock QEMU supplies and which required
events the external model cannot observe. Do not claim architecture-level
ordering coverage from a backend that simply serializes all requests.

Allow independent outstanding requests, asynchronous invalidations and
interrupts, and simulator progress while a guest access is pending. In
particular, a blocked RX must not prevent another vCPU's TX or a dirty writeback
needed to complete that RX. Audit frontend locking and request handling as
well as the bridge; a concurrent backend alone cannot fix frontend serialization.
Simulator signal access belongs on the simulation thread, with explicit queues
between external I/O and simulation execution.

### Time, networking and diagnostics

Define how guest virtual time and RTL cycles advance, including periods when
vCPUs block. DEADLINE and RX timeout tests require one documented time mapping;
wall-clock delays on the host must not change their meaning. QEMU
[`icount`](https://www.qemu.org/docs/master/devel/tcg-icount.html) is an option to
evaluate for TCG coordination, not a cycle-accurate CPU model. Deterministic
stimulus does not by itself establish deterministic multi-vCPU execution.

Feed complete Ethernet frames through CMAC streams so that RTL parsing,
classification, buffering and encoding remain exercised. Drive guest bypass
traffic through the same device path. A separate management NIC can support
guest orchestration; test traffic must not accidentally use it to bypass RTL.

Record source/configuration hashes, guest artifacts, random seeds, transaction
ordering and time mapping with compact pass/fail summaries. On a bounded
timeout, retain guest state/logs and RTL/bridge traces before destroying the VM.
Use independent expected results and protocol assertions; do not make the
bridge's own state the sole oracle for its correctness.

## Full test sweep and migration contract

Before migration, enumerate all RTL test cases, including shared factories,
parameterized cases, ECI and PCIe suites, and dependency fixtures used by the
platform. Inspect behavior rather than class names: software emulation can be
hidden in register helpers, interrupt callbacks or descriptor constructors.
Record the inventory and coverage mapping with the maintained tests when
implementation starts; this proposal is not a completed inventory.

For each case record its stimuli, assertions, parameters/seeds, simulated
software responsibilities, RTL boundary, fault injections and disposition:

| Case behavior | Disposition |
| --- | --- |
| Scala acts as kernel, runtime, handler or application | Migrate to real guest software against RTL. |
| Pure RTL unit test or bus/cache environment model | Retain as a focused simulation test. |
| Mixed software scenario and low-level fault injection | Move software execution into the guest; retain bridge/environment injection and independent RTL assertions. |
| Invalid transaction that production software cannot issue | Keep a direct adversarial hardware test; pair with a guest rejection/isolation case where relevant. |
| Unsupported feature or platform-specific path | Record the dependency and keep existing coverage until an equivalent route exists; never silently drop the case. |

Migration covers registration, allocation/reclaim, cooperative yield, RX/TX
ownership, inline/overflow payloads, bypass, native UDP, ONC-RPC and nested
calls wherever the sweep finds software modeled in Scala. This is a starting
taxonomy, not an exhaustive suite list. PCIe tests must be reviewed explicitly;
an ECI replacement cannot be counted as preserving a PCIe-specific assertion.

Each replacement must exercise the original behavior with the actual software
path and preserve its assertion strength, including negative cases, boundary
sizes, resource exhaustion and fault schedules. Where real software cannot
reach an internal state used by an old test, separate the hardware invariant
from the software scenario rather than weakening the invariant. Low-level
cache evictions, delayed acknowledgements and backpressure remain legitimate
hardware-environment stimuli.

Run old and replacement cases during transition, investigate mismatches, and
retire the old software-emulating case only after its coverage mapping and
replacement pass. Remove unused Scala software models and helpers once their
last consumers migrate. The end state has no Scala stand-in for production
software in applicable integration tests; retained Scala tests explain their
hardware-only or adversarial purpose. Do not maintain two parallel application
or scheduling implementations as the final validation architecture.

## Feasibility gates and rollout

1. **Platform contract:** inventory driver/runtime platform dependencies and
   complete the test sweep. Demonstrate stock QEMU's external frontend on
   AArch64 with the required memory attributes, addresses and access sequences.
   Resolve interrupt routing and ThunderX instruction behavior before declaring
   the unmodified ECI module supported.
2. **Minimal real path:** load the production module, pass version/ABI probes,
   and complete one real 2F2F RX/TX exchange through RTL. Include dirty writeback
   and invalidation evidence; a register read alone is insufficient.
3. **Concurrent progress:** run multiple vCPUs with blocked RX, concurrent TX,
   delayed invalidations and interrupts. Establish bounded progress and the
   virtual-time contract before deadline or scheduling acceptance.
4. **Coverage migration:** port the inventoried cases incrementally, starting
   with native UDP and bypass, then domain handoff, multi-worker contention,
   RPC/nested calls and supported timers. Reuse application fixtures rather
   than recreating handlers in the bridge. Keep runtime practical with focused
   cases, bounded traffic and reusable built artifacts.
5. **Completion:** every inventoried software-emulating case has an accepted
   replacement or an explicitly resolved scope decision; replacement checks
   run in the supported Nix/Mill workflow; obsolete models are removed and the
   [validation guide](../development/validation.md) documents the new route.

## Fidelity and unresolved decisions

This route validates real AArch64 software instructions, mappings and ABI
interactions with production NIC RTL under a modeled host cache/DCS environment.
It does not establish ThunderX cache replacement, weak-memory behavior,
physical ECI credit/progress behavior, FPGA timing closure, or hardware RPC
latency. Keep focused RTL/formal checks and Enzian acceptance for their
respective boundaries; consult the
[ECI progress discussion](../research/eci-progress.md).

The decisive open questions are whether stock QEMU can expose the required
fixed platform layout and interrupt semantics; whether necessary CPU-specific
operations can execute with correct effects; whether its external frontend
allows the required concurrent progress; and how guest/RTL time is coordinated.
If any blocks the two primary constraints, document the precise incompatibility
and choose a revised design explicitly before removing tests. A guest transport
rewrite, ignored cache-maintenance operation or QEMU patch is not an implicit
fallback for this proposal.
