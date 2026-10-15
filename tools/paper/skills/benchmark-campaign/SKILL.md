---
name: benchmark-campaign
description: Prepare, launch, inspect, or recover unattended Lauberhorn hot echo RPC measurement campaigns and review their paper figures. Use for campaign execution, not ordinary chart styling or hardware acceptance tests.
---

# Hot echo benchmark campaigns

Work in the Lauberhorn platform repository. Read
`docs/development/benchmark-campaign.md` for implementation status, the adapter
contract and validation commands. Read `AGENTS.md` and the
[enzian-deploy skill](tools/enzian/skills/enzian-deploy/SKILL.md) before touching a
board, including its BMC rail-state and D-Bus recovery guidance. All linked paths
are relative to the platform repository root. The benchmark adapter
[campaign/hardware.py](tools/paper/campaign/hardware.py) invokes the same
[test.py](tools/enzian/test.py) and expect-based [boot.py](tools/enzian/boot.py)
used for ordinary hardware acceptance; [campaign/harness.py](tools/paper/campaign/harness.py)
uses [run.py](tools/enzian/run.py) and shared [console.py](tools/enzian/console.py).
Keep deployment fixes in these shared scripts, never a parallel benchmark boot
or console workflow. Use `paper-chart` for figure generation and final review.
For trace preservation use the shared
[lauberhorn-trace skill](tools/enzian/skills/lauberhorn-trace/SKILL.md) and
`tools.enzian.trace.session.capture()`. The adapter uses the same session API as
the manual CLI, including existing CMAC/JTAG readers; never generate trial Tcl
or implement a paper-specific dump/export/restore path.
Keep execution logic in `tools/paper/benchmark_campaign.py` and its campaign
package; do not reproduce its rate selection or acquisition loops in chat.

Before launch, require a concrete manifest, current EMG reservations, matching
artifact hashes, 32 isolated generator CPUs (currently 16–47), best-effort IRQ affinity setup, storage and RAM budgets,
restoration paths, and working terminal callback delivery to the current thread.
A dated hardware handoff is not a reservation. Preserve any earlier failure
before resetting. Provision through EMG and use the repository hardware harness.
Never promote a fixture, an unvalidated adapter, or historical measurements into
passing rehearsal evidence.

Run local gates first. The controller must pass the small rehearsal, including
all three unloaded payload sizes in both compiled instrumentation modes and overloaded loaded cases, before it
freezes and collects the full grid. Default to `scope: "rehearsal"`, which
terminates with a callback after validated rehearsal plots and never starts full
collection. Use `scope: "full"` only when full acquisition is authorized; a user
request to stop after rehearsal overrides any earlier full-campaign plan. Uninstrumented unloaded cases use compiled-out software tracing and client RTT
only, with FPGA recording and MAC/PTP timestamping disabled; they perform no
trace readouts. Instrumented unloaded cases use MAC/PTP timing and their own
breakdowns. Do not add a redundant runtime-off control. Loaded cases use
compiled-out software tracing, disable FPGA recording and perform no trace reads. Boot/module failures still require
the repository's diagnostic capture before reset. If Ethernet capture fails,
retain partial evidence and stop without resetting; do not fall back to a full
JTAG dump. Small JTAG head/tail checks remain part of successful capture validation. Expected server loss is data;
generator misses, admission failure, corruption and resource exhaustion invalidate
a point. IRQ affinity limitations or observed interrupts alone are diagnostic,
not invalidation conditions; enforce the requested absolute send schedule in
both warmup and measurement. Keep failed attempts and exact rates.

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

For supported source-only fixes, use the documented `recovery-plan` and
`--recovery-review` workflow. Current tooling restricts this to unchanged acquisition
manifests and Python/shell/Tcl changes. General artifact/configuration overrides
are not yet implemented: extend the maintained recovery tooling with a hash-bound,
per-cohort review before applying such an override. Never bypass the guard by
editing frozen manifests, receipts or recorded hashes in place.
Develop fixes in a separate worktree while a campaign is running; apply them only
after it stops. Hardware preservation and restoration gates still apply.

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
