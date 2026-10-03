# ECI implementation and evidence

October 2, 2026, branch `feat/cooperative-yield`. The selected address map and
cooperative PREEMPT ABI are the sole ECI implementation. Acceptance remains
incomplete: pipelines 515941 and 515944 both passed all gates, preparation and
hardware/report jobs. Candidate 515944 is `8cc9ad58`, with WNS −0.089 ns and
474 failing setup endpoints; no timing closure is claimed. Its first verified
cold boot passed all ECI lanes, then driver initialization failed before RPCs:
the kernel used datapath indices 1–4 for a four-entry, zero-based preemption
register table. UBSAN diagnosed index 4, followed by the MMIO bounds BUG.
The failed trial was frozen before reset with no trace loss/DMA error. Its full
132,491,136-byte F_MAC1 capture matches the write pointer and peer/local SHA-256
`f5b27ef4b1e65c40df6b9d802fcd0d901127c1622e6d01b1d6265cef81c61e99`.
No RX-hold threshold was measured. The corrected kernel uses the physical worker
index and executes a generated-register selection check before every module
build. Retain candidate refs while acceptance remains incomplete.


## Implementation

Contract/model commit `514d446` names the capacity schema
`lauberhorn.eci-address-map.v1` and checks simultaneous cross-worker claims.
RTL commit `2fd67e37` removes the thread router and preemption control line.
Kernel/runtime commit `952b9301` installs the matched pool and activation ABI.
Earlier preparatory and naming commits remain in branch history.

`EciAddressMap` and `eci_address_map.h` are generated from the selected 8 KiB
configuration. Seven physical-worker aliases share a color-neutral 19-bit
lifetime namespace. Four workers and sixteen active slots remain defaults.
Slots, lifetime codes, Linux PIDs, thread indices and CPU IDs are distinct.

`EciAxiIngress` checks fresh access ownership and retains accepted full addresses
in AXI USER metadata. Outstanding R/B responses remain charged to their physical
worker. Late RX writeback has an independent sink. LCI commands retain their
full addresses through LCIA matching; owner changes cannot retag them. Final
control invalidation completes before reuse. TX descriptors and reply-context
tokens are checked against the trusted assignment.

`EciDomainAllocator` saturates after 524288 codes. FPGA initialization owns its
reset boundary; application reset and driver reload cannot recycle codes.
Application reset after allocation or a fatal lifetime fault requires a full
coherence reset. Unauthorized fresh reads fault the requesting domain, as
approved by the maintainer; DCS does not propagate AXI errors as a safe application
fault. Sticky domain faults fence claims and trigger domain termination. Old
cleanup traffic is still serviced. Complete resource-recycling recovery is deferred.

`EciPreemptionControlPlugin` sends an IRQ only for idle activation. Ordinary
PREEMPT consumes no packet and sends no scheduling IRQ. The serialized info
read acknowledges relinquishment and waits for autonomous cleanup. Other
AXI-Lite accesses may wait behind it. `PreemptInfo` has no valid bit and is never
polled. A read-only yield-request check rejects unauthorized ordinary calls.

The kernel shares activation handling between pinned idle-dispatch threads and
ordinary yield. The immutable pool is declared before creation and advertised
only after every thread registers following initialization. Mapping only maps
aliases. Runtime yield replaces the physical alias and both local parity bits.
No shared parity page, kernel parity programming, ordinary cache flush or
activation commit write remains.

After the committed response, the outgoing thread becomes claimable before the
helper waits for an incoming thread. This prevents simultaneous A→B/B→A claim
waits. Unrelated wakeups cannot return an old activation; failed delivery kills
and fences the affected domain. Ordinary syscall sleep retains assignment.

## Checks completed in this implementation round

