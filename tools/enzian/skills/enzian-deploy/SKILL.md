---
name: enzian-deploy
description: Deploy matched Lauberhorn artifacts to reserved Enzian boards, run hardware acceptance, and recover BMC or boot failures using EMG and the repository harness. Use for real hardware deployment, not simulation or chart presentation.
---

# Enzian deployment

Work in the Lauberhorn platform repository. Read [validation](../../../../docs/development/validation.md)
for manifests and acceptance, [workflow](../../../../docs/development/workflow.md) for matching build
products, and the [lauberhorn-trace skill](../lauberhorn-trace/SKILL.md) when
failure preservation is needed. Both manual preservation and benchmark capture
use `tools/enzian/trace_capture.py` / `trace.session.capture()`; do not generate
separate VIO or dump Tcl.
Keep execution in the shared deployment scripts (paths relative to the repository
root): [test.py](../../test.py) orchestrates acceptance,
[boot.py](../../boot.py) implements expect-based power/BDK/program/boot,
[console.py](../../console.py) owns reservation and console attachment,
and [run.py](../../run.py) runs bounded CPU scripts. Do not duplicate this
state machine in a skill or introduce a benchmark-specific boot implementation.

The [benchmark-campaign skill](../../../paper/skills/benchmark-campaign/SKILL.md)
uses these same scripts through [campaign/hardware.py](../../../paper/campaign/hardware.py)
and [campaign/harness.py](../../../paper/campaign/harness.py). Those adapters supply
matched manifests and benchmark role scripts; deployment and expect handling stay
in `tools/enzian/`. Deployment fixes belong in the shared scripts so acceptance
and benchmark acquisition both inherit them.

Check current EMG reservations for both DUT and peers. Provision exclusively
through EMG, including kernel, arguments and volumes. Never edit PXE or iSCSI
exports directly. Stage matching bitstream, generated headers, module, runtime
and application artifacts with hashes. Use Nix and source-defined build targets.
Dated board notes are provenance, not current reservations or current state.

## Fresh reservations and root-volume names

Follow [fresh root-volume safeguards](../../../../docs/development/validation.md#fresh-root-volume-safeguards).
Check both inventories immediately before acquiring a reservation; existing names
reuse volumes, and active volume names must not be shared. Retain acquisition
provenance and recheck live state rather than relying on dated notes.

Use `tools/enzian/test.py CASES.json --logs NEW_DIR --repeats N` for the shared
reset/program/boot/module/RPC acceptance flow. Require verified rails off before
programming, BDK QLM8–13 CDR lock and all CCPI lanes 0–23 before module loading,
and actual correct RPC replies for acceptance. Read compact `summary.json`
first. Only inspect bounded raw log excerpts for a reported failure. Preserve
all failed attempts; never overwrite evidence or silently treat login as a pass.

## BMC state and recovery

The BMC API is stateful. Start diagnosis with `print_voltage_all()` in
`enzian-shell bringup`; do not infer rail state from a BMC reboot or Linux login.
A normal `DeviceUninitializedError` mentioning `init_driver()` can mean the
relevant CPU power rails are off and the API was called in the wrong sequence.
Do **not** call `init_driver()` merely because the error suggests it. Inspect
voltages and use the bring-up sequence: `common_power_up()`, then
`cpu_power_up()`. Let the boot helper catch and hold the CPU at BDK, power the
FPGA, program it, and release boot. For confirmed powered-off rails, use the
harness's `test.py --cold-start-first` path to avoid a redundant `power_down()`; it still verifies
rails before proceeding. If rails are already on, use the ordinary verified
cold-reset path. Never bypass the off-rail check to suppress an API error.

Distinguish API/state errors from `org.freedesktop.DBus.Error.NoReply` timeouts.
For D-Bus timeouts, preserve the failing log, reboot the DUT's BMC, verify it
returns (preferably with a changed boot ID), then inspect voltages and retry the
correct sequence through the harness. Do not loop indefinitely: if the timeout
recurs after one reboot and bounded retry, stop and report the preserved evidence.
Use the shared reservation/console helpers; never steal an attached console.

A failure before FPGA power-up/programming may leave no accessible recorder;
record that limitation instead of claiming a capture exists. For module/probe
failures on a programmed FPGA, freeze and preserve VIO plus a matched F_MAC1
Ethernet trace before reset. Follow explicit campaign restrictions: the echo
campaign treats a failed Ethernet dump as terminal, with no full JTAG fallback.
Small integrity spot checks are separate from a full fallback dump.

## Campaign handoff

For unattended echo acquisition, use `benchmark-campaign` and its maintained
controller. Resume compatible fixes with per-cohort evidence review; retain
completed acquisitions and original revisions rather than restarting the dataset.
Restore temporary NIC/peer controls and preserve failed hardware state before
clearing recovery gates. After detached launch, return without progress polling;
inspect only on a user request or terminal callback. Rehearsal authorization does
not authorize the full dataset.
