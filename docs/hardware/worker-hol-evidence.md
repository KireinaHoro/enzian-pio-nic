# Worker head-of-line blocking: historical failures and matched checks

For the directory-capacity source of DC head-of-line blocking, see
[ROM simplification](lauberhorn-rom.md): why local locking creates pressure,
the selected nonlocking protocol, and its area/timing evaluation. The board
observations below cover their recorded workloads and do not establish that
all shared-resource blocking has been eliminated.

## Original coloring motivation

The coloring discussion predates the September/October implementation pivot.
Commit `41e14a3433626b472f848f8915c0b26b54f8aa03` (June 12, 2026) expands the
original `docs/ECI-2F2F-DCU-PROGRESS.md`; commit
`437058d21da519f432f253f1a63d7ce5a22669cb` later that day is explicitly titled
“try to explain the observed overflow timeout with color model.” Its crash
section records TX offsets `0x8000`, `0x8100`, `0x8200`, `0x8300`, citing a local
screenshot, and proposes the mutually blocking RX/TX control-color cycle.
That explanation survives in [coherence progress](queue-pair-design.md#motivation-avoid-head-of-line-blocking).
It was a source-supported hypothesis, not a captured proof of every pending
DCU transaction at the instant of failure.

Tracked captures independently preserve the relevant symptoms. Dates below
are capture-directory dates or dates the evidence was committed; they do not
establish an exact acquisition time or a matched build SHA.

| Evidence | Recorded failure | Provenance |
| --- | --- | --- |
| [March 13 iperf](../../data/eci/dcs_trace/iperf-timeout-0x8080-2-2026-03-13/panic.txt) | LFB entry timeout, low address `0x8080` | Directory date; commit `c7a0a1bb` March 13 |
| [March 18 link-up](../../data/eci/dcs_trace/link-up-timeout-0x80-2-2026-03-18/panic.txt) | LFB timeout `0x80`, then external abort and panic in `napi_poll` | Directory date |
| [Iperf without spinlock](../../data/eci/sys_trace/iperf-tx-nospinlock-timeouts/panic.txt) | LFB timeout `0x8100`, fault in `netdev_xmit`, iperf on CPU 24 | Commit `a88148a8`, June 14 |
| [Second iperf without spinlock](../../data/eci/sys_trace/iperf-tx-nospinlock-timeouts-2/panic.txt) | LFB timeouts `0x8380`, `0x8280`, `0x8600`, `0x8080`, `0x8180`; `netdev_xmit`, CPU 12 | Commit `9e2fbabd`, June 15 |
| [RPC-invoked bypass](../../data/eci/sys_trace/rpc-invoked-bypass-timeout-0x8080/panic.txt) | LFB timeout `0x8080`, external abort in `netdev_xmit` | Commit `c14f2f63`, June 18 |

The June captures are the closest preserved examples to the original coloring
motivation. They include compressed system traces beside the panic logs. They
are not controlled measurements of one near-timeout application worker delaying
another. In the [older FSM trace analysis](../../data/eci/sys_trace/remote_fsm_trace_notes.md),
normal RX invalidations complete in about 40–44 ticks until a final `SINV_H`
has no visible acknowledgment; a later GSYNC response establishes that the
capture continues beyond that point. The analysis leaves the exact blocking
resource unresolved.

The [September 18 pipeline 512199 failure](../../agents/runtime-applications.md#historical-b4-parking-and-recovery) is distinct:
an RX read remains unanswered after an earlier invalidation completed normally,
and bypass subsequently stops too. The investigation reproduces an AXI
crossbar DECERR completion-accounting underflow that prevents later valid
reads. It must not be presented as proof of a DCU-color collision.

## Bypass lock status

The exact tested revision `b2117e96` has no `dp_lock`, and neither
`netdev_xmit` nor `napi_poll`/`poll_once` in `sw/kmod/bypass.c` takes a spinlock
around the ECI RX/TX calls. Commit `bf20f8f5` (June 15) removed the already
commented-out lock calls and the lock field. Thus the current board tests do
not rely on that workaround. `claim_lock` still protects scheduler/worker
bookkeeping; eliminating coherence-induced serialization does not eliminate
ordinary software synchronization requirements.

## Current verification scope

The corrected image is pipeline 516112, RTL/software revision
`b2117e9625cca7ce256b44db34a5f2a46a952622`; baseline provenance is recorded in
[the board ledger](../../data/eci/board-evidence/isolation-516112-20261004.json).
The prior 515944 contention campaign is historical evidence, not acceptance of
this corrected image. Near-timeout trials must record actual finite holds,
physical worker overlap, independent RPC completions, TAD state and kernel
faults. A successful finite campaign does not establish universal freedom from
HOL blocking or the four-worker tail-latency distribution; that distribution
will be measured using the separately developed benchmark infrastructure.

## Meaning of the earlier timeout probe

The final 515944 screening bracket was 26,857,015 passing versus 26,857,032
failing programmed cycles: 134.285075 versus 134.285160 ms at 200 MHz.
The passing point was on boot 26 and the failing point on boot 25; it is not a
same-boot, repeated determination of an architectural timeout constant. The
search stopped before full maximum-boundary qualification. The separately
repeated finite contention setting was 26,656,301 cycles (133.281505 ms),
approximately 1.004 ms below that screening boundary. Request-to-NACK trace
intervals also include protocol overhead. Do not call the 85 ns-wide screening
bracket a guaranteed timing margin or transfer it to another bitstream.

## Timestamp-analysis correction

The October 4 campaign exposed an analyzer bug: `analyze_rx_holds.py` used the
decoder's internal epoch-extended raw timestamp instead of its already corrected
timestamp. Recorder bubbles can make that internal value advance by a whole
46-bit counter period, inflating durations and overlap counts. The analyzer now
uses the corrected timestamp and rejects captures spanning half a counter
period or crossing a genuine counter wrap. A binary trace regression covers a
bubble between a read and NACK, source pipeline compensation, peer overlap and
rejection of a real wrap; all eight hold-analysis tests passed.

All three saved 515944 measurement ranges were reprocessed. The
[historical ledger](../../data/eci/board-evidence/cooperative-20261003-contention.json)
now contains corrected counts and duration histograms, with unchanged capture,
client and TAD provenance. Its all-but-one case still shows 4,656 descriptor
and TX-submit events from the active worker while three other workers hold
requests for at least 26,656,301 cycles. Earlier numeric overlap counts are
superseded; the finite progress observation survives reanalysis.

## 516112 first cold boot: throttling enabled

The matched baseline passed 100 RPCs after a verified cold reset. A standalone
26,656,301-cycle hold then passed a 182.57-second monitored exposure. The
contention case (`out/board/hol-516112-boot01-three`) kept the same programmed
hold for a 180-second control-read interval. Its 15-second peer workload returned
2,929 add, 2,928 echo and 55,491 bypass responses, without retries or pending
requests. All eight TAD snapshots and the continuous kernel log passed; the
idle-dispatch and domain-fault interrupt counts did not increase.

The corrected trace analysis observes three simultaneous physical-worker holds
while datapath 1 delivers 4,486 descriptors and submits 4,486 replies, with
4,481 ordinary handoffs and 41,487 bypass TX submissions during those overlaps.
The measurement range has no lost samples; incomplete pairs at its boundaries
are excluded. The largest RX read-to-response interval is 133.281945 ms.
Pairing system-interface RLDD/RLDI/RLDX requests with accepted PSHA/PEMD responses
by slice and unaliased cache line yields 251,734 complete pairs, at most
133.282190 ms. Two leading responses and one trailing request are boundary
incomplete. This excludes static-link/CPU completion latency and is not an
architectural deadline proof.

Client maxima were 79.37 ms for add, 199.86 ms for echo, and 18.25 ms for bypass.
Trace-local activation-delivery to first RX access reaches 199.757845 ms,
whereas TX commit-read to submission is at most 1.955 microseconds. These are
independent overlapping measurements, not additive E2E components. The DUT had
`kernel.sched_rt_period_us=1000000` and `kernel.sched_rt_runtime_us=950000`;
workers are SCHED_FIFO. Thus the recurring roughly 50 ms-per-second delays
have an RT-bandwidth explanation to test, rather than demonstrating a
200 ms outstanding coherence read.

The test routine now disables RT bandwidth throttling before module load,
checks readback and records the setting. The second boot was already underway
when this change was made, so its setting was applied explicitly before the
contention run and retained separately in the ledger. This does not require a
new bitstream or deployment closure.

## 516112 second cold boot: throttling disabled

The second verified cold boot passed its 100-RPC baseline. After setting
`kernel.sched_rt_runtime_us=-1`, the first contention attempt stopped because
characters were lost from the serial shell's initial PS2 assignment. The echo
setup payload never ran; 9,355 mul allocation RPCs had already passed. The
harness preserved the full FPGA trace before proceeding. The console helper
now sends the initial commands separately, one byte at a time, with a shorter
continuation prompt; four shell-framing tests passed. A recovery precheck
exceeded its 60-second harness deadline but later produced a clean assessment.
The existing mul/bypass services were reused, and tracing resumed. The final
measurement range excludes that intentional freeze/resume gap.

The retry (`out/board/hol-516112-boot02-three-retry`) passed 3,128 add, 3,128 echo
and 54,950 bypass replies without retries or pending requests. A 180.01-second
control probe completed 133,613 reads; the host-side bound places the client
completion within 156.733 seconds of control launch. TAD/kernel checks passed,
and idle-dispatch/domain-fault interrupt counts did not increase. The measured
trace has no decoded lost samples and no activation parity mismatches.

During three-peer holds the trace records 3,507 descriptor/TX-submit pairs on
datapath 1 and one on datapath 2, plus 31,129 bypass TX submissions. It also
records echo domain 2 migrating from physical worker 0 to worker 1; after that,
parts of the run have two stalled peers rather than three. Thus three-peer
coverage is established for the counted intervals, not every instant of the
exposure. The largest complete RX request-to-NACK interval is 133.281545 ms.
The system-interface read pairing finds 260,642 complete pairs, at most
133.282010 ms, with four unmatched leading responses and four trailing reads.

Add's maximum client latency fell from 79.367 ms to 0.251532 ms and its p99 was
0.193652 ms. Echo's p99 was 0.194281 ms, but two outliers remained: 124.211491 ms
near the start and 123.401954 ms at 8.438 seconds. Bypass had one 18.704387 ms
outlier. These finite Python/socket measurements establish neither the new
DPDK baseline nor four-worker tail-latency acceptance. The recurring roughly
50 ms add delays disappeared; the remaining outliers must remain visible.

The corrected protocol analysis bounds queue-to-dispatch at 0.163260 ms,
activation-delivery to first RX access at 0.153660 ms, and TX commit-read to
submission at 1.970 microseconds. These intervals overlap and must not be
added into an E2E total. Further correlation of the echo outliers is recorded
in the machine-readable ledger.

The two long echo windows are localized more tightly. In both, the TX
acquisition RLDD receives a full-data PEMD after **0.510 microseconds**. The next
TX-control RLDD (commit) is not visible until roughly 123 ms later; its response
then takes about 1.5 microseconds. There is no TX upgrade request waiting in
between. This rules out a pending TX-acquisition response as the cause of these
two gaps. It does not identify the intervening CPU/software/barrier delay:
Linux scheduling/page-fault/instruction traces were not captured. The second
event follows the observed echo-worker migration. Do not label the remaining
tails as resolved or infer their exact cause from FPGA events alone.

A further review of the same saved measurement range finds that each echo
commit follows another physical worker's long RX hold completing: datapath 2
returns a NACK 23.660 microseconds before the first commit, and datapath 3
returns one 17.640 microseconds before the second. Bypass submits 462 and 451
responses respectively during the two descriptor-to-commit gaps. GSYNC requests
appear 15.645 and 8.455 microseconds after echo descriptor delivery; each visible
FPGA GSYNC response follows in two application-clock cycles (10 ns). A further
GSYNC pair in the first gap also responds in two cycles. Thus the visible FPGA
GSYNC responder is not holding these requests for 123 ms.

This temporal association makes CPU-side cross-core synchronization a candidate
for investigation, including barriers or translation maintenance. It does not
prove which instruction stalls or that the peer NACK causes its release. The
matched TX path contains `dmb sy` before its commit read; instruction/kernel
tracing or a controlled reproduction is still needed to distinguish causes.
The [additional evidence](../../data/eci/board-evidence/echo-outlier-516112-20261004.json)
retains capture provenance, surrounding event counts and accepted GSYNC headers.

Scanning the entire loss-free measurement range strengthens the issuer-specific
hypothesis. Of 7,094 accepted GSYNC requests, exactly one has PPVID 44 and one
has PPVID 45; none has PPVID 46 or 47. The two occur inside the delayed echo
service intervals on datapaths 1 and 2 (Linux CPUs 44 and 45). Both have other
workers in long RX holds. These are also the only two of 6,256 descriptor-to-TX
commit intervals exceeding 1 ms; the next longest is 35.205 microseconds.
Thus the proposed equivalence has no counterexample in this measured window,
with only two positive observations. The GSYNC field layout establishes PPVID
as header bits 5:0; direct equivalence to Linux CPU numbering has not been
independently calibrated, though both observed IDs match the delayed workers.

There are 5,630 GSYNC requests during fully observed near-timeout RX holds.
Some are followed by promptly completed worker requests before a peer's held
RX completes. Those originate from non-worker PPVIDs and do not contradict a
stall of the issuing core. The third GSYNC inside an outlier has PPVID 42.
The [scan evidence and reproduction script](../../data/eci/board-evidence/gsync-worker-tail-516112-20261004.json)
record the ID histogram and examples. Neither CPU-side global retirement nor
the interrupt/TLB-maintenance trigger is visible in this FPGA trace. A matching
GSYNC ID is stronger evidence than temporal proximity alone, but does not prove
that exact initiating instruction or a universal causal equivalence.

## Outcome and retained state

The finite all-but-one checks passed on two cold boots of corrected 516112,
including the second exposure with RT throttling disabled. No TAD timeout,
worker fault or progress failure was observed in the measured traffic. This
supports independent progress under the tested near-timeout holds; it does not
prove all worker permutations, infinite stalls, adversarial isolation or the
four-worker tail distribution. Routed timing remains open (WNS -0.396 ns,
7,227 failing setup endpoints).

The [matched ledger](../../data/eci/board-evidence/worker-hol-516112-20261004.json)
records artifact hashes, job IDs, command inputs, corrected analysis, interrupted
attempts and limitations. The DUT retains `sched_rt_runtime_us=-1`, a safe
post-exposure hold of 10,000,000 cycles, and the existing services. Tracing is
frozen after the final successful capture; no reset followed it.

## Controlled GSYNC reproduction

The [worker-local permission-change experiment](gsync-worker-reproduction.md)
reproduced hold-dependent tails in 80 RPCs with 160 matching PPVID-47 GSYNCs.
It strengthens the synchronization hypothesis without identifying the trigger
of the historical two outliers.
