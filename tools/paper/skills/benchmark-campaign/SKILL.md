---
name: benchmark-campaign
description: Prepare, launch, inspect, or recover unattended Lauberhorn hot echo RPC measurement campaigns and review their paper figures. Use for campaign execution, not ordinary chart styling or hardware acceptance tests.
---

# Hot echo benchmark campaigns

Work in the Lauberhorn platform repository. Read
`docs/development/benchmark-campaign.md` for implementation status, the adapter
contract and validation commands. Read `AGENTS.md` and the
[enzian-deploy skill](../../../enzian/skills/enzian-deploy/SKILL.md) before touching a
board, including its BMC rail-state and D-Bus recovery guidance. Commands and
code paths are relative to the platform repository root. The benchmark adapter
[campaign/hardware.py](../../campaign/hardware.py) invokes the same
[test.py](../../../enzian/test.py) and expect-based [boot.py](../../../enzian/boot.py)
used for ordinary hardware acceptance; [campaign/harness.py](../../campaign/harness.py)
uses [run.py](../../../enzian/run.py) and shared [console.py](../../../enzian/console.py).
Keep deployment fixes in these shared scripts, never a parallel benchmark boot
or console workflow. Benchmark peer roles and trace receiver/exporter roles share
`tools/enzian/image.py`: verify the staged Nix image hash, mount it read-only in
a private namespace, and execute its pinned Python. Host Python only bootstraps
the verifier; never launch trace code against a private path from the host namespace. Use `paper-chart` for figure generation and final review.
For trace preservation use the shared
[lauberhorn-trace skill](../../../enzian/skills/lauberhorn-trace/SKILL.md) and
`tools.enzian.trace.session.capture()`. The adapter uses the same session API as
the manual CLI, including existing CMAC/JTAG readers; never generate trial Tcl
or implement a paper-specific dump/export/restore path.
Keep execution logic in `tools/paper/benchmark_campaign.py` and its campaign
package; do not reproduce its rate selection or acquisition loops in chat.

Before launch, require a concrete manifest, current EMG reservations, matching
artifact hashes, 32 isolated generator CPUs (currently 16–47), best-effort IRQ affinity setup, storage and RAM budgets,
restoration paths, and established terminal callback delivery to the current thread.
A successfully received probe or terminal callback already proves delivery for
the same thread and callback command/setup. Reuse that evidence across preflight,
launch, resume and new campaign roots; do not send another visible probe just
because preflight runs again. Re-probe only after a relevant callback destination,
command, authentication or service-environment change, or an observed delivery
failure. Record and reuse delivery evidence in maintained preflight tooling so
manual and detached preflights do not each notify the user. If existing tooling
still probes unconditionally, fix that before the next launch rather than adding
a manual probe. Restart the controller only when its in-memory logic must change.
A probe receipt is not an acquisition result and does not call for progress
polling or a repetitive acknowledgement. Terminal success/failure callbacks and
delivery retries remain required.
A dated hardware handoff is not a reservation. Preserve any earlier failure
before resetting. Provision through EMG and use the repository hardware harness.
Never promote a fixture, an unvalidated adapter, or historical measurements into
passing rehearsal evidence.

For an explicitly requested bounded switch integration, use `scope: "unloaded-rehearsal"`:
12 unloaded cohorts, 128 warmups and 100 measured requests each, ending after
validated CDF/breakdown previews and a terminal callback. It never starts loaded
or full collection. The same maintained controller owns locking and resume.

Run local gates first. Full collection starts directly from its frozen `grid.json`
(or an explicit complete `grid` or `loaded_sweep: "linear-to-loss"` in a new campaign configuration); never launch a
preliminary rehearsal implicitly. Use rehearsal scopes only when explicitly
requested. Full acquisition requires authorization. Collect and validate all
full unloaded plots before any loaded run. Refresh loaded plots after every
completed grid/extension point and sustained confirmation. Progress plots are
partial data, not final completeness evidence. The canonical methodology is `docs/research/hot-measurement.md`.
Unloaded CDF cases have compiled-out probes and Tofino wire-to-wire durations;
breakdown cases have enabled probes and their own Tofino totals. Neither uses
mlx5 hardware timestamps. Partition CPU time into queue processing, network
processing, RPC receive/transmit and handler time, plus explicit remaining CPU
work. The combined CPU–wire residual is the wire duration minus the CPU duration;
never infer directional RX/TX residuals without synchronized clocks. RX probes
must start at valid CQE observation inside the selected receive path; Version-4 TX ends immediately before the NIC notification (mlx5 MMIO or LH
ECI control-line read), after descriptor/doorbell-record preparation and ordering.
Notification, flush and return are excluded from the CPU interval. Application
burst-call boundaries alone do not validate this coverage.

