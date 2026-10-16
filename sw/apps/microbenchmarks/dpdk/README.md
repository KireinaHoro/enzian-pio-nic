# Reusable polling traffic generator

`bench_dpdk` generates schema-defined echo requests. The active paper workload
uses 8/128/1432-byte echo bodies for loaded and unloaded cases; use the
[canonical campaign](../../../../docs/development/benchmark-campaign.md) for
acquisition and measurement contracts. Loaded operation uses 32 generator CPUs,
response-independent paced batches and aggregate RTT observations. Unloaded
operation is single-outstanding and uses server MAC timestamps.

`echo_dpdk` includes software stage probes; `echo_dpdk_uninstrumented` compiles
them out. Both support PTP for unloaded MAC measurements. Loaded responder
operation uses the uninstrumented batched path with PTP disabled. `wire.h` owns
Ethernet/IPv4/UDP framing and validation; `bench.x` defines the echo payloads.
The independent add/mul codecs remain available for infrastructure tests.

## Build and functional checks

From the repository root:

```sh
nix build .#trafficGeneratorNative    # local Linux
nix build .#trafficGenerator          # cross-compiled ThunderX/AArch64
nix develop -c make -C sw/apps/microbenchmarks/dpdk check
```

Both Nix packages come from the `thunderx-ptp` flake input and include the
ThunderX Linux PF receive-filter correction and opt-in packet timestamp support.
See the [package/API documentation](../../../../../thunderx-dpdk-ptp/README.md).
The generator does not enable hardware timestamps merely by linking this library.
The cross package also supplies ThunderX T88 machine metadata and the target
archiver. Only the required libraries and ThunderX/pcap PMDs are built.
The native Enzian validation uses patched DPDK 23.11.4; Nix pins 26.03.
Preserve that version distinction in comparisons.

The Ubuntu stock PMD sent valid packets but received none on zuestoll07:
`SET_XCAST` applies the PF's CAM list but does not add its `mac` argument.
The [compatibility patch](../../../../../thunderx-dpdk-ptp/patches/dpdk-thunderx-linux-cam.patch)
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
separate domain, the optional final argument `flush:N[:ROUNDS]` sends two 16-request mul
bursts before every Nth add request (including warmup). Set
`LH_BENCH_FLUSH_SPIN_US=100` only on the mul server to keep its workers occupied
while the queue fills; the maximum accepted setting is 10000 us. The measured
add handler is unchanged. `flush:1` prepares every attempted cold request; a
larger N permits a cold first request followed by hot requests.

Every flush response is checked and drained before the measured add starts.
Set ROUNDS to 1..8 to vary preparation; retain all attempts and verify eviction.
Preparation is outside the timed roundtrip and is recorded in
`OUTPUT.csv.flush.csv`; any invalid response or timeout fails the run. This is
a preparation mechanism, not proof of eviction: require kernel/runtime and
hardware activation evidence for every cold sample. Hardware core selection is
not forced by this mode; report actual core attribution and sample counts.

For synchronized capture, set both `LH_BENCH_READY_FILE` and
`LH_BENCH_START_FILE` to unique shared paths. After NIC initialization and clock
calibration the client creates READY exclusively, then waits up to 300 seconds
for START. Arm tracing after READY and create START to release the workload.
Existing markers are rejected; no filesystem checks occur inside timed RPCs.

## Bounded concurrent diagnostic load

Set `LH_BENCH_WINDOW=1..256` to enable a closed-loop concurrent window. Warmup
requests drain before the measured observation window begins. Every sent request
retains its own timestamps and ID; out-of-order replies are accepted, duplicate,
unsent-ID and incorrect replies fail the run. All pending requests are retained
as failures on timeout. TX admission failure stops rather than silently dropping
work. No output occurs in the timed loop. This is achieved closed-loop load,
not an open-loop offered-rate experiment or a coordinated-omission correction.

