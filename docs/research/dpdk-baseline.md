# DPDK microbenchmark baseline

The DPDK baseline compares Lauberhorn's FPGA/coherent RPC path with CPU-side
packet processing on an Enzian ThunderX and ConnectX-5. Its chart label is
**DPDK**. It uses the same echo workload and shared synchronous RPC handler path
as LH, with networking supplied by the mlx5 PMD. This comparison measures the
systems with their respective NICs and transports; it does not isolate one
hardware offload or coherence mechanism by itself.

[SH-A64](caladan-enzian.md) is the separate Caladan simple-policy/directpath
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
thread-local bounded scratch record. It records entry to the productive poll,
valid CQE observation, and return after mbuf preparation, CQ/RQ pointer updates
and doorbells. Empty polls contribute no request observation. Preparation before
CQE observation is retained separately; it is not part of the CQE-to-packet
interval or silently added to the CPU–wire residual. Compressed/vector/MPRQ RX
is outside this measured path and fails the responder's probe coverage check.

TX submission starts when the constructed network packet is ready, including
mbuf trimming/metadata preparation before entering the PMD. The PMD records
entry, return from the SQ doorbell operation, and return after
post-doorbell software bookkeeping. SQ construction, ordering, CQ reclamation
and mbuf ownership work on that invocation remain included. The application
records network parsing/construction, RPC receive/transmit and echo-handler
boundaries. The importer partitions CQE observation through TX PMD return,
with post-doorbell and other uncovered CPU work in `cpu_other`. It conserves
that duration before any external wire join. No timestamps from different clocks
are subtracted, and no mlx5 hardware completion timestamps are used.

Lauberhorn records both sides of the TX doorbell read directly; the return
probe includes a memory-ordering fence before sampling the CPU clock. Transport
cleanup after its return remains other CPU time. The uninstrumented compile
path passes null probes and eliminates these clock calls.

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
