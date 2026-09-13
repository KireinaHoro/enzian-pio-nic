# Recorded Enzian test results

Historical evidence from zuestoll14, 2026-09-08–10. Use the
[execution guide](hardware-test.md) for a new test. Evidence under `out/` is local
and ignored; recipes and helpers are tracked.

| Build | Timing evidence | Observed result |
| --- | --- | --- |
| 2812450, `aa32b1af` | Route WNS −1.314 ns | Original 101 add calls passed; two verified-reset repeats each passed 100 calls |
| 2811847, `93f4c1da` | Route WNS −2.073 ns | Static-shell read oops despite successful programming and both ECI links in RUN |
| 2383312, `4fa10def` (Mar 20) | Final WNS −0.164 ns, TNS −165.535 ns, WHS +0.004 ns | Initial shell-read oops; two verified-reset repeats passed module/revision checks but failed RPC |

The older repeats failed differently: first RPC timed out; second client
creation reported unable to receive. No oops occurred in either repeat. All four
repeat trials measured CPU core 0–0.00390625 V and FPGA core 0 V before power-up.
Reset dwell changed before repeating, so reset sensitivity and run-to-run
variation remain confounded. WNS alone does not explain the outcomes; these
small samples establish neither long-term reliability nor timing closure.
At the end of those earlier trials, 2812450 was serving add at `192.168.129.200`, with
`cpu40g1` down. Recheck reservation and machine state in every new session.

## Evidence locations

- `out/hardware-tests/reproducibility/verified-trials/summary.json`: four trials,
  exact manifest, reset/program/console logs and per-trial gateway client output.
- `out/hardware-tests/reproducibility/repro-{01,02}-{2812450,2383312}/`: copied
  server logs/client CSVs. September CSVs each contain 100 correct results.
- `out/hardware-tests/2812450/`: first successful boot and `rpc/client-{one,100}.csv`.
- `out/hardware-tests/2811847/`: negative/refactored-negative tests, programmed
  `positive-load/console.log`, direct 64-bit read SIGBUS, and `eci-edge.csv`.
- `out/hardware-tests/2383312/`: initial older test and final `timing.rpt`.
- `out/hardware-tests/build-selection/`: successful CI traces and timing comparison.
- `reproducibility/trials/` contains excluded harness failures: monitors were
  read after their deinitialization. `monitor-recovery/` records the correction.

The negative test twice confirmed synchronous external abort `0000000096000210`
in `probe_versions`; disassembly located the static-shell version read at
`0x97effffffff8`. The shell survived and `insmod` received SIGSEGV. Matching
software was always rebuilt at the hardware commit with a flake-only kernel
release fix; the original uncorrected `6.8.12` module was never force-loaded.

## Older artifact discovery

On ba2, `Downloads/artifacts(21).zip` matched job 2383312 by exact CI generation
timestamps; later successful NIC revision checks corroborated the association.
`artifacts(22).zip` matched June 12 job 2645398 (`76c1ae40`) by timestamps, with
route-selection WNS −1.484 ns. June 3 jobs 2623585 and 2622870 had much better
route estimates (−0.089/−0.009 ns), but direct artifact requests returned 404.
March's legacy `add.x` lacks request IDs; its matching client source/binary is
retained in `out/hardware-tests/2383312/client/` and gateway
`/scratch/pengxu/lauberhorn-e2e-2383312-client/`. Do not use the newer wire schema.

## Imported runtime and Dandelion, September 10

DUT: `zuestoll14`, job 2812450, hardware/runtime revision
`aa32b1af04f2fcdd9191c94784eba5bbad1ea25f`. Upstream testing workflow `ed1b45b`
was merged in `5cb21f4`; runtime, core transport, kernel and RTL remained
unchanged. Supplemental application closures use the same runtime and generated
headers, with the existing kernel-release packaging fix. No new bitstream was
built for these application probes.

Peer: `zuestoll12`, acquired normally, then reacquired with golden image
`2025-07-28` (Ubuntu 24.04.2, kernel `6.8.0-64-generic`). Its `cpu40g1` route
to `192.168.129.200` uses source `192.168.129.129`. The DUT uses `lauberhorn0`
and keeps its own `cpu40g1` down. Gateway is only management/shared-file staging
for these peer trials. The peer FPGA and Lauberhorn kernel module are unused.

Sven's application source is platform
`9e2518140ecf4423f0f01b8caded4ad1f708cf62`; the nested demo's only patch makes
the remote IP configurable. Dandelion is an external archived snapshot of
`69c3088f8f5333eab653ed5c9bd2f6da8f7f944a`, built for AArch64 with the MMU
process engine against the real imported runtime. `student_build.nix` builds
the application, peer-client and deployment squashfs closures. All three
closures built successfully; the full Dandelion Rust test suite was not run.

| Peer probe | Observed result |
| --- | --- |
| Upstream add, four workers | 100/100 calls passed, including arithmetic and echoed request IDs |
| Upstream mul, four workers | 100/100 calls passed, including arithmetic and echoed request IDs |
| Sven standalone adder, four workers | 100/100 calls passed after the reset retry, including arithmetic and echoed 64-bit timestamps |
| Sven nested adder, remote responder on peer port 12345 | Timeout after 10 seconds; zero nested requests received by the responder |
| Dandelion single-function `test_matmul` equivalent | HTTP registration passed; FPGA UDP call returned the expected 1×1 matrix in a 148-byte ONC-RPC reply |