| Check | Observed result and limits |
| --- | --- |
| Generated address collateral and independent capacity utility | Five tests passed; all 524288 codes round-trip on all seven workers (3,670,016 combinations). Both parities, twelve overflow lines per direction, basis/high bits and rejection are checked. |
| `EciAddressMapTest`, `EciAddressDecoderSim`, `EciAxiIngressSim` | Four tests passed, including real AXI crossbar backpressure, ownership changes and retained addresses. These do not instantiate actual DCS slices. |
| `EciDomainFaultsSim` | Sticky fault and concurrent notification-consumption test passed. |
| `EciDomainAllocatorSim` | Both tests passed, including all 524288 allocations, saturation, fatal fencing and application reset. |
| `CooperativeYieldSim` | Delayed cleanup, shared control contention and repeated A→B→A passed with one initial scheduling IRQ and zero ordinary IRQs. Fatal-domain control/IRQ test passed while an independent owner continued. Host coherence model, not silicon. |
| `RpcReplyContextsSim` | Live-token owner mismatch rejected; test passed. |
| Fast ECI regression | Initial run: 36 passed, three failed. Initialization-baseline and interrupt-model fixes made all three pass on targeted reruns. The first committed rerun passed 42 tests. After the idle-first and same-edge ingress fixes, all 44 tests in 13 suites passed (one existing ignored test). The additional cross-worker migration test passed separately. |
| Actual DCU/RX writeback | Four directed checks passed with the selected ROM and new RX layout, including delayed B, WDDA, LCIA and next-control release. This is not a full DCS slice. |
| Production DCS slice control routing | 812 address trials passed through the 32-DCU array, production routers and arbiters: seven workers plus bypass, both parities, every overflow line, high lifetime bits and A→B→A. Read/dirty-write addresses and IDs, delayed completion and backpressured LCIA checked. A follow-up holds three worker reads plus bypass RX while an independent worker and bypass TX complete three rounds; delayed responses then drain by retained ID. Test RAM/passive trace sinks; payload datapath and ThunderX are not instantiated. |
| Stalled-worker/bypass progress | Three expanded tests passed: one, two and three held worker reads while an independent worker progresses and bypass RX/TX transfer concurrently across both parities. Finite 50000-cycle hold setting in the host coherence model; no silicon timeout claim. The separately run RX/TX interleaving test passed and is restored to the regular suite. |
| Worker configurations | Production ECI composition and matched headers generated for every count 1–7; all seven kernel modules and seven runtimes cross-built successfully. The one-worker scalar preemption-register macro needed a kernel fix; the default four-worker path is unchanged. |
| Generated wrapper interfaces | 295 NicEngine ports, 439 trace-DMA ports and 16 ILA connections matched. |
| Native runtime tests | Passed, including real `lh_yield` alias/parity replacement, ioctl failure, invalid-result termination, descriptor bitfields and C ABI assertions. |
| Matched cross builds | Runtime, kernel, deployment and Vivado-input builds passed with the correctness fixes and probe controls. The final compiler memory-barrier change passed runtime and native interface checks. Dandelion’s matching adapter cross-build passed. |
| Pinned cooperative TLC | Thirteen outcomes passed: five positives, seven expected faults and one delayed-idle-kick reachability witness. Includes duplicate/lost kicks, ordinary IRQ, cleanup/control cycle, concurrent claim exchange, failed copying, unrelated wakeups, delayed bootstrap parking and initialization failure. |
| TLA module boundaries/SANY | All 44 inventoried modules passed. Parsing/import checks are not exhaustive model verification. |

`HandoffClaims` reuses `ThreadClaim` uniqueness and capacity invariants. Its
late-release negative reproduces the simultaneous exchange deadlock. This is a
bounded claim/delivery composition, not verification of Linux scheduling or
full transport refinement. `IdleDispatch` assumes eventual autonomous cleanup;
finite simulation delays do not establish an infinite-stall silicon guarantee.

The blocks dependency uses `1eddae0` for configured AXI USER widths and
`a21d410` for controlled LCIA delay in simulation. The dependency remains on its
own feature branch. The unrelated pre-existing `deps/thx-fsms` revision change
is preserved and excluded from platform commits.

## Outstanding acceptance

- Actual Linux concurrency and failure handling remain board checks. The
  slice control-array check, separate migration simulation and initialization-
  failure model do not constitute a full DCS/NIC/ThunderX composition proof.
- Transport settled-entry and freshness remain conditional model obligations.
  Delayed-LCIA, retained-address, final-control invalidation and A→B→A tests
  check the corresponding concrete boundaries separately; they do not prove
  full-system refinement or infinite-stall silicon progress.
- Launch hardware CI with matched artifacts, retain its refs and follow the
  30-minute/five-hour collection workflow.
- Recheck Enzian reservation. Record artifacts, clocks, programmed RX hold and
  every exposed L2C_TAD status. Find the exception boundary or register limit at
  implemented timer resolution; preserve trace/fault evidence before reset.
