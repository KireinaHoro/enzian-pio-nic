# DPDK microbenchmark baseline

The DPDK baseline compares Lauberhorn's FPGA/coherent RPC path with CPU-side
packet processing on an Enzian ThunderX and ConnectX-5. Its chart label is
**DPDK**. It uses the same echo workload and shared synchronous RPC handler path
as LH, with networking supplied by the mlx5 PMD. This comparison measures the
systems with their respective NICs and transports; it does not isolate one
hardware offload or coherence mechanism by itself.

[CS-A64](caladan-enzian.md) is the separate Caladan simple-policy/directpath
baseline for runtime-managed core allocation and preemption. The DPDK responder
uses configured polling workers and does not establish Caladan scheduling or
interference-control behavior. The DPDK traffic generator is a separate client
role, shared across systems; its NICVF calibration is not the server baseline.

## Workload and comparable software

The active microbenchmark uses schema-defined 8/128/1432-byte echo bodies from
`../microbenchmarks/bench.x`. LH and DPDK share the synchronous runtime request,
handler and reply interfaces, the `oncrpc.c` XDR adapter, generated codecs and
separately compiled `echo_handler.c`. Both allocate/decode arguments, dispatch
the handler, marshal directly into the TX buffer and free decoded arguments.
DPDK supplies the program/version/procedure table and Ethernet/IP/UDP/RPC
envelope processing beneath those interfaces.

The DPDK server loop requests one RX packet in both loaded and unloaded modes.
Profiling, output and termination differ between acquisitions; generator batching
does not select a different server implementation. Responder/runtime/codec builds
use `-O2` and the same Enzian CPU tuning. Pin source, binaries, compiler options,
worker placement and instrumentation in each comparison. Transport or codec
changes require a new matched baseline rather than reuse of an older workload.

## mlx5 transport

The selected CPU-NIC device is ConnectX-5. The configured baseline host is
zuestoll07; verify actual inventory and reservations before acquisition.
`nix/software/mlx5` builds separate instrumented and compiled-out PMDs. The
benchmark image includes both responder variants and the generator, plus NICVF
compatibility support for a ThunderX client. NICVF results must retain their
own NIC identity.

The shared runner keeps mlx5 bound to `mlx5_core`/`mlx5_ib`, using the bifurcated
PMD rather than VFIO. It records placement, kernel/device identity, binary hash
and EAL arguments, and restores link/resource controls. Both instrumentation
modes select the scalar queue path:
`rx_vec_en=0,mprq_en=0,rxq_cqe_comp_en=0,txq_mpw_en=0`.
Vector, compressed-CQE and MPRQ performance is outside this selected baseline.

## Measurement contract

Unloaded CDFs use compiled-out software probes and Tofino server-facing
wire-to-wire intervals. Separate instrumented runs provide CPU components and
one wire-minus-CPU residual. Loaded runs use compiled-out probes and client RTT,
without switch capture, FPGA readout or NIC PTP. See the common
[hot measurement methodology](hot-measurement.md) for exact boundaries and
acceptance rules. Neither mlx5 hardware completion timestamps nor subtraction
of timestamps from different clocks is part of this method.

## Software probes

`queue-probes.patch` instruments the actual scalar mlx5 RX path, with a
thread-local bounded scratch record. It records successful valid-CQE
observation and RX return after queue bookkeeping/refill. Empty polls supply no
request observation. Compressed/vector/MPRQ RX remains outside this selected
measurement path.

The TX probes record adapter submission entry, PMD entry and the point immediately
before the UAR MMIO write inside `mlx5_doorbell_ring_traced`. Descriptor/WQE
construction, reclamation, doorbell-record store and ordering barriers precede
that endpoint. The MMIO write, optional flush and return follow it. The maintained
Nix patch is applied to both instrumented and off variants; the helper's optional
hook is NULL in the off variant. Caladan uses its own directpath source, with
the equivalent endpoint before `mmio_write64_be`.

The application records network processing and shared RPC/handler boundaries.
Versions 4 and 5 partition valid RX observation through pre-MMIO notification, then
joins the switch MAC duration to obtain the combined NIC–wire remainder.
LH uses its pre-ECI-read endpoint. No clock is taken on TX return for the current
breakdown. See the [complete instrumentation contract](../development/rpc-breakdown.md).

## Reproduction and acceptance

Build the [DPDK baseline image](../development/workflow.md#dpdk-microbenchmark-baseline-images),
then use [board validation](../development/validation.md#dpdk-baseline-readiness)
for the bounded functional/probe acceptance matrix. The short matrix shares a
board between local NICVF generator and mlx5 responders; it establishes neither
saturation capacity nor full unloaded wire latency. Full measurement campaigns
use a separate generator machine and the
[benchmark controller](../development/benchmark-campaign.md).

Full unloaded collection requires hash-bound real-workload wire acceptance;
local tests or a successful readiness matrix cannot satisfy that gate. Missing
wire durations are excluded from paper CDF/breakdown inputs. The
[recorded acceptance evidence](../../agents/measurements.md#october-16-dpdk-baseline-readiness)
retains the short matrix's scope, artifact hashes and software interval results.
