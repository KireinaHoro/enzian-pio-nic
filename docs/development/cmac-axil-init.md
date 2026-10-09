# CMAC AXI-Lite initialization investigation — October 9

Branch: `fix/cmac-axil-init`. Pipeline 518139 built revision `79d5758f`.
See the board evidence below; component checks alone do not establish alignment.

## Failure mechanism

Before removal in `23b1a134`, `lauberhorn_eci.vhd` instantiated the CMAC
interconnect with `M01_BASE_ADDR=0x200000`, `M01_ADDR_WIDTH=11`. That exposes only
`0x200000–0x2007ff`. `sw/devices/cmac.dev` and the startup code access RS-FEC
correction at `+0x1000` and RX/TX enable at `+0x107c`: both are outside the
aperture. The actual interconnect returns DECERR for these accesses. MAC/version
register access within the first 2 KiB can succeed while FEC programming fails.

[AMD PG203 register descriptions](https://docs.amd.com/r/en-US/pg203-cmac-usplus/Register-Descriptions)
place the FEC controls at those offsets, with RX/TX enable defaulting to zero.
The autonomous no-register design ties FEC enable/correction/indication high.
This establishes a concrete programming-path defect consistent with the reported
failure to align; it does not prove that it was the only board-level cause.

## Correction

Restore the F_MAC0 AXI-Lite port, existing 64-to-32 adapter and two-target
interconnect. Map a full 64 KiB CMAC bank and explicitly pass local offsets to
the CMAC. Use the application clock/reset for register access and retain board
GT polarity. Register mode owns MAC/FEC controls; remove their external ties.
F_MAC1 keeps autonomous control so driver initialization cannot disable the
trace capture link. Static shell and toolkit sources are unchanged.

`init_bypass()` now starts CMAC before publishing a netdev. Startup validates the
version, pulses reset, enables FEC and checks its readback, enables RX with RFI,
waits for alignment, enables TX and clears RFI, then checks both aligned/status
bits in one status snapshot. Waits sleep rather than busy-wait. Each polling
phase is bounded to approximately ten seconds. Failures disable RX/TX and
report FEC/status/block-lock diagnostics. Netdev down does not stop the physical
MAC shared with RPC workers. There is no periodic carrier monitor or automatic
runtime recovery in this change.

The status register is mixed latched-low/latched-high, not a plain live word.
Polling consumes those latches. The restored generic width adapter can read a
neighboring 32-bit word on a 64-bit-side read; comprehensive statistics access
still needs a separate audit. Startup reads status at `+0x204` (upper lane).

## Validation

- Production interconnect simulation: historical 11-bit aperture rejects both
  FEC addresses; new 16-bit aperture routes them; version/status and NIC routes
  work, and the next unmapped bank returns DECERR.
- Production C startup with generated Mackerel accessors and fake MMIO passes
  success, inaccessible FEC, alignment timeout, status timeout, and invalid
  version cases. These tests do not model analog alignment or reset timing.
- Nix cross-build of the kernel module passes (Ubuntu arm64 6.8.0-64 ABI).

Reproduce from the branch with recursive submodules:

```sh
nix develop -c python3 tools/cmac/check-routing.py
nix build .#devHdrs --out-link out/cmac-dev-headers
nix develop -c python3 tools/cmac/check-startup.py out/cmac-dev-headers
nix build .#kmod --out-link out/cmac-kmod
```

Board results are recorded below. Long-duration link monitoring/recovery and
comprehensive statistics-register semantics remain outside this initialization
fix. Passing functional tests do not establish timing closure.

## Pipeline 518139 and board follow-up

Pipeline [518139](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/518139)
built `79d5758f86a9b891234519b46e49226e01cfbb3d`. All fourteen test gates,
prepare job 2880982, hardware job 2880983 and report job 2880984 passed.
Matched bitstream, probes, trace map, deployment image and manifests are retained
in `/tmp/lauberhorn-cmac-axil/out/cmac-ci-518139/`. Deployment SHA-256:
`c489cbe0b3f2fad1aba9313963d82638872e42c8da4e83e6df7d74f73c09ea76`.
Hardware hashes and job metadata are in `hardware/`; `provenance.json` records
both pipeline identity and preservation of the prior failure.

Vivado reports WNS **−0.881 ns**, TNS **−2783.831 ns**, and **15,040** failing
setup endpoints; hold and pulse-width checks have no failing endpoints.
This is not timing closure. No timing optimization was performed.

Before reset, current reservations and DUT state were checked. The frozen
pipeline-517951 memcached failure pointer (`0xa2a648`, 682,201,600 bytes)
matched its complete local capture and SHA-256
`76450049ee7cbe01f0a0f8880d37afd980a53ed77aab6262f53831f2277748ba`.
The capture remains in the main workspace at
`out/native-udp/memcached-recovery-trace/trace-dram.bin`. Shared-storage quota
prevented image staging; only the remote duplicate was removed, after verifying
its identical hash, with a location note left beside the remote metadata.
The old trace was frozen with sticky sample-loss set; its existing limitations
remain unchanged.

The first cold boot passed full power-off verification, QLM8–13 lock and all
24 CCPI/ECI lanes. With FPGA tracing armed before module load, the driver reported
CMAC version 3.1, loopback disabled, and RX aligned/ready. FEC readback, reset and
both bounded link polls completed successfully; all 100 checked RPCs passed.
The compact harness result is `board-rpc/summary.json`; selected CMAC startup
lines and client CSV are retained in `trial-01-results/`.

The second requested trial stopped before FPGA programming: BMC
`fpga_power_up()` received `None` while reading `VCC1V8_FPGA`, causing a
`TypeError` in its voltage comparison. The full failed-stage log is retained
under `board-rpc/02-cmac-518139-rpc/boot/`. This is an infrastructure failure,
not a driver/alignment result. A single bounded recovery trial uses unchanged
artifacts and the normal full verified cold-reset harness.

The recovery trial's first boot lacked the required CCPI lane-initialization
report. The boot helper rejected it before module loading and automatically
performed another full cold reset. Its second attempt passed all QLM/lane
checks, CMAC startup and another 100 checked RPCs. Results are in
`board-rpc-retry/summary.json` and `trial-02-results/`. No driver/kernel faults
were reported in either passing trial. Total acceptance is **two validated cold
boots and 200 checked RPCs**, with the BMC failure and rejected ECI boot counted
separately, not as passes.

The DUT is left on pipeline 518139 with the second successful RPC server running
and FPGA tracing enabled. Recheck reservation and actual state before further
access. This demonstrates external-link alignment and functional traffic with
AXI registers enabled and initialization reset/readback checks; it does not
validate every CMAC register or long-duration link recovery. The branch remains
separate from the ongoing memcached/padding work.

Ten final F_MAC0 VIO samples all showed aligned/status=1 and local/remote
fault=0. The final trace snapshot was unwrapped with sample-loss=0, DMA-error=0
and trace-stop=0. Both saved client CSVs contain 100 correct results with matched
request IDs. CMAC version-to-ready intervals were approximately 0.43 seconds
on both validated boots; these are kernel log intervals, not RPC latencies.