Optional `LH_BENCH_GAP_NS` spaces request starts (0..1e9 ns); it is a minimum gap,
not a promise that the generator attains a requested rate. `flush:N` requires
window 1. The measured observation interval in `OUTPUT.csv.load.json` excludes
warmup, includes the final drain, and includes flush preparation in cold mode.
RTT still excludes preparation and inter-request pacing. Compute throughput
from successful measured completions divided by this recorded interval, never
from reciprocal median RTT. Reject runs with `failed != 0` or missing rows.

Validate both modes before a physical run:

```sh
nix build .#trafficGeneratorNative --out-link out/load-client
nix develop -c python3 sw/apps/microbenchmarks/dpdk/replay_test.py \
  out/load-client/bin/bench_dpdk out/NEW-latency-replay
nix develop -c python3 sw/apps/microbenchmarks/dpdk/load_replay_test.py \
  out/load-client/bin/bench_dpdk out/NEW-load-replay
```

A physical pilot is still required: pcap timing is never paper performance data.
Configure the DUT's static neighbor for the peer before taking its VF from the
kernel, because this client deliberately does not answer ARP.

## CPU-NIC echo responder

The same package builds `echo_dpdk`, a single-worker polling responder for
exactly the generator's `add` wire format. It buffers paired hardware RX/TX
samples and writes the CSV after the run. `mac_to_mac_ns` is TX minus RX from
the NIC's common PTP counter, not client RTT. Only one timestamped TX operation
may be outstanding. Warmups remain in the CSV with `warmup=1`; failed samples
are explicit and make the process fail.

Boot the DUT with the platform's `bootFiles` and `copymods=force` as described
in the [deployment workflow](../../../../docs/development/workflow.md#thunderx-cpu-nic-timestamps).
Mount the matching `trafficGeneratorDeployFs` read-only at `/nix/store` (use a
private mount namespace if another deployment already occupies that path).
On a reserved DUT with its data NIC configured, start:

```sh
LH_ECHO_READY_FILE=/dev/shm/NEW-ready \
/nix/store/lauberhorn-client/bin/lh-dpdk-run \
  --logs /dev/shm/NEW-server --lcore 45 --no-iommu --ptp-bgx 4 \
  --manifest /nix/store/lauberhorn-client/manifest.json \
  /nix/store/lauberhorn-client/bin/echo_dpdk -- \
  DUT_IP CLIENT_IP CLIENT_MAC 49152 2000 32 2000000 add /dev/shm/NEW-server.csv
```

Wait for the ready file before running `bench_dpdk` on the reserved peer with
the same count/warmup, DUT destination port 12345, and the reversed endpoints.
Both runner summaries must pass and report restoration. The timestamp runner
refuses an unmatched kernel, benchmark package or loaded/installed module set.
Save the image manifest and both CSVs with any dataset used in the paper.
`LH_ECHO_DELAY_NS=20000` inserts a deliberate 20 µs service delay for a
clock/interval control. `LH_ECHO_SOFTWARE_CONTROL=1` disables timestamps for
a packet-path control; its zero timestamp columns are not MAC measurements.

### Optional CPU-stage profiling

Set `LH_ECHO_PROFILE=1` on the responder to buffer six
`CLOCK_MONOTONIC_RAW` boundaries alongside each paired MAC timestamp: first
successful RX-burst observation (including a packet held while draining the
preceding TX timestamp), network parse start/end, network framing start/end,
and immediately before the TX-burst attempt. CSV records retain the minimum
adjacent-clock probe duration; no probe-cost subtraction is applied.

The RX-to-pre-TX CPU interval includes RPC validation/encoding, dispatch and
buffer preparation. Network time is the sum of the parse and framing spans.
The fixed-input responder validates 7 and 11 and encodes 18 directly, so there
is no separately executed handler to time. The residual MAC interval includes
PMD, DMA, completion polling, NIC processing and queueing; it is not a pure
interconnect delay. Profiled measurements are a separate cohort: use a
probe-off control and do not assign their components to an earlier total.
