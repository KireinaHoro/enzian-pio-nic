# Reusable polling traffic generator

`bench_dpdk` owns the DPDK port, one RX/TX queue, one pinned EAL lcore,
closed-loop request timing, warmup, bounded timeout, validation and buffered CSV.
`wire.h` owns Ethernet/IPv4/UDP framing and checksum/length validation.
`benchmark.h` defines the codec interface; `benchmarks.c` supplies `add`, `mul`
and transport-only `echo`. A new UDP microbenchmark adds encode/validate
callbacks and a registry entry; it does not copy the DPDK loop. Callbacks must
not allocate, perform I/O, or retain packet buffers. They receive a request ID
unique within the run, including warmup. Variable payloads up to 1472 bytes are
supported by the transport; fragmented IP and VLAN-tagged frames are unsupported.

This is a single-outstanding, closed-loop latency generator. It does not measure
open-loop queueing latency, saturation throughput or avoid coordinated omission
under offered load. Do not use its latency distribution as a loaded-server claim.

## Build and functional checks

From the repository root:

```sh
nix build .#trafficGeneratorNative    # local Linux
nix build .#trafficGenerator          # cross-compiled ThunderX/AArch64
nix develop -c make -C sw/apps/microbenchmarks/dpdk check
```

Both Nix packages include the ThunderX Linux PF receive-filter correction.
The cross package also supplies ThunderX T88 machine metadata and the target
archiver. Only the required libraries and ThunderX/pcap PMDs are built.
The native Enzian validation uses patched DPDK 23.11.4; Nix pins 26.03.
Preserve that version distinction in comparisons.

The Ubuntu stock PMD sent valid packets but received none on zuestoll07:
`SET_XCAST` applies the PF's CAM list but does not add its `mac` argument.
The [compatibility patch](../../../../nix/software/patches/dpdk-thunderx-linux-cam.patch)
sends `ADD_MCAST` first and uses Linux mailbox mode flags. This was confirmed by
10,100 physical gateway echo exchanges and NIC loopback after the patch.
Do not use an unpatched distribution PMD for measurements on this setup.

Build the client deployment image on the build host:

```sh
nix build .#trafficGeneratorDeployFs
rsync -L result enzian-gateway:/scratch/USER/CLIENT.img
```

The image contains the cross-built generator, pinned patched DPDK, Python,
runner, trace receiver, summary/replay tools and their runtime utilities. It
contains no compiler/toolchain. Do not build on the client or install Ubuntu
packages to satisfy benchmark dependencies. Provision the kernel and root with
`emg`; only kernel modules and privileged device access come from that host.

After checking existing mounts, mount the image read-only at `/nix/store` on
the client and use `/nix/store/lauberhorn-client/bin/lh-dpdk-run` (same arguments
as `dpdk_run.py`) with `/nix/store/lauberhorn-client/bin/bench_dpdk`. The wrappers
set an image-only utility PATH and clear inherited library/Python overrides.
`manifest.json` in that environment records package paths, DPDK version and
source hashes. Archive the image hash and manifest with each run.

Test actual DPDK packet I/O without a physical port:

```sh
python3 sw/apps/microbenchmarks/dpdk/replay_test.py \
  sw/apps/microbenchmarks/dpdk/bench_dpdk out/NEW-replay
```

The pcap PMD checks add/multiply, bad results, stale IDs and timeouts. Replay
numbers are functional-test timing, not network latency measurements.

## Run

Reserve a fresh Enzian and verify `cpu40g0` carries management/root storage.
Inspect the PCI function behind **cpu40g1**, its driver and IOMMU group before
binding only that VF to DPDK. Do not unbind its physical function or reset SR-IOV:
that could disrupt management VFs. Save interface addresses/routes, driver and
hugepage settings and restore them after testing. Kernel driver names must be
read from sysfs: the tested Ubuntu image calls it `nicvf`, not `thunder-nicvf`.

On the reserved client, the reusable runner snapshots the data VF and restores
its driver, addresses, routes, hugepages and VFIO mode even after a failed run:

```sh
sudo python3 tools/enzian/dpdk_run.py --logs NEW-run --lcore 6 \
  --no-iommu /absolute/path/to/bench_dpdk -- \
  CLIENT_IPV4 SERVER_IPV4 NEXT_HOP_MAC SERVER_UDP_PORT \
  10000 100 100000 add /absolute/path/to/NEW-client.csv
```

Omit `--no-iommu` when the VF has a working IOMMU group. That flag explicitly
permits VFIO without DMA isolation on a dedicated client. The runner refuses
a management-route VF, never reconfigures the PF, and checks restoration in its
`summary.json`. Reserve the machine externally before running it.

After preparing that VF and hugepages, use its exact PCI address and one core:

```sh
bench_dpdk -l 6 -a DATA_VF_BDF --file-prefix=lh-bench -- \
  CLIENT_IPV4 SERVER_IPV4 NEXT_HOP_MAC SERVER_UDP_PORT \
  10000 100 100000 add NEW-client.csv 2>NEW-client.log
```

Arguments after `--`: source IP, destination IP, next-hop MAC, destination UDP
port, measured count, warmup count, per-request timeout in microseconds,
benchmark name, new CSV path. The client UDP source port is 49152. ARP, DHCP,
ICMP and routing are not implemented; provide the correct next-hop MAC and
arrange a static return neighbor when the responder/router needs one. The
Lauberhorn ONC-RPC response path retains request addressing. A timeout or
incorrect matching reply stops the run, writes its failed row, and exits nonzero.
Old IDs and unrelated packets are counted; they do not satisfy the request.

An optional final `loopback` argument enables the PMD's hardware loopback mode
for the `echo` codec only. Use identical source/destination IPs, port 49152,
and the VF's own MAC. It validates the client NIC path without a server and is
not a Lauberhorn or switch-path benchmark.

## Client kernel and CPU isolation

The stock `6.8.0-64-generic` image failed VFIO device open/info on this board.
The observed return paths matched the upstream
[PCI runtime-PM initialization regression](https://lore.kernel.org/all/20250611233117.61810-1-superm1@kernel.org/).
Client-only Ubuntu `6.8.0-146-generic` fixed access; no PF reset or DUT kernel
change was needed. Provision the client and select its kernel through `emg`;
never edit PXE/GRUB configuration or iSCSI/TGT exports directly. Follow the
[Enzian provisioning rules](../../../../docs/development/hardware-test.md#1-check-access-and-reserve).

For lower jitter, isolate the chosen generator CPU (Linux CPU 6 in the recorded
trial), not the DUT's worker IDs. The tested client kernel supports NO_HZ_FULL:

```text
isolcpus=nohz,domain,managed_irq,6 nohz_full=6 rcu_nocbs=6 irqaffinity=0-5,7-47 kthread_cpus=0-5,7-47 rcu_nocb_poll
```

Verify `/sys/devices/system/cpu/isolated` and `nohz_full` after boot. The runner
records both and the kernel command line. It disables DPDK telemetry and uses
legacy hugepage allocation because the ThunderX PMD needs physically contiguous
DMA rings in PA/no-IOMMU mode. Inspect first/last-quarter drift and tails; CPU
isolation is not proof that all interruptions disappeared.

## Measurement contract

The CSV retains every completed/failed attempt, with warmup marked explicitly.
No file writes occur inside the measured RPC. Result pages are touched before
measurement. Mbuf allocation is before `client_call_enter_ns`; request encoding
and framing are inside. The five timestamps use `CLOCK_MONOTONIC_RAW`:

| Interval | Includes |
|---|---|
| enter → TX ready | Benchmark encoding and packet framing |
| TX ready → TX submit | PMD enqueue, retries/backpressure and timestamp cost |
| TX submit → RX burst | Client NIC/network/server plus polling delay |
| RX burst → exit | Response validation, freeing RX buffers and loop bookkeeping |

These raw intervals telescope exactly to the raw E2E duration. TX submit is a
software enqueue boundary, not physical transmission. RX burst is a software
observation after the PMD returns, not wire arrival. Instrumentation within
E2E stays charged to E2E; only one minimum adjacent-clock probe cost is subtracted
in the optional corrected E2E metric. Raw timestamps are always retained.

Use `tools/enzian/benchmark_summary.py CLIENT.csv --output NEW-summary.json`
for all measured samples, failure accounting, percentiles and first/last-quarter
drift. `client_software` is the per-request sum of encoding, enqueue and
validation; it is an aggregate, not an additional stacked component.
`data/eci/apps/plot_latency.py` accepts this CSV and matched runtime CSV:
keep warmup rows in both through hardware correlation, then exclude them from
reported metrics. See [latency measurement](../../../../docs/research/latency-measurement.md)
for clock domains, trace boundaries and the external-residual contract.

## Controlled cold preparation

With one `add` thread in the measured domain and four `mul` threads in a
separate domain, the optional final argument `flush:N` sends a 16-request mul
burst before every Nth add request (including warmup). Set
`LH_BENCH_FLUSH_SPIN_US=100` only on the mul server to keep its workers occupied
while the queue fills; the maximum accepted setting is 10000 us. The measured
add handler is unchanged. `flush:1` prepares every attempted cold request; a
larger N permits a cold first request followed by hot requests.

Every flush response is checked and drained before the measured add starts.
Preparation is outside the timed roundtrip and is recorded in
`OUTPUT.csv.flush.csv`; any invalid response or timeout fails the run. This is
a preparation mechanism, not proof of eviction: require kernel/runtime and
hardware activation evidence for every cold sample. Hardware core selection is
not forced by this mode; report actual core attribution and sample counts.