Every peer attempt uses a full reset with CPU core readings
`[0, 0.00390625]` V and FPGA core `[0]` V. Executed application probes passed
module and exact NIC revision verification with no startup oops/panic; one
separate module-load failure is described below. A passing
smoke probe does not establish long-term reliability, performance, fault
recovery, nested completions, or Dandelion compositions/sharding. The imported
runtime remains unready for merge; see the
[deferred integration work](../software/nested-rpc-integration.md).
Dandelion's process-engine CPU mapping also remains unreconciled: its source
uses worker indices 0–3 as Linux CPU IDs, distinct from the isolated NIC RPC
workers on CPUs 44–47. This smoke result does not validate that scheduling policy.

The first standalone-adder setup is a separate failed hardware-initialization
trial: after a verified full reset and successful programming, `insmod`
aborted in `probe_versions+0xa4/0x258` with synchronous external abort
`0000000096000210`. The application and client did not run in that attempt.
Retain `peer-followup/01-sven-adder-peer-20260910/cpu/console.log`; do not count
this as an adder arithmetic failure or discard it when reporting repeatability.
The immediate verified-reset retry passed module/revision checks and all 100
standalone-adder calls (`student-retry/summary.json`).

Afterward, SIGTERM left the adder leader in `Zl` and three worker threads in
`Il`, with wait channel `do_notify_resume`. The server log stopped growing,
but this was not a clean process exit. Cleanup/recovery remains unresolved;
`nested-rpc-20260910-final-stop/` and `nested-rpc-20260910-final-state/` retain
the console evidence.
Final cleanup powered down the DUT and verified CPU core `[0, 0.00390625]` V
and FPGA core `[0]` V. BMC/common monitoring power remains available; use the
normal full boot/program workflow for the next trial. Reservations for
`zuestoll14` and `zuestoll12` remain with `pengxu`; the peer stays booted.
Final voltage evidence is in `nested-rpc-20260910-final-power-off/bmc.log`.

### Nested destination corruption

The peer PCAP contains the initial request to DUT port 12346 followed by three
DUT ARP requests for **129.129.0.0**, despite `NESTED_REMOTE_IP=192.168.129.129`.
No nested UDP call reached the responder. The runtime log shows three
`core_eci_tx` operations; neither that log nor the capture proves a valid UDP
call left the FPGA. This failure is independent of switch self-reflection:
the responder was on another physical machine.

The exact generated `lauberhorn_eci_dev.h` destination accessor stores through
`uint32_t *)(12 + ptr)` after shifting a 32-bit value by 16. It therefore loses
the upper half of the field. RTL places the field at byte 14, following the
16-bit PID, cookie and XID. The archived host-side `descriptor-probe.c`,
compiled in `nix develop` against that header, exited 1 and printed:

```text
input=c0a88181 rtl_daddr=00008181 bytes=81 81 00 00
```

Those bytes match the captured wrong ARP target. Cookie/XID/program fields use
the same problematic generated access pattern. The runtime also still needs
wire-order conversion and PID/cookie/source-port/reply-listener integration;
correcting the truncation alone will not establish nested RPC correctness.
Fix the generator or coordinate a layout change with RTL and regenerate the
collateral; do not hand-edit a generated header or deploy unmatched artifacts.

### Reproduction and evidence

- Tracked recipes: `tools/enzian/runtime-peer.example.json`,
  `student_build.nix`, the three Python application clients and capture wrapper.
- Local evidence root: `out/hardware-tests/nested-rpc-20260910/`.
  `peer-campaign/`, `peer-followup/` and `student-retry/` contain exact
  manifests/reset/program/console logs. `add-evidence/`, `mul-evidence/`,
  `student-adder-evidence/`, `nested-evidence/` and `dandelion-evidence/` contain server/client logs and
  `peer.pcap`. Shared originals use `/scratch/pengxu/test-01-CASE/`.
- `provenance.json` records source revisions, image hashes and endpoint patch;
  `descriptor-probe.{c,log}` records the host-side generated-header reproduction.
- Earlier gateway-only preliminary tests (`baselines/summary.json`) passed
  two 100-call add trials and two 100-call mul trials. These precede the peer
  setup and must not be described as peer traffic results.
- Initial peer add/mul attempts were excluded before RPC: tcpdump first failed
  changing ownership on shared NFS, then crashed with SIGSEGV. `peer_capture.py`
  replaced it. PCAP timestamps are userspace receive times, with no drop-count
  measurement; use them for packet diagnosis, not latency conclusions.
- Shared backup of manifests, reset/console logs and provenance:
  `/scratch/pengxu/nested-rt-20260910/evidence/`.

Image SHA-256 values (shared paths `/scratch/pengxu/nested-rt-20260910-NAME.img`):

| NAME | SHA-256 |
| --- | --- |
| student | `8f887af1dc3449a93f21fdbf227fd7d9b7831a9fb6f6b2991d575e7a59cbdeaf` |
| dandelion | `f1b6b2cbb4a9db50db5e070debabc5c6616fa0f6acf822f74743b6ca567cbd15` |
| peer | `f13ced45efbdda656277030093773e72465322887d14d8cc2197868b589affc3` |

Generated ECI header SHA-256:
`bede7aca7f2d15acfc045f4ae1e86842833fc5ba85b003133fcc52744ea7edec`.
Nested PCAP SHA-256:
`a00e6c053f4f7cfd783d8446af00f5730b66b220f9398bfe1570e6cc563e0153`.
Dandelion PCAP SHA-256:
`d281c511752ff09996c1b3b9a800532d3d5fd0377e84ad119225482e4e634acd`.
Matmul fixture (`machine_interface/tests/data/test_elf_mmu_aarch64_matmul`
from the Dandelion snapshot) SHA-256:
`ab570741dcc23e098db3f71c4d686a4bd9e60edd9af66a49c876945f7b9060d0`.