- Repeat the highest tested exception-free setting under contention across two
  cold boots, recording exposure and nearest failing bound. Include one/multiple
  stalled peers, all-but-one stalls, active-peer cooperative handoffs and
  simultaneous bypass RX/TX. Verify hold durations and peer completions in traces.

No current RX stall threshold, board acceptance or timing closure is claimed.
Host-event interface design, deadline activation, full exit recovery, PCIe
scheduling and timing optimization remain outside this stage.

## Follow-up correctness checks

A directed watermark test reproduced selection of an occupied, RX-ready worker
while another physical worker was idle. Selection now gives the idle set priority.
The test passes after the correction. Ingress busy tracking also includes AR/AW
acceptance on the current edge, so owner installation cannot race the first
accepted old-address writeback. Directed assertions cover this boundary and
attribute rejected reads to their requesting lifetime, without faulting old RX
writeback cleanup.

The host model now publishes an outgoing thread only after the committed info
response, matching the kernel. Four focused tests passed after these corrections,
including duplicate kicks during blocked ordinary handoff, foreign-PID and
oversized TX rejection, and subsequent valid TX progress. The corrected full regression passed all 44 tests. A separate six-handoff
migration test moved single-thread domains between workers and back, with only
the three initial scheduling IRQs. Dandelion's matched adapter build and the pinned
transport/AXI model checks passed; these are separate from board evidence.

The pinned workflow check also passes three real loopback UDP client tests: concurrent
validated RPC/bypass completions, corruption rejection and timeout without retry.
These validate the measurement tool, not the board. The obsolete B4 recovery
runner is removed because it uses the superseded control/recovery ABI.

The exact `8cc9ad58` deployment image built from the committed flake and was
staged on the shared filesystem. Local and remote SHA-256 agree:
`97bdf4a14350ffe5a1c3f8fecb40de03d1ac2f501b3ff0d11d2007d73034ac13`.
DUT zuestoll14 and capture peer zuestoll12 were both reserved by `pengxu` at the
October 2 preparation check. Neither board was reset or reprogrammed in this
preparation. The campaign runner now stops on CPU/client failure before any
subsequent cold reset; three focused tests and the pinned workflow gate passed.

The worker-count matrix initially exposed a test generator's one-per-JVM
Mackerel guard, then the kernel's scalar-versus-indexed base mismatch for one
worker. Both are corrected. `GenWorkerConfigurations` emits each configuration's
headers/devices, and `tools/address-map/worker-builds.nix` cross-builds them.
These are configuration/interface checks; the Enzian candidate remains the
four-worker `8cc9ad58` artifact. No board result for seven workers is claimed.

The pinned workflow check passed the expanded four-case hold-analysis suite
and four TAD assessment cases. Simultaneous-stall counts exclude bypass and
initial activation; log loss, missing units, nonzero interrupts, changed TIMEOUT
state and logged EDAC faults prevent a clean assessment. The assessment remains
limited to the recorded finite exposure.

The refined idle model keeps notification set through response consumption and
allows an earlier idle kick to arrive while ordinary handoff owns the reader and
control bus. The pinned cooperative check passed all thirteen outcomes, including
a reachability witness for that overlap. This changes model coverage only;
the hardware candidate already passed the corresponding delayed-kick simulation.

The corrected-module image uses hardware/runtime/application revision `8cc9ad58`
and module revision `336c65d6`, explicitly recorded together in its manifest.
Its SHA-256 is `8dac17d20850e5edae297a3a414cfd0cb512f611e9a8cae2246fad0d97ed8adc`.
The [recorded Nix recipe](../../data/eci/board-evidence/cooperative-20261003-module-image.nix)
reproduces the exact image store path. Production RTL, runtime, application and
ABI sources were unchanged between those two revisions; the module uses the
original generated headers. All seven module configurations passed the added
UBSAN register-selection check and cross-build. Restoring the old `+1` index in
the native check reproduces its assertion failure.

The corrected cold-boot baseline passed 100 RPCs and all eight TAD assessments.
The application clock is 200 MHz (5 ns), from the candidate's post-route clock
report and generated configuration; no independent oscillator measurement is
claimed. Initial finite probes through 10,000,000 cycles passed. A bounded
64 MiB tail contains 832 completed worker holds of 10,000,003–10,000,004 cycles,
including protocol overhead. The 100,000,000-cycle probe logged an uncorrected
LFB-entry timeout at TAD4 and fenced the domain. Its full newly appended trace
region was preserved; it contains one completed 100,000,003-cycle hold and a
loss marker associated with the earlier freeze/resume gap. No continuous-history
claim spans that gap. A fresh cold-boot 55,000,000-cycle probe also failed;
refinement and final contention/two-boot acceptance are still in progress.

