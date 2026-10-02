# Recorded Enzian test results

Historical evidence from zuestoll14, September 2026. Final checkpoint STA and
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

## CMAC trace capture investigation — 2026-09-17

The job 2812450 trace CMAC on F_MAC3 used F_MAC0's RX/TX polarity mask
`0011`; the engineer's interfaces-stub specifies `1100` for F_MAC3. The
[source correction and capture setup](tracing.md#udp-readout) parameterize
the shared CMAC control constructor. RX/TX RS-FEC enable, RX correction,
RX indication and IEEE indication mode were already high in the routed
checkpoint, with transcoder bypass low. A comparison of 559 normalized
CMAC primitive control/clock/reset inputs found no differences between
the traffic and trace instances.

The capture host was reserved zuestoll12, `cpu40g1`, IP `192.168.129.129/18`,
MAC `0c:53:31:03:01:81`. LLDP identified switch `leaf1`, chassis
`00:90:fb:73:e4:a1`, and port `ZS12-CPU2`; the working zuestoll14 traffic
CMAC reported the same switch and port `ZS14-FPGA1`. These are observed
port identities; the F_MAC3 switch port still needs direct confirmation.

The original trace port had no RX alignment and asserted local fault;
internal PMA loopback aligned with both faults clear. An eight-pin polarity
ECO followed by `route_design -preserve` restored external RX alignment
and cleared local fault, but remote fault remained asserted. No trace
metadata reached zuestoll12. Both the regenerated full image and the
original full image plus the ECO partial image failed the driver's first
version read with an external abort. Restoring the original full image
passed driver/revision checks and 100 adder RPCs with zero failures.
The ECO therefore is not a validated deployment artifact, and network
dumping is not yet verified.

The first ECO preserved all 1,096,490 primitive placements. Of 1,177,789
nonconstant route descriptions, six changed; detailed node/PIP comparison
found five were only reordered descriptions. The actual change was on `clk`,
driven by `i_eci_platform/i_eci_transport/i_clk_gt_link1`: seven PIPs added,
three removed. A second ECO explicitly locked all 892,250 completed signal
routes before routing the constant nets. Those six networks then matched the
original nodes/PIPs, and driver version/initialization checks passed, but
network setup triggered an asynchronous SError panic. The trace remote fault
alternated between zero and one. One metadata datagram reached zuestoll12;
the first read timed out with an empty output file. A subsequent attempt with
a static neighbor entry received no metadata; the temporary entry was removed.

The corrected Python receiver passed three UDP loopback regression tests;
the original RTL trace-responder suite passed all seven tests. A new realistic
ARP-padding regression exposed a separate parser bug: padding was treated as
the next UDP command. Discarding ARP bodies before the command aligner passed
all eight tests, including header-only, minimum-size and multi-beat padded ARP
followed by UDP. This RTL correction requires a fresh hardware build; the
polarity-only ECO images do not contain it. These checks do not establish
operation of the physical link. Local evidence is under
`out/hardware-tests/cmac-trace-20260917/`, with status in `summary.json`.


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

## B4 kernel parking fix on 512199 — 2026-09-18

Kernel fix `5ec614c` enforces initial registration and preemption parking through
checked return-to-user task work. The callback rechecks after every wakeup,
instead of relying on TASK_IDLE set inside mmap or a single sleep. See the
[diagnosis](../hardware/b4-l2-timeout-512199.md).

On reserved zuestoll14, the original **512199 / `754ecb4` bitstream** was retained.
Only `sw/kmod/sched.c` was patched in the original module source; RTL, generated
headers, runtime and applications remained from `754ecb4`. The deployment
manifest separately records this local module modification and patch hash.
Image SHA-256: `be1f319b2d802dd1c161cea505aa9855f5e91ea5a4fbe6f5cfbb26ed95ec1ca2`.
Patch SHA-256: `aa7a2475d2b47b2d5ba79fe30d0e78a8cf5fe5bc3726641d4f302be599eff3be`.

**100 multiplication RPCs passed with zero failures**, followed by **20/20
recovery cases across two no-reset rounds**: INT/TERM/KILL before traffic and
after dispatch, explicit stop of a hung handler, dirty uncommitted TX, stuck
BUSY, and watchdog expiry. The persistent multiplication service passed every
interleaved correctness check. Both repetitions of the previously failing
hung-handler case completed. Kernel traces record retirement phase 2 for all
20 fixtures and all three dispatch phases for active cases. Both watchdog cases
record hardware kill and recovery reason 2. Stuck-BUSY cases also record the
hardware kill path, with explicit-stop recovery reason 1. No L2 timeout, checked
kernel fault, ftrace overrun, commit overrun or dropped event was found in the
archived evidence. This validates this incoming/process recovery campaign; it
is not closure of nested cancellation/deadline/late-reply/outgoing-capacity work
or of the independently reproduced crossbar DECERR bug.

Setup and interrupted attempts are retained separately:

- First boot lacked the BDK CCPI report; the automated second boot passed all
  QLM and 24-lane checks and loaded the module without fault.
- Initial RPC failed ARP resolution. The worker was correctly parked in
  `park_at_user_return`; hardware MAC ingress was zero, bypass was empty, and
  the main CMAC reported alignment plus remote fault. A transmitter-only VIO
  reset restored traffic without resetting application/ECI state. Its immediate
  status sample still showed remote fault; the subsequent 100-call RPC result
  is the evidence of recovered traffic. A bounded DDR capture has no loss flags.
- An early campaign passed two cases, then serial input stalled in an unfinished
  heredoc before the third kill executed. The owned fixture was subsequently
  retired. Runner fix `7fec944` waits for a unique shell continuation prompt
  after each 128-character payload line. All 21 Nix workflow tests passed,
  including two actual-shell framing regressions.
- The next start found the 4.4 GiB root filesystem full before creating its log
  directory. A previous-boot test-owned server log was archived to shared scratch
  and removed. The final 20-case campaign then completed on the same boot.

Local evidence: `out/worker-park-test/`, especially `provenance.json`,
`recovery-512199-worker-park-final/summary.json`, `recovery-audit.json` and
`recovery-evidence/`. Shared archive:
`/scratch/pengxu/lh-worker-park-recovery-evidence/`. The verbose persistent
benchmark is stopped and its log archived after validation to avoid filling
root again; the patched module remains loaded. Kernel cross-build and seven
shim-based parking scenarios also passed; these are separate from board results.

## Bundled nested RPC and restored server tracing — 2026-09-18

DUT zuestoll14, peer zuestoll12 (`192.168.129.129`), unchanged FPGA from
pipeline **512199**, revision `754ecb4f19746ac62ade3ccaa301b33ee7905762`.
Bitstream SHA256 `d16ddf408ba4d749036c319df6533753b1cb03444d25ede3be84fe7a25b0f481`.
The deployment deliberately retains that revision's generated RTL, configuration
and Mackerel headers, plus the previously tested `5ec614c` kernel parking patch.
Runtime tracing is `dac2450`; the board-discovered inline TX copy repair is
`a4600bc`; the final validation fixture is `006cce4`. This is software validation
on matched ABI collateral, not evidence for later RTL timing candidates.

The initial image SHA256 was
`66eea06cb412c3355ce4fdcd4a15a7c2145aef902dd64ad34abf084314187da4`; the repaired software image is
`3d6a2578764b637eff9da44ea61e557ba34ea72749b4c80be62cd96309e57114`
(`/scratch/pengxu/lh-512199-bundled-fixed.img`). The final fixture executable
was staged separately against that mounted runtime; SHA256
`80109e3777434c0f24ce92e75f602c49c7de34e5ff6cbd8a6abd601291711237`.
Manifests preserve the exact store paths and original hardware revision.

Results:

- Restored server tracing: gateway multiplication smoke passed **100/100**;
  exactly 100 CSV rows matched client IDs, had ordered RX/decode/handler/encode/TX
  timestamps, and recorded CPU 44 for RX and TX. The existing latency plotter
  successfully produced software breakdown and histogram PDFs. No new FPGA/CPU
  clock correlation or full hardware latency figure is claimed.
- The bundled peer campaign passed **88/88** checks, observing 396 nested packets
  with checked asymmetric program/version/procedure fields, source/destination
  addresses and ports, XIDs, and complete application bytes. Remote calls, local
  calls, remote forwards and local forwards passed at 48, 512 and 1024 bytes.
  Local cases produced no packets at the peer responder; this is not a CMAC
  trace proof of absence of all physical traffic. Eight parallel futures received
  deliberately reordered replies correctly.
- Default-stack nested calls rejected; explicit errors, oversized encoding,
  exhausted fiber and software pending pools returned the expected error, and
  subsequent calls progressed. Twenty cycles each confirmed cancel completion,
  exactly 16 outgoing admissions out of 32 attempts, retirement and capacity reuse.
  Incoming saturation returned 16 successful deadline completions and 16 hardware
  errors; subsequent requests progressed.
- A deadline expired during a quiet interval, with the outer response in about
  1.99 s (the 1 s deadline plus sweep granularity). Deliberately late replies did
  not corrupt subsequent calls. The final campaign emitted 109 terminal server
  rows: 89 deferred, six forwarded and 14 inline, including four expected errors.
  Local child requests have their own rows; hardware admission rejections do not.
  Deferred active/wait arithmetic and forward timestamp ordering were checked.
- Killing a service with **16 confirmed outgoing requests** (fixture deadlines
  extended to 60 s) completed kernel recovery phases 0/1/2. Its replacement passed
  **31/31** checks including restored 16-slot capacity, after receiving the 16 old
  replies. No reset, module reload or FPGA programming occurred between this kill
  and replacement. These late replies were sent before fresh nested submissions;
  a late response racing an identical port/XID after process-lifetime reuse is
  not established by this test.
- Four-worker add and mul baselines from the second Enzian each passed **100/100**
  independently decoded wire replies with checked arithmetic and asymmetric XIDs.
  These serial calls were handled on CPU 44; registering four workers is not
  evidence of simultaneous work on all four CPUs. Both services fully retired.
  Final audit found no new kernel faults, no kernel trace overruns/drops and no
  remaining campaign processes. The module/bitstream remain loaded; no other
  reservation or process was changed.

The initial raw-echo fixture incorrectly treated the 48-byte inline receive
extent as an exact short wire length; its 16-byte trial is retained as a fixture
failure. The corrected 48-byte remote trial then exposed a real runtime bug:
`core_eci_tx` copied 24 nested inline bytes after the 64-byte generated datatype,
but that datatype already contains those bytes at offsets 40–63. The peer saw
zeros in those positions and intact overflow bytes. `a4600bc` places the copy
inside the descriptor, with native and sanitizer regression checks for every
length 1–24 and adjacent-byte preservation. The full campaign passed after this
software-only repair.

The first pending-kill trial retired according to dmesg but failed its trace
assertion: module reload had reset the event enables. It is retained as an
incomplete trial, not counted as a pass. With trace events explicitly re-enabled,
the second trial captured phase-2 retirement and passed replacement checks.

Evidence: ignored `out/bundled-validation/` contains build recipes/logs, image and
fixture hashes, run.py transcripts, scripts, CSV checks/plots and downloaded
evidence. Shared evidence is `/scratch/pengxu/lh-bundled-validation/`, including
`nested-results-final.json`, `kill2/{kill-result,after-kill-results}.json`, captured
packet hex, all failed trials, manifests and kernel traces. The checked-in peer
runner is [nested_validation_client.py](../../tools/enzian/nested_validation_client.py).
No DECERR board investigation was performed; its RTL repair is accepted by the
[separate simulation regression](../hardware/b4-l2-timeout-512199.md#decerr-repair-accepted-by-simulation-2026-09-18).

This substantially covers the generic B1–B4 transport/runtime checks, but does
not close final-batch timing acceptance, the historical Sven-specific application
fixtures, explicit CSR readback, full local hardware trace correlation, or the
separate B5 startup and B6 Dandelion/application gates. The 512199 setup timing
failure is unchanged; no later pipeline was queried in this campaign.
The updated expect flow was then exercised on a third cold boot: QLM and lane
validation passed on attempt one, the matching driver loaded, and another 100
adder RPC replies passed. No retry was needed in this live trial; retry behavior
is covered by the automated tests. Two temporary server-setup mistakes (missing
log parent, then redundant IP assignment) were corrected before the final RPC
check. The board remains on the CI image, with capture overrides restored.

A final bounded UDP trial bypassed ARP with a temporary static neighbor entry.
The packet capture contains six FPGA metadata packets and 31 outgoing UDP
requests to the configured FPGA MAC/IP, but no trace data; the read timed out.
The neighbor entry was removed afterward. This rules out unresolved host ARP
as the sole cause of that trial's failure. Remote-fault flapping persists;
network trace dumping remains unverified even though JTAG readout and normal
RPCs work. The third boot's JTAG head (64 KiB), tail (1 MiB at byte offset
131140160), static ILA, and BDK gate summary are retained under `gated/`.

## CMAC trace retest after workspace consolidation — 2026-09-23

Work resumed on `platform/master` at `9786470`; the obsolete `plat-master`
worktree was removed after preserving its hardware-test evidence. The board
trial used the matched artifacts from successful pipeline **512381**, revision
`5adb1e7` (hardware job **2837292**, software job **2837291**). This tests the
trace polarity/ARP fixes, not the later merged nested-RPC hardware.

- The first boot lacked the BDK CCPI lane report. The current expect flow
  automatically cold-reset and reprogrammed; attempt two passed QLM8..13 and
  lanes 0..23, loaded the matching driver, and passed **100/100 add RPCs**.
- The trace CMAC aligned with RX status good and local fault clear, but remote
  fault continued alternating. Resetting only its TX core and GT TX datapath
  did not resolve this. All reset outputs were deasserted afterward.
- zuestoll12 received trace metadata, but the requested 64 KiB dump timed out
  on its first read and produced **zero bytes**. A separate zero-length command
  (sequence 35, 21 attempts) also timed out while a temporary static neighbor
  bypassed ARP. The neighbor was removed afterward.
- Retriggering unsolicited metadata after those failures succeeded, reporting
  write slot 2393017, sample loss set and DMA error clear. Thus the responder
  could still emit metadata; a DDR read stall alone does not explain the failed
  command round trip. This does not establish whether requests reached the
  FPGA or whether command replies were transmitted and lost.

Network trace dumping remains unresolved. Switch-side link/FEC counters and
port configuration are the next requested evidence; leaf1 access details were
requested from the maintainer. RS-FEC was already enabled in the existing CMAC
configuration; this trial does not justify changing it. No new RTL build or
CI polling was started.

Compact evidence is `out/hardware-tests/cmac-20260923/summary.json`; packet
capture and receiver files remain on zuestoll12 under
`/dev/shm/cmac-20260923/` (volatile storage). Copying them to shared storage was
rejected by automatic approval review and was not performed. The original pipeline 512199 bitstream and matching prior software image
`/scratch/pengxu/lh-512199-bundled-fixed.img` were restored: boot passed on the
first attempt, driver load/version verification passed, and the original network
configuration was restored. No application was running before this trial; none
was started after restoration.


## Simplified LCI-only DC trial — 2026-10-02

**Failed; not hardware acceptance.** Pipeline 515621, hardware job 2861585,
revision `2699f580a6c14fb00fa8cff3857dc5b83f890a89`, used the matched CI deployment
image, bitstream, LTX and trace map on zuestoll14. This is the existing routed
128 KiB design with the selected non-locking DC and UL removed. See the
[compact evidence](../../data/eci/board-evidence/lci-only-20261002.json).

The first boot lacked the required CCPI report. The automated second full cold
reset passed QLM8–13 CDR lock and all CCPI lanes 0–23. Driver load, revision
verification and four-worker service registration passed. RPC client creation
then failed with `RPC: Unable to receive`; no application RPCs completed. The
DUT console subsequently failed to return a prompt. The planned second trial was
not run, and the board was not reset after this failure.

The complete F_MAC1 capture is 139,951,936 bytes (2,186,749 slots), unwrapped,
with no pre-freeze loss/DMA error or decoded loss marker. A second frozen read
has the same SHA-256. The matching-map decoder found no DCS error events.
The decisive bypass RX sequence is:

1. Read `0x100` receives `PEMD` (`R12 → RA3`, exclusive permission).
2. Read next RX control `0x80` waits for prior data invalidation.
3. LCI `0x100` sends `FLDX_EH`; the trace confirms dynamic/static boundary acceptance.
4. CPU returns data-bearing `HAKD`, dmask 15. The DC accepts `A31d` and enters `WDD`.
5. No `WDDA` or `LCIA` follows in the capture; the `0x80` read remains unanswered.

Source inspection identifies the corresponding integration gap:
`DcsRxAxiRouter` leaves AW/W/B blocked and only implements AR/R service. The DC's
AXI write adapter waits for B completion before returning write completion. The
selected endpoint therefore exposes an RX writeback dependency the application
router cannot service. This is not a captured forbidden ROM transition. Actual
AXI handshakes are not traced on the board; the subsequent directed
[real-DC/RX-router diagnostic](validation.md#rx-writeback-regression-reproducer)
reproduces the missing service. Correct RX writeback disposition remains open.

This also makes the model's independent writeback-service premise a concrete
implementation blocker. The bounded model result remains conditional on that
premise. No addr/PREEMPT implementation or workaround was added during this test.
The DUT remains programmed with tracing frozen; original VIO values and all
artifacts/logs are retained in `out/board/20261002-lci/` and matching shared/ba2
paths recorded there. Preserve that state before further board work.

### RX regression comparison: RA2 to RA3 — 2026-10-02

The RX failure is newly exposed by the **R12 → RA3 permission change**, not by
new software stores or a newly introduced FLDX opcode. Re-decoding the final
September 28 successful capture (`5cda901`, DC `7b5cc87`) gives three complete
exchanges for bypass RX overflow `0x100`:

| Build | Read response | LCI forward | CPU response |
| --- | --- | --- | --- |
| September 28 successful baseline | `PSHA` / RA2 (shared) | `SINV_H`, dmask 0 | `HAKD`, dmask 0 |
| October 2 selected simplified DC | `PEMD` / RA3 (exclusive) | `FLDX_EH`, dmask 15 | `HAKD`, dmask 15 |

The first older exchange is at trace ticks 32493095802 (RLDD), 32493095903
(PSHA), 32493097336 (SINV_H), 32493097375 (HAKD). The failing exchange is at
32981670372 (RLDD), 32981670476 (PEMD), 32981671951 (FLDX_EH), 32981671998
(HAKD). These are different captures' clocks, not comparable wall times.
[Retained excerpts and provenance](../../data/eci/board-evidence/rx-ra3-comparison-20261002.json)
include an additional older RX exchange and TX `0x8000` exchange: TX already
used FLDX_EH/data-bearing HAKD after acquiring exclusive permission.

The older build selected `gen_dc_protocol/dc_stmc_auto_lock/cc_rom_from_cc_tbl`
through `create_project.tcl`. Its R12 miss enters `s1__2pRA2`, then RDDA emits
RA2/PSHA. The historical `replyRA3` branch was not the selected baseline.
The new `dc_stmc_lauberhorn` generator instead enters `s1__3pRA3`, then emits
RA3/PEMD. Both implementations already map F31 to FLDX_EH. Shared RX previously
used F21/SINV_H, so it did not request the returned data now seen on RX.

A data-bearing HAKD here is **not evidence of a CPU store**. The local THX
`legacy/remote_fsm.txt` admits the load-only sequence `LS → IS_d`, `AE_d → E`,
`FE_d → FA_d`; a home forward requests data from the exclusive owner. That FSM
does not distinguish payload cleanliness. The kernel RX source is unchanged
between these two revisions. The causal integration mismatch is exclusive RX
permission plus data-returning invalidation against an RX AXI endpoint with no
write completion service. Calling this merely a “dirty CPU line” obscures the
change that exposed it.

The directed RTL diagnostic subsequently passed all four expected observations:
no-data HAKD completes without a write, data-bearing HAKD on RX queues AW but
stalls W for 200 cycles, a test sink withholding B preserves the wait, and
releasing B completes WDDA/LCIA and the pending RX control read. It connects the
actual DCU, AXI write adapter and generated RX router, with the environment
limitations listed in the [reproduction instructions](validation.md#rx-writeback-regression-reproducer).
The six existing selected-ROM DCU checks were also rerun. The four diagnostic
passes establish the obstruction and intervention, not production acceptance.
[Run evidence](../../data/eci/board-evidence/rx-writeback-rtl-20261002.json) records
source hashes and the observed outcomes.
