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
The last recorded state was 2812450 serving add at `192.168.129.200`, with
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