Subsequent five-second screens were insufficient: 27,204,589 cycles passed the
first snapshot but faulted during the following RPC check, and 27,193,602 cycles
faulted after its short checks finished. Those passes were withdrawn. The search
now uses sixty-second exposure, another 100 RPCs and a post-client assessment of
all eight TAD units and the continuous kernel log. It has observed a passing
26,924,951-cycle screen and failing 26,992,113-cycle screen; these are provisional
observations, not an accepted maximum. Full failure buffers and their SHA-256
digests are retained under `out/board/cooperative-515944-refine/` and on the shared
scratch filesystem before each reset. No architectural timeout protection was
changed.

One cold-boot baseline also stopped in `lscpu` before application startup, with a
kernel soft lockup in `find_inode_fast`. Its complete 132,696,064-byte trace has
SHA-256 `de51121b463c8ac129a8cba416488979cc8b79fb3152dbef8dfcdcf48898b054`.
It contains no application activation, only bypass traffic, 347 LCI requests and
347 completions, no decoded loss and no reported DCS errors. A post-failure
all-TAD snapshot was unavailable. Forty stock-Linux `lscpu` queries at concurrency
four passed after a full power cycle without an FPGA image or driver; that
bounded control does not establish the lockup's cause. The following matched
cold-boot baseline, including the same `lscpu` operation, passed. This remains an
unresolved observation. Acceptance checks now reject kernel BUG/Oops, UBSAN,
lockup and RCU-stall diagnostics as well as TAD exceptions.

The matched cold boot reports firmware RCLK 2000 MHz and SCLK 800 MHz. Routed
constraints specify ECI at 322.265625 MHz and the application at 200 MHz; these
are provenance and firmware reports, not instantaneous oscillator measurements.

`tools/enzian/analyze_rx_holds.py` now also checks each fully observed activation
snapshot against its first RX/TX access and counts domain migration and A→B→A
reuse. Trace loss clears pending comparisons and history. Six focused cases and
the pinned `workflow-tools` check passed after this addition. These counters are
coverage evidence; observing reuse alone is not proof of cache freshness.

Longer observation also withdrew a fresh-boot 26,925,213-cycle pass: the
sixty-second probe, 100 RPCs and post-client checks passed, but a preflight check
before contention setup found an LFB timeout at approximately 84.45 seconds
after programming. No contention services were started on that failing boot.
The full 147,335,360-byte trace was preserved without loss; SHA-256 is
`619876b45b2142c2e0bcf0b3bb405afe2dc987a55c36dcfb9e94b570b88a7cd0`.
The campaign therefore pauses fine-resolution screening to qualify a lower
setting with longer exposure and contention. A narrow interval between a short
pass and a later failure does not establish a usable timeout boundary. No
maximum exception-free setting is accepted yet.

The [finite contention record](../../data/eci/board-evidence/cooperative-20261003-contention.json)
now records 26,656,301 cycles (133.281505 ms) across two verified cold boots.
One- and two-peer measured intervals passed RPC/bypass correctness, three-minute
control-register readers and all eight TAD/kernel checks. The fresh-boot
all-but-one trial completed 2,964 add, 2,963 echo and 54,629 bypass replies without
retries. Its loss-free measured trace contains 5,926 ordinary handoffs and no
scheduling IRQ counter change. Of these, 4,710 handoffs overlap three other
full-length stalled worker reads, alongside independent RX/TX and bypass
progress. All 5,926 first-RX and first-TX parity comparisons match, with repeated
A→B→A reuse. The complete fresh-boot trace also has no decoded loss.

The same live add thread on the first boot was observed on Linux CPUs 44 and 47;
the later trace delivers its domain on NIC datapath 4 (worker index 3), with
matching first-access parity and a correct response. The intervening capture
gap prevents a continuous-history claim. The audit now requires a fully observed
departure before reporting domain migration, so concurrent expansion to multiple
workers does not count as migration. Seven focused cases and the pinned workflow
gate pass. Boundary refinement above this qualified finite setting remains open;
these results do not establish an infinite-stall guarantee or resolve the earlier
baseline kernel lockup.
