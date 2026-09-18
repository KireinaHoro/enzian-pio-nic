# Recorded Enzian test results

Historical evidence from zuestoll14, 2026-09-08–10. Final checkpoint STA and
source-level bottlenecks are in [physical findings](../hardware/physical-findings.md). Use the
[execution guide](hardware-test.md) for a new test. Evidence under `out/` is local
and ignored; recipes and helpers are tracked.

| Build | Timing evidence | Observed result |
| --- | --- | --- |
| 2812450, `aa32b1af` | Final WNS −1.051 ns (route estimate −1.314) | Original 101 add calls passed; two verified-reset repeats each passed 100 calls |
| 2811847, `93f4c1da` | Final WNS −1.813 ns (route estimate −2.073) | Static-shell read oops despite successful programming and both ECI links in RUN |
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

## Nix packaging campaign — 2026-09-15

Four verified-reset trials of the shared image passed on job 2812450 hardware:
helper and direct paths twice each, 400 correct adder replies total. This was
packaging validation using matching historical sources, not final-master hardware
validation. See the [full provenance and checks](nix-refactor-validation.md).

## Fresh trace CI and BDK initialization gate — 2026-09-18

[Pipeline 512110](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/512110)
passed all jobs at `4c898645cde1484d2fb5435a42e045a3c1f15068`.
Hardware job 2834758 and deployment image from prepare job 2834757 were
staged together. This is a fresh CI implementation, not either polarity ECO.

The first verified cold boot reached Linux but loading the matching driver
aborted at `probe_versions+0xa4`, the static-shell read at `0x97effffffff8`.
Its post-menu BDK log reported QLM8..13 CDR lock but **omitted the CCPI lane
initialization line**. The second cold boot of the identical artifacts printed
all lanes `[0]` through `[23]`, passed version/driver initialization, and checked
100 adder RPC replies with zero failures. This separates incomplete BDK ECI
bring-up from a reproducible failure of the new RTL; it does not establish why
lane initialization intermittently fails.

The successful boot's static ECI edge ILA captured the shell-version request
on link 1 at sample 0 (`0010cfdffffffffc`), its response header at sample 9,
and version `02f19869` at sample 12. Both links reported RUN. The first failed
boot's DDR trace had been stopped for the earlier network experiment, before
module load; its 1,280-byte JTAG snapshot is **not** a capture of the abort.
The successful comparison retains the ILA, a partial DDR prefix, and a 1 MiB
tail at byte offset `0x7c208c0`; the full 125.13 MiB JTAG dump was stopped
because this attempt succeeded. Do not treat those partial windows as a full,
lossless ECI credit reconstruction.

The trace CMAC aligns with local fault clear but intermittent remote fault.
On the RPC-passing boot, zuestoll12 received metadata from `192.168.129.201`,
then timed out on the first data read (zero trace bytes). Metadata reported
writeSlot=2050083, wrapped=0, sampleLost=1, dmaError=0. A separate reset trial's
receiver could not bind because the original receiver still owned the port;
that trial is not a valid UDP-read result. Switch-side port/FEC diagnosis is
still needed; the successful metadata packet does not certify network dumping.

The expect-based boot helper now rejects missing/unlocked QLM8..13 or missing/
incomplete CCPI lanes 0..23 before accepting boot. It retains each failed log
and performs a full verified reset and reprogramming, up to three attempts.
Only ECI initialization failures trigger these retries; programming/access
failures still stop. The explicit no-bitstream negative test remains exempt.
Seven boot regressions and all twelve existing workflow tests pass through
`checks.x86_64-linux.workflow-tools`, including an actual expect-stream gate,
retry exhaustion and reset recovery from a resumed boot.

Evidence is under `out/hardware-tests/cmac-trace-512110/`. Two obsolete agent
server logs were truncated, reclaiming about 45 GiB of shared scratch while
preserving result CSVs and recovery/deployment images.

## B4 pipeline 512199 functional probe — 2026-09-18

Hardware job **2835579**, revision `754ecb4f19746ac62ade3ccaa301b33ee7905762`,
was tested on reserved zuestoll14 with an exact-revision deployment image.
This image still fails routed setup timing (WNS −0.382 ns); functional results
below do not establish timing closure. Bitstream SHA-256:
`d16ddf408ba4d749036c319df6533753b1cb03444d25ede3be84fe7a25b0f481`.
Deployment SHA-256:
`3eac2c22eb4a3f42ffdfa361344c881ad37c98394b44781410f95ce8db424448`.

Two verified-reset boots passed module/revision verification and **100 correct
multiplication RPCs each**. Between these successes, two boots using the old
helper aborted in `probe_versions` at the static-shell version read. Applying
the new ECI validator to their saved transcripts found missing CCPI lane
initialization in both failures; the successful boots reported QLM8..13 lock and
all 24 lanes. The last successful run used the updated gated helper directly.
The second abort's JTAG snapshot retained 23 DDR slots (1472 bytes), with
wrapped/sampleLost/dmaError all zero. No static edge ILA was armed for that abort,
so the application trace does not establish the shell-read failure mechanism.

Master `2a614d9` was merged, and boot-flow commits `01e8454` and `ff506d2` were
imported separately from `fix/cmac-trace-capture`, where they had not yet reached
master. The imported flow passed its seven tests and the Nix `workflow-tools`
check. These host-tool updates did not change the deployed artifacts.

The first recovery attempts exposed serial harness truncation: the fixture
registered and reached `RECOVERY_READY`, but the completion-marker command sent
after the heredoc was truncated. The marker now shares the initial shell command
line, so it arrives before worker startup/kernel logging. Local shell checks
verified preservation of success and nonzero statuses. The two interrupted
fixtures were retired explicitly; their attempts remain recorded as harness
failures, not passing campaigns.

With corrected framing, **six no-reset signal cases passed**: INT/TERM/KILL
before traffic and after verified dispatch. Each checked process retirement,
service reuse, and unrelated multiplication RPCs. The next case,
`01-hang-explicit-active`, failed its unrelated RPC check. Its startup transcript
records `L2C_TAD_INT: 0000000000020000, TIMEOUT: c0088100000a0080`, an
uncorrected LFB entry timeout. A later RPC check also failed, and console login
could not collect kernel state. The fixture's timed explicit-stop completion is
therefore unverified. Dirty-TX, stuck-BUSY, watchdog and the second campaign round
were not reached. This is an open failure, not B4 acceptance or proof of a
particular hardware cause.

The FPGA trace was frozen before any reset. Status was writeSlot=84456073,
wrapped=0, sampleLost=0, dmaError=0; only bounded prefix/tail windows are retained,
not a full trace or lossless reconstruction. The board was left in the failed
state for diagnosis. Local evidence: `out/enzian/b4-512199/`, including exact
manifests/provenance, `smoke*/summary.json`, `legacy-boot-validation.json`,
`recovery-512199-framed/summary.json`, and JTAG captures. Preserve the original
failure when testing a later timing-corrected image.

The subsequent [trace and crossbar investigation](../hardware/b4-l2-timeout-512199.md)
identifies a preemption-control read before service dispatch and reproduces an
AXI DECERR completion-accounting bug that blocks subsequent mapped reads. The
board's RRESP and exact software wakeup were not captured; implementation fixes
and corrected board validation remain outstanding.
