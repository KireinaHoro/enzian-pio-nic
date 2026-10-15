---
name: benchmark-campaign
description: Prepare, launch, inspect, or recover unattended Lauberhorn hot echo RPC measurement campaigns and review their paper figures. Use for campaign execution, not ordinary chart styling or hardware acceptance tests.
---

# Hot echo benchmark campaigns

Work in the Lauberhorn platform repository. Read
`docs/development/benchmark-campaign.md` for implementation status, the adapter
contract and validation commands. Read `AGENTS.md` and the hardware-test guide
before touching a board. Use `paper-chart` for figure generation and final review.
Keep execution logic in `tools/paper/benchmark_campaign.py` and its campaign
package; do not reproduce its rate selection or acquisition loops in chat.

Before launch, require a concrete manifest, current EMG reservations, matching
artifact hashes, eight isolated generator CPUs, storage and RAM budgets,
restoration paths, and working terminal callback delivery to the current thread.
A dated hardware handoff is not a reservation. Preserve any earlier failure
before resetting. Provision through EMG and use the repository hardware harness.
Never promote a fixture, an unvalidated adapter, or historical measurements into
passing rehearsal evidence.

Run local gates first. The controller must pass the small rehearsal, including
both unloaded instrumentation modes and overloaded loaded cases, before it
freezes and collects the full grid. Loaded cases disable FPGA recording and
software profiling and perform no trace reads. Boot/module failures still require
the repository's diagnostic capture before reset. Expected server loss is data;
generator misses, admission failure, corruption and resource exhaustion invalidate
a point. Keep failed attempts and exact rates.

Launch using the documented detached user-service command. After launch, return
the campaign root and service identifier without intermediate polling. Inspect
`status.json` only when the user asks or a terminal queued prompt arrives. Normal
progress uses the compact status, not raw console logs. Resume explicitly with
the identical configuration and implementation; never overwrite a cohort.

On failure, inspect the named failed stage and preserved evidence, diagnose the
bounded failure, and repair before explicitly resuming. Retry notification
without rerunning acquisition. Do not describe a submitted service as a successful
campaign. On success, check completeness and provenance, inspect plot previews
visually, and report actual coverage and limitations. Preserve old active paper
figures until their replacements have passed validation. Commit implementation,
measurement data and provenance normally; leave layout and presentation changes
uncommitted until requested.
