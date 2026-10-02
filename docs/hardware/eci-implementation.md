# ECI implementation and evidence

October 2, 2026, branch `feat/cooperative-yield`. The selected address map and
cooperative PREEMPT ABI are the sole ECI implementation. Acceptance remains
incomplete: regression and deployment checks are in progress, and no replacement
hardware CI or Enzian timeout campaign has run. Do not merge or substitute
historical board evidence for these gates.

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
| Fast ECI regression | Initial run: 36 passed, three failed. Initialization-baseline and interrupt-model fixes made all three pass on targeted reruns. Full committed rerun is pending. |
| Worker configurations | Production ECI composition elaborated for every count 1–7. |
| Generated wrapper interfaces | 295 NicEngine ports, 439 trace-DMA ports and 16 ILA connections matched. |
| Native runtime tests | Passed, including real `lh_yield` alias/parity replacement, ioctl failure, invalid-result termination, descriptor bitfields and C ABI assertions. |
| Matched cross builds | Runtime and kernel passed before the final claim-release and fixture corrections. Final deployment/Vivado-input builds are pending. |
| Pinned cooperative TLC | Nine outcomes passed: three positives and six expected violations. Includes duplicate/lost kicks, ordinary IRQ, cleanup/control cycle, concurrent claim exchange, failed copying and unrelated wakeups. |
| TLA module boundaries/SANY | All 43 inventoried modules passed. Parsing/import checks are not exhaustive model verification. |

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

- Finish the committed ECI regression, actual DCU/writeback and DCS slice
  routing/completion checks, matching runtime/kernel/deployment builds, and
  Dandelion adapter build. Extend concurrent migration and initialization-failure
  evidence where existing tests do not cover the implementation boundary.
- Reconcile remaining transport-readiness and activation properties with the
  concrete implementation. Report assumptions and finite bounds explicitly.
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
