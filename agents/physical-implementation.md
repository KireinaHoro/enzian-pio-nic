# Physical implementation continuation

Timing optimization is paused at the maintainer's request. Do not start new
timing work or poll CI until requested. The already launched ba2 batch was
allowed to finish; that permission does not authorize a replacement batch.

Platform master adopted TX placement/refinement and high-VC RX staging.
Selected revision `1c72ea0` reached WNS −0.007 ns with 17 failing endpoints;
this is not timing closure. DCS/low-VC/combined experiments were left unmerged.
Toolkit commits must remain on separate feature branches/private refs pending
colleague upstream approval; never merge or push them to toolkit master.
See the [selected result and limits](../docs/hardware/physical-implementation.md#selected-september-17-result-and-limits).
The closeout did not establish new master timing closure or change the static shell.

Recorded board revisions also failed timing:

| Revision | Recorded timing / functional evidence |
| --- | --- |
| `5cda901` | F_MAC1 CMAC trace path; 300 RPCs and three full 322 MB dumps; WNS −0.062 ns |
| 516232 | WNS −0.102 ns; 893 failing setup endpoints |
| 518689 | WNS −0.950 ns |

See [CMAC board evidence](hardware-state.md#cmac-trace-readout-evidence)
and the [measurement evidence](measurements.md). Functional acceptance is not
routed timing closure. Before any authorized resumption, inspect actual refs and
saved batch results; do not infer current job state from this handoff.

## Retained experiment inputs

At September 17 closeout, toolkit `c92f0d9` was retained on
`timing/20260916-rx-elastic` and private platform ref
`timing/20260916-eci-toolkit-rx-elastic`. Low-VC `a194f3a` remained separate under
`timing/20260917-low-vc-stage` / private ref
`timing/20260917-eci-toolkit-low-vc-stage`. Platform experiment refs were
`timing/20260917-dcs-placement`, `timing/20260917-low-vc-stage` and
`timing/20260917-round2-combined`. Recheck actual refs before use; preserve
unmerged work and do not push to the colleague's toolkit master.

The authorized local batch was `lh-round2-20260917.service` on ba2, ordered
all, DCS, low-VC, TX refinement. Inputs:
`/tmp/lh-round2-inputs-20260917-{all,dcs,low-vc,tx-refine}`; outputs:
`/tmp/lh-round2-20260917-{all,dcs,low-vc,tx-refine}`, with `.console.log` and
`.exit-status` files. At closeout combined was finished, DCS running and the
other two queued. These are dated observations, not live service status.
On an explicitly requested resumption, inspect saved statuses and manifests in
`out/physical/round2-20260917/` before deciding whether any result is missing.
No new run or polling is authorized by this handoff.

Toolkit RX regression notes retain the pre-existing RSTP packetizer failure and
low-latency-mode GHDL driver issue; tested BRAM-mode staging does not resolve
those owner-side issues. Use current validation/CI procedures, not the retired
September timers or failed-submission commands. Original experiment chronology
is retained at `5e114527:docs/hardware/physical-experiments.md`.
