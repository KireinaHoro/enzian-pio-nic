---
name: lauberhorn-trace
description: Freeze, dump, validate and persist Lauberhorn FPGA traces manually, including diagnostic preservation and failed-export recovery. Use the shared CMAC capture session with bounded JTAG integrity checks; not for workload-specific latency analysis.
---

# Lauberhorn trace preservation

Work in the platform repository. Paths below are relative to its root. Read
[the canonical workflow](../../../../docs/development/tracing.md) for the configuration,
commands, output contract and recovery options. For boot or power failures use
[enzian-deploy](../enzian-deploy/SKILL.md) first; a board that
never powered/programmed has no accessible recorder to invent a capture from.

Invoke `python3 tools/enzian/trace_capture.py capture --config TRACE.json --output
NEW_DIRECTORY`. This is the same `trace.session.capture()` operation used by
[benchmark-campaign](../../../paper/skills/benchmark-campaign/SKILL.md). It stages
one source-controlled `control.tcl` and reuses the existing
`tools/trace/eci/sys_trace/dump_trace_udp.py` CMAC receiver and
`tools/trace/eci/sys_trace/dump_trace_hw_axi.tcl` JTAG reader. Never write per-run Tcl,
copy remote `/tmp` scripts as execution dependencies, or construct a separate
freeze/export/restore workflow. All peer execution uses the shared Enzian expect
and console helpers.

Check current reservations, exact bitstream/LTX/map provenance, capture peer
routing, RAM/disk budgets and the configured transfer endpoint before capture.
Use a fresh output directory and let the session own saved VIO state. Full dumps
use CMAC; JTAG is only the bounded successful-dump head/tail integrity check.
Do not run full JTAG fallback, reset to recover a capture link, or silently dump
only a prefix of a wrapped buffer. Preserve failures before any reset.

Read `capture.json` and `summary.json` rather than raw progress logs. Success
requires byte/hash validation, independent integrity checks and restored controls;
trace recording remains frozen. Preserve partial outputs and cleanup errors on
failure. If the receiver completed and only export failed, use `recover-export`
on the same session; it checks recorder identity and never restarts acquisition.
A passing transport summary is not proof of loss-free or valid benchmark data:
run the workload's matched trace analysis separately.

Recording-off operation uses `freeze` and `preserve` with the same control-session
directory and performs no dump. The unattended benchmark adapter uses this same
control implementation for loaded cohorts. Normal unloaded echo measurements use Tofino wire timestamps; both unloaded
and loaded cohorts keep FPGA recording frozen without a dump. Full FPGA dumps
are for exceptional failure preservation or separately requested diagnostics. Receiver and exporter run through `tools/enzian/image.py`, the same hash-verified
private Nix image launcher used by benchmark roles. Require `peer_image` and
`peer_image_sha256`; host Python is only the verification bootstrap. Respect campaign
no-poll behavior and selective resume policy; manual diagnosis does not authorize
a full dataset or invalidate unrelated completed cohorts.