Use zuestoll07 sequentially for both LH and ConnectX-5, and zuestoll14 as the
separate generator. Check both server-facing leaf0 ports before capture.
The `tofino-capture` skill owns switch access, batch completeness and recovery.
The unloaded adapter delegates lifecycle to `tools/capture-session.py run` in
that project and supplies the real client ledger. Require an authenticated
control socket and pinned controller/artifact hashes. Accept schema-3 pairer
output with `latency_accepted: true`, `units: "ns"`, `ns_per_tick: 1`;
`hardware_timing_validated: false` and `absolute_wire_calibrated: false` are
calibration metadata, not latency vetoes. Preserve raw timestamps and use the
maintained clock-rate qualification helper before/after campaigns and after
clock/reboot changes. Bounded integration trials remain outside paper inputs;
real-workload validation still gates long collection. See the campaign document
for the manifest and per-chunk limit. `campaign/wire.py` requires hashed real
unloaded rehearsal acceptance before long collection; fixtures never authorize
capture. Full collection uses resident-worker chunks with manifest-configured warmups (default 128) after each
rearm. Existing integration evidence is checked without launching extra trials.
Preserve compatible prior acquisitions through the explicit review path.
For bounded software-only board acceptance use
`docs/research/dpdk-baseline.md` and
`docs/development/validation.md#dpdk-baseline-readiness`, the shared NIC runner and
`tools/paper/campaign/mlx5_readiness.py`; validate its archive with
`tools/paper/validate_mlx5_readiness.py`. Missing wire durations stay null and
readiness outputs are excluded from paper inputs. Do not resume the NICVF adapter
as the new baseline. Caladan-derived hot cases require benchmark acceptance beyond the partial
native ARM/MLX5 mechanism checks. Loaded cases retain compiled-out probes and client
RTT, with no Tofino capture, FPGA readout or NIC PTP.
Boot/module failures still require
the repository's diagnostic capture before reset. If Ethernet capture fails,
retain partial evidence and stop without resetting; do not fall back to a full
JTAG dump. Small JTAG head/tail checks remain part of successful capture validation. Expected server loss is data; this includes default-runtime starvation and zero
handler activity in a loaded trial whose readiness, placement, generator, health
and restoration checks pass. Zero replies means zero goodput and 100% deadline
loss, with undefined latency. Do not tune away baseline behavior to pass an
activity gate. Retain placement/counter consistency and grid-wide worker coverage;
unloaded/readiness activity requirements remain strict. Stop the qualifying
latency/goodput curve at the first >1% loss point and retain all loaded loss points.

Record well-formed ONC-RPC `SYSTEM_ERR` rejections separately and include requests
without an on-time successful reply in the 1% loss condition. Retain per-trial rejection counts for later plots (and per-request counts in
detailed diagnostic mode); do not count duplicate rejection
replies as additional lost requests. Generator misses, admission failure, corruption and resource exhaustion invalidate
a point. IRQ affinity limitations or observed interrupts alone are diagnostic,
not invalidation conditions; enforce the requested absolute send schedule in
both warmup and measurement. Loaded generation uses slotted Poisson batches: configurable aggregate
`slot_ns` (default 1000 ns) and reproducible `arrival_seed`. The 32-core pool
uses eight exclusive TX owners plus dedicated RX, preparation and tracking cores.
Successive slots belong to successive TX owners; completion must precede the
next opportunity for that owner (`senders * slot_ns`). Independent send-lag
guards are retired. Reject receiver backlog that persists beyond a sender period;
record RX occupancy and polling gaps. Prepared loaded RTT begins at TX release,
so keep it separate from historical pre-encoding RTT series;
Poisson draws are never clipped to the 64-packet TX-call limit. Record sampled
phase/core counts; do not require rate × duration requests. Equal-spacing and
adaptive deterministic-batch senders are retired. Use the same slot length
across systems/rates and qualify its sustainable rate before collection.
Do not merge old arrival models into a slotted-Poisson series.
Unloaded cohorts remain eligible for reviewed reuse.
Set counts, warmups, durations, repetitions and per-payload sweep steps in the
campaign manifest `measurement` object; see the campaign guide. Defaults use
one trial per loaded grid point and one sustained trial per candidate.
Do not imply a between-trial range when only one trial exists.
Keep failed attempts and exact rates. One valid point above 1% loss is sufficient
for rehearsal overload coverage; do not require an additional higher point. New hot full campaigns use `loaded_sweep: "linear-to-loss"`: manifest steps
(default LH 20k for 8/128 B, 5k for 1432 B; DPDK/CS-A64 50k and 20k respectively), starting at one positive step
and ending at the first valid >1% loss point. Zero is the chart origin.
Historical explicit grids retain bounded geometric extensions.

