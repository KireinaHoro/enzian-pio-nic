# ECI progress evaluation

These experiments evaluate independent worker/bypass progress during long coherent
RX waits and worker-issued synchronization. Results are bounded observations on
the named revisions. The [queue-pair design](../hardware/queue-pair-design.md)
defines the coloring argument, [ROM simplification](../hardware/lauberhorn-rom.md)
addresses directory lock pressure, and [ECI implementation](../hardware/eci-implementation.md)
defines the selected early-NACK behavior. Neither coloring nor finite board
success establishes freedom from all shared-resource blocking.

## Builds and interpretation

| Experiment | Matched artifacts | Scope |
| --- | --- | --- |
| Earlier timeout screening | 515944, RTL `8cc9ad58` | Historical timeout observations on a payload-isolation-vulnerable image; not corrected-image acceptance |
| Corrected functional baseline and contention | 516112, `b2117e9625cca7ce256b44db34a5f2a46a952622` | Two 100-RPC cold-boot baselines, followed by finite near-timeout contention |
| Controlled GSYNC before early NACK | Same 516112 hardware/software; application-only permission-change probe | 240 RPCs; 160 explicit permission-change GSYNCs overlapping two/three peer holds |
| Controlled GSYNC after early NACK | 516232 FPGA `6f5fa076adbad2002681d3051c7056522929b7a1`, corrected module `af0f64fe` | Two fresh cold-boot baselines (200 measured + 32 warmups), then 240 controlled RPCs; one/two observed stalled peers |

