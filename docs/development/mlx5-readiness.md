# ConnectX-5 hot echo readiness

The selected CPU-NIC baseline uses zuestoll07, PCI `0005:90:00.0`, netdev
`enP5p144s0np0`, MAC `b8:ce:f6:87:c2:9e`. Check EMG reservation and actual
inventory before use. The observed kernel is `6.8.0-146-generic`, firmware
`16.35.8002`, and link speed 100 Gb/s. These are inventory observations, not
benchmark acceptance.

Build the maintained products:

```
nix build .#dev.x86_64-linux.software.mlx5BenchmarkNative -o out/mlx5-native
nix build .#mlx5BenchmarkImage -o out/mlx5-image
```

`nix/software/mlx5` builds DPDK with separate instrumented and compiled-out PMDs.
The shared `traffic-generator.nix` image packages both responder binaries and
the generator. The image also includes the existing Linux NICVF compatibility
patch for a ThunderX traffic peer; the CPU-NIC baseline itself is mlx5.

`tools/enzian/dpdk_run.py --interface enP5p144s0np0` keeps mlx5 bound to
`mlx5_core`/`mlx5_ib`; it never binds a Mellanox device to VFIO. It records kernel,
PCI/interface, binary hash, placement and EAL arguments, applies best-effort
isolation, and restores link/resource controls. The same runner retains VFIO
handling for a NICVF traffic peer. Hardware timestamp setup is absent.
Both instrumentation modes use the same scalar queue settings:
`rx_vec_en=0,mprq_en=0,rxq_cqe_comp_en=0,txq_mpw_en=0`.

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

## Short board acceptance

Use the [deployment skill](../../tools/enzian/skills/enzian-deploy/SKILL.md),
`tools/enzian/run.py`, and the hash-verified private image launcher in
`tools/enzian/image.py`. Stage the image and helper through the existing transfer
service; the host interpreter only verifies and enters the image.

Inside the image, run `tools/paper/campaign/mlx5_readiness.py` with an unused
server IP, the peer's actual source IP and both explicit MAC addresses. This
maintained helper uses the canonical runner for every role and produces a fresh
output directory. It exercises 8/128/1432-byte echo, instrumented and
uninstrumented single-outstanding calls, and short aggregate loaded traffic with
one/four responders. The local NICVF generator occupies CPUs 16–19 and mlx5
responders CPUs 44–47. Shared hugepages are reserved before either role starts
and restored after both finish. This is functional readiness, not a throughput
or saturation result. Full campaigns require a separate generator machine.

Archive the helper, command manifest, image hash, per-role outputs and
restoration summaries. Import unloaded observations through
`campaign.import_unloaded.dpdk`; absent Tofino data produces `wire_ns: null`,
`wire_available: false`, `paper_inputs: false`. Import loaded observations through
the ordinary aggregate validator; validate actual worker activity and full
payload replies. Paper CDF/breakdown validation rejects missing wire durations.
The campaign launch gate remains closed until Tofino integration is validated.
Caladan-simple acceptance still requires its separate ARM/directpath port.

## Validated short acceptance — October 16, 2026

The twelve-case zuestoll07 matrix passed: 256 measured + 16 warmup requests
for each unloaded payload/mode, and 25,000 measured loaded requests per
payload/worker configuration (100 kRPC/s, 50 ms warmup + 250 ms measurement).
All 150,000 measured loaded requests returned on time, with zero generator
misses or TX admission failures. Each four-worker case recorded 6,250 measured
calls on each worker. All six unloaded cases completed their 256 measured calls,
with ordered queue probes in all three instrumented cases. Every role restored
its NIC/resources. Total case execution was 110.4 seconds, excluding staging.

The instrumented RX-queue medians were 200/200/210 ns and TX-submission medians
410/405/430 ns for 8/128/1432 bytes. These include probe overhead and only
establish working software interval capture; they are not unloaded wire latency
or full-dataset results. No Tofino durations or Caladan board acceptance exist.

Local checks passed: 51 campaign unit tests, 16 schema packet replays, seven
open-loop replay scenarios, native wire/schema/aggregate/probe-state checks,
and runtime regressions including compiled-out tracing and sanitizer checks.

Evidence: `out/mlx5-readiness/acceptance.json`, `validated-v2.json`, and the complete
`mlx5-ready-01.tar.gz` role archive. Image SHA256: `61367fc00f65379416de0168776a6bd74653afdf4b930a5876d8e523d1107dfb`.
Archive SHA256: `401ecdd1a2abbb52c15fc335aad58070c18290d960e3824b1dacc772e559f324`.
The archive manifest records both PMD package paths and each role's executable
hash, arguments, raw observations, NIC counters and restoration results.
