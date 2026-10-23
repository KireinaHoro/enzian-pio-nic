---
name: enzian-deploy
description: Reserve and provision Enzian boards through recorded EMG commands, deploy matched Lauberhorn artifacts, run hardware acceptance, and recover BMC or boot failures using the repository harness. Use for real board access, not simulation or chart presentation.
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

## EMG command safety

`emg` is a simple Bash script with fragile argument parsing. Execute only command
forms and options already recorded in the repository's provisioning documentation,
successful acquisition evidence, or explicit maintainer instructions. Never probe
`emg` with `--help`, `-h`, guessed options/subcommands, or a bare invocation to
discover usage. This prohibition includes help flags after a valid machine name:
`emg acquire MACHINE --help` can acquire a machine and create a volume.

When the needed command form or option is not recorded, inspect the script read-only and ask the
maintainer for the supported command before executing it. For reacquisition, use
the recorded existing volume and boot settings; create a new volume only when
requested. Verify the resulting reservation and volume assignment through the
recorded inventory commands.

## EMG reservations and boot selection

Use the [recorded EMG use cases](references/emg.md) for inventory, creating or
selecting root volumes, selecting staged kernels/initrds, supplying a kernel
command line and release/reacquisition. This skill owns command mechanics and
safety. Calling workflows own their image names, golden-image selection, kernel
artifacts and command-line options; read their configuration before using EMG.
Retain the selected settings and resulting inventories, and check live ownership
before board access. Reservation is not boot or hardware acceptance.

## Board acceptance

Use `tools/enzian/test.py CASES.json --logs NEW_DIR --repeats N` for the shared
reset/program/boot/module/RPC acceptance flow. Require verified rails off before
programming, BDK QLM8–13 CDR lock and all CCPI lanes 0–23 before module loading,
and actual correct RPC replies for acceptance. Read compact `summary.json`
first. Only inspect bounded raw log excerpts for a reported failure. Preserve
all failed attempts; never overwrite evidence or silently treat login as a pass.

## Raw-log access

Read compact stage/results output and `summary.json` by default. Do not open,
tail, grep/search, or otherwise read raw console, CPU, BMC, programmer or boot
logs during normal progress or after success. Do not read them merely to verify
markers that the shared harness already validates. Let the harness perform its
bounded retries and report their outcome.

Raw logs may be read only to diagnose a reported error that requires them, or
when the user explicitly requests log inspection. First identify the failed
stage and the diagnostic question from its summary, then read only a bounded,
relevant excerpt. Keep full logs preserved on disk without loading them into
the conversation. Missing routine summary fields should be fixed in the shared
workflow rather than making raw-log inspection a normal operating step.

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

For Vivado `End of startup status: LOW` with the FPGA still unprogrammed
(`DONE=0`), the maintainer authorizes retrying the same matched bitstream directly,
without a cold reset. Preserve the failed programming log and use the boot
helper's `--resume-held` path to verify BDK is still held, reprogram, and release
boot only after programming succeeds. A missing trace VIO on this unprogrammed
FPGA does not block that retry. This exception does not cover failures after
successful programming or module/probe failures.

## Campaign handoff

For unattended echo acquisition, use `benchmark-campaign` and its maintained
controller. Resume compatible fixes with per-cohort evidence review; retain
completed acquisitions and original revisions rather than restarting the dataset.
Restore temporary NIC/peer controls and preserve failed hardware state before
clearing recovery gates. After detached launch, return without progress polling;
inspect only on a user request or terminal callback. Rehearsal authorization does
not authorize the full dataset.