516112 contains TX scrub/bounds, RX tail masking and inactive-domain admission
corrections. The [original disclosure](../../data/eci/board-evidence/cooperative-20261003-isolation-blocker.json)
was an RTL reproduction of successor-visible TX storage, not a board exploit.
The [correction record](../../data/eci/board-evidence/cooperative-20261003-isolation-correction.json)
records 50 passing fast ECI tests (one ignored), and the
[matched baseline](../../data/eci/board-evidence/isolation-516112-20261004.json)
records the two cold boots. Those functional trials do not establish adversarial
multi-domain isolation. The implemented rules belong to the
[cache-line interface](../architecture/hw-sw-interface.md#payload-cleanup-across-owners).

The first 516232 module had an idle-activation failure caused by invalid per-CPU
storage placement. Module `af0f64fe` corrected it without changing the FPGA;
only the corrected-module results below establish the successful follow-up.
The [failure ledger](../../data/eci/board-evidence/gsync-516232-results-20261005.json)
and [corrected-module ledger](../../data/eci/board-evidence/percpu-gsync-516232-20261005.json)
retain original capture and artifact provenance. This is not an unresolved
GSYNC blocker. Routed timing was not closed: 516112 had WNS −0.396 ns with
7,227 failing setup endpoints; 516232 had WNS −0.102 ns with 893.

## Original coloring motivation

The coloring discussion predates the September/October implementation pivot.
Commit `41e14a3433626b472f848f8915c0b26b54f8aa03` (June 12, 2026) expands the
original `docs/ECI-2F2F-DCU-PROGRESS.md`; commit
`437058d21da519f432f253f1a63d7ce5a22669cb` later that day is explicitly titled
“try to explain the observed overflow timeout with color model.” Its crash
section records TX offsets `0x8000`, `0x8100`, `0x8200`, `0x8300`, citing a local
screenshot, and proposes the mutually blocking RX/TX control-color cycle.
That explanation survives in [coherence progress](../hardware/queue-pair-design.md#motivation-avoid-head-of-line-blocking).
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
commented-out lock calls and the lock field. Thus these recorded board tests do
not rely on that workaround. `claim_lock` still protects scheduler/worker
bookkeeping; eliminating coherence-induced serialization does not eliminate
ordinary software synchronization requirements.
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
## Trace-analysis validity

The RX-hold analyzer uses corrected timestamps, not the decoder's internal
epoch-extended raw counter: recorder bubbles can make the latter advance by an
entire 46-bit period. It rejects half-period spans and genuine wraps; regression
coverage includes bubbles, source-pipeline compensation and peer overlap.
The [515944 ledger](../../data/eci/board-evidence/cooperative-20261003-contention.json)
contains reprocessed counts with unchanged capture/client/TAD provenance. Its
all-but-one interval retains 4,656 descriptor/TX-submit pairs while three peers
hold for at least 26,656,301 cycles. Earlier uncorrected overlap counts are not
valid evidence. All corrected-image results below use corrected analysis.

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

The board validation routine disables RT bandwidth throttling before module
load and checks readback. The second experiment below applied that setting
before contention, permitting comparison with the throttled run.

## 516112 second cold boot: throttling disabled

The second verified cold boot passed its 100-RPC baseline. Contention used
`kernel.sched_rt_runtime_us=-1`. An interrupted setup and its preserved trace
are excluded from the final measurement range, which contains no intentional
freeze/resume gap.

The measured run (`out/board/hol-516112-boot02-three-retry`) passed 3,128 add, 3,128 echo
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
## Contention result and limits

The finite all-but-one checks passed on two cold boots of corrected 516112,
including the second exposure with RT throttling disabled. No TAD timeout,
worker fault or progress failure was observed in the measured traffic. This
supports independent progress under the tested near-timeout holds; it does not
prove all worker permutations, infinite stalls, adversarial isolation or the
four-worker tail distribution. Routed timing remains open (WNS -0.396 ns,
7,227 failing setup endpoints).

The [matched ledger](../../data/eci/board-evidence/worker-hol-516112-20261004.json)
records artifact hashes, job IDs, command inputs, corrected analysis, interrupted
attempts and limitations.

## Controlled GSYNC results

On October 4, an application-only probe on matched pipeline **516112**
(`b2117e9625cca7ce256b44db34a5f2a46a952622`) reproducibly generated RPC tails by
changing a populated page's permissions on a worker while other workers waited
in coherent RX reads. All **240 RPC replies** validated. All **80 mprotect
requests** generated two GSYNCs with PPVID **47**, matching the probe's reported
Linux CPU 47. The 80 explicit `getpid` syscall controls generated none.

This strengthens the historical two-outlier interpretation above:
a worker's synchronization can wait behind a peer's blocked RX even though the
FPGA answers GSYNC immediately. It does not establish which interrupt or
instruction initiated the historical GSYNCs. The experiment intentionally invokes
TLB maintenance through `mprotect`; no transport barrier, RTL, address-layout,
DESCHED or kernel implementation was changed.

The one-thread probe changes an already populated private anonymous page from
read/write to read-only, then restores read/write. CPU CLOCK_MONOTONIC_RAW
measures the two calls separately. Twenty repetitions of each mode (no-op,
`syscall(SYS_getpid)`, and permission change) ran in every phase.

| Programmed RX hold | First mprotect median | Restore mprotect median | Permission-change RPC E2E median |
| --- | ---: | ---: | ---: |
| 50 ms, first phase | 40.333 ms | 50.004 ms | 90.450 ms |
| 10 ms | 9.071 ms | 10.004 ms | 19.188 ms |
| 1 ms | 0.358 ms | 1.004 ms | 1.472 ms |
| 50 ms, repeated | 40.314 ms | 50.004 ms | 90.424 ms |

The first call encounters the remaining peer hold, whereas the restoration can
encounter a newly issued full-length peer RX. These are measured durations, not
an assumption that every call must cost exactly one hold interval. All 160
explicit permission-change GSYNCs overlap two or three fully observed peer RX
holds. The final TX commit follows the latest outstanding peer's NACK in every
permission-change RPC: minimum **4.51 us**, median **4.64 us**, maximum **40.18 us**.
CPU-side global retirement itself is not directly traced.

The first no-op request also had a PPVID-47 GSYNC and a **44.412 ms**
RX-descriptor-to-TX-commit interval. Commit followed the last peer NACK by
**6.03 us**. Its explicitly measured no-op took only 70 ns, so that delay is
outside the measured no-op. The remaining 79 no-op requests contain no GSYNC.
This additional cold-request tail remains visible; its initiating instruction
is unresolved. The syscall controls take at most **10.32 us** inside getpid.

The probe prefaulted its private page and thread-local message buffer. It ran on
Linux CPU47 / datapath4 after add/mul activation placed peers on datapaths1–3,
with RT throttling disabled and no ordinary DESCHED intentionally induced.
The 184,771,456-byte final capture has zero decoded loss during all 240 measured
requests and exactly 240 descriptor-to-commit pairs. An earlier freeze/resume
loss marker precedes probe activation. Matched dmesg snapshots and all eight
TAD checks passed. The
[evidence ledger](../../data/eci/board-evidence/gsync-reproduction-516112-20261004.json)
retains binary/source hashes, distributions, capture/health records and limits;
raw outputs remain under `out/gsync-repro-20261004/` and shared scratch.
[Validation](../development/validation.md#controlled-gsync-and-early-nack-checks)
describes probe construction, workload and collection.

## Corrected-module early-NACK measurement — October 5

With the same 516232 FPGA and the corrected module, all **240 controlled RPCs**
passed, including 80 permission changes and 80 syscall controls. The probe ran
on **Linux CPU46 / physical worker2 / NIC datapath3**. All 160 permission-change
GSYNCs had PPVID46; 107 overlapped one or two fully observed peer RX holds,
while 53 had no such overlap. The observed peers were datapaths 1 and 2, so this
run does not establish three simultaneously stalled peers.

| RX hold setting | First mprotect median | Restore median | Socket RPC median |
| --- | ---: | ---: | ---: |
| 50 ms, first | 18.455 us | 17.940 us | 146.197 us |
| 10 ms | 18.340 us | 17.925 us | 145.471 us |
| 1 ms | 18.655 us | 17.935 us | 146.391 us |
| 50 ms, repeated | 18.365 us | 17.925 us | 146.681 us |

The prior hold-dependent millisecond waits disappeared in these requests.
For the 196 observed peer overlaps, trace-adjusted GSYNC-to-NACK was **10–35 ns**
(median 10 ns). This is the FPGA event interval, not CPU synchronization
retirement or complete coherent-read latency. Subsequent full 1 ms peer holds
were observed, confirming that release did not remain sticky in that phase.
Other GSYNCs interrupted longer holds; do not interpret this as a clean test
that every later 50 ms read retains its full timeout.

All 240 descriptor-to-commit spans correlate with client replies, with no decoded
loss during the experiment. The complete unwrapped **864,557,952-byte** capture
was saved and hash-verified before resetting for latency measurements. DMA status
was clear and pre-freeze loss status was zero. Paired kernel/TAD assessment passed.
The [correction and GSYNC ledger](../../data/eci/board-evidence/percpu-gsync-516232-20261005.json)
records provenance, distributions and limits. Raw evidence remains in
`out/gsync-516232-fixed/`. These socket-client results are separate from the DPDK
latency comparison. FPGA timing remains open.

## Remaining questions

The controlled permission-change experiment establishes a reproducible
hold-dependent synchronization delay and its removal in the tested early-NACK
cases. It does not identify the initiating instruction of the two historical
outliers or the extra cold-request GSYNC. CPU global retirement is not directly
visible in FPGA traces. Three-peer early-NACK behavior, arbitrary interrupt
origins, all worker permutations and four-worker tail distributions require
separate evidence. A one-shot pulse also cannot promise release for a read that
enters `waitDesc` after that pulse.

Finite near-timeout contention does not determine a universal RX timeout bound
or prove infinite-stall progress. Adversarial isolation and complete recovery are
separate from functional RPC success. Follow the
[application validation scope](roadmap.md#current-status--october-8-consolidation)
for further cases; the historical dedicated campaign is closed.