Loaded collection supports `aggregate: true`: online validation, exact counters,
100 ns mergeable RTT histograms and bounded diagnostic samples. Prefer it after
targeted comparison gates pass. Keep detailed CSV mode for diagnosis and preserve
historical raw data. Pool histogram counts before computing percentiles; never
average trial percentiles or expand counts into synthetic request rows. A mode or
binary transition requires the compatibility review below. See the compact loaded
observations contract in `docs/development/benchmark-campaign.md` for retention,
resource limits, forwarding bounds and quantization.


Launch using the documented detached user-service command. After launch, return
the campaign root and service identifier without intermediate polling. Inspect
`status.json` only when the user asks or a terminal queued prompt arrives. Normal
progress uses the compact status, not raw console logs. Never overwrite a cohort.
Automatically reuse only passing cohorts whose request/configuration and relevant
implementation identity match and whose saved evidence hashes still verify.
If a server, generator, placement, configuration or implementation change prevents
automatic reuse, fail fast before further acquisition and queue a terminal prompt
for model review through the existing callback mechanism. Do not silently discard
completed cohorts or restart the dataset because hashes differ.

The agent may explicitly override the compatibility rejection after reviewing
the change; separate user approval is not required within the authorized campaign
scope. Classify each affected completed cohort as reuse or rerun, with a concrete
reason and validation evidence. Bind the review to exact old/new code, artifacts
and configurations, receipt hashes, and evidence hashes. Assess workload and wire
semantics, timing boundaries, instrumentation, send schedule, placement and
resource effects. A packaging or diagnostic-only change can leave measurements
compatible; a server or generator change requires examining its actual effect,
not blanket permission or blanket invalidation.

An override authorizes reuse of intact passing evidence, not bypassing evidence
integrity or relabeling failed attempts as passing. Retain original acquisition
identities and record the review separately. Rerun affected or unfinished cases
and regenerate derived validation/plots; do not combine scientifically incompatible
cohorts merely to fill a grid.

For explicit selective invalidation, the optional `recovery-plan` and
`--recovery-review` workflow remains available. Source edits alone never require it. Current tooling restricts this to unchanged acquisition
manifests and Python/shell/Tcl changes. For artifact/configuration transitions, create a new root with
`inheritance-plan --inherit-reviewed SOURCE --config NEW_CONFIG --root NEW_ROOT`,
complete its per-cohort decisions and validation evidence, then launch with
`--inherit-reviewed SOURCE --inherit-review REVIEW`. The controller binds the
override to both configurations and code/artifact manifest hashes, checks every
source receipt and preserves original acquisition provenance. Never bypass the guard by
editing frozen manifests, receipts or recorded hashes in place.
Plotting and orchestration source edits do not require a hash-bound recovery review.
Implementation snapshots are provenance, not a source freeze. Acquisition artifact
and configuration compatibility and raw-evidence integrity checks still apply.
Controller edits take effect after restart; plotting edits take effect on the next plot stage. Hardware preservation and restoration gates still apply.

On failure, inspect the named failed stage and preserved evidence, diagnose the
bounded failure, and repair before explicitly resuming. Retry notification
without rerunning acquisition. Do not describe a submitted service as a successful
campaign. On success, check completeness and provenance, inspect plot previews
visually, and report actual coverage and limitations. Preserve old active paper
figures until their replacements have passed validation. Commit implementation,
measurement data and provenance normally; leave layout and presentation changes
uncommitted until requested.

The existing `--inherit-unloaded SOURCE --inherit-reason REASON` path supports
reviewed inheritance of all twelve unloaded rehearsal cohorts into a new root,
preserving source hashes and provenance. It is a limited implementation, not a
policy that all loaded cohorts must be discarded after a binary change. Apply
the compatibility review above when broader inheritance is needed.
