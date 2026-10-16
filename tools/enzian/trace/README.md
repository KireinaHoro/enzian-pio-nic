# Lauberhorn trace capture

`python3 tools/enzian/trace_capture.py` is the canonical entry point for manual
capture and unattended campaigns. `trace.session.capture()` is the same operation
as a Python API. The benchmark adapter calls that API; it has no separate dump,
export, restoration or integrity implementation.

The workflow reuses the existing low-level implementations:

- `data/eci/sys_trace/dump_trace_udp.py`: CMAC/UDP receiver, bounded retries and
  partial output. Its readiness marker is created only after socket bind.
- `data/eci/sys_trace/dump_trace_hw_axi.tcl`: JTAG reader, called only for two
  4 KiB head/tail integrity checks after a successful full Ethernet dump.
- `tools/enzian/trace/control.tcl`: one parameterized VIO/snapshot/trigger/check
  script staged from source, never generated per trial.
- `tools/enzian/run.py` and `console.py`: shared expect-based peer execution,
  reservation checks and console ownership. No alternative login implementation.

The old paper capture/check wrappers and remote-only `capture-vio.tcl` are no
longer execution dependencies. Historical manifests retain their original hashes.
Workload-specific event counts, joins and latency analysis remain in paper tools;
transport integrity does not claim those measurements are valid.

## Configuration and commands

Use a JSON configuration with `gateway`, `programmer`, `dut`, `peer`, `vivado`,
`hw_server`, `cable`, `ltx`, `remote_artifacts` (remote bitstream/LTX paths mapped
to SHA256), `trace_map`, `trace_map_sha256`, `pipeline` (provenance), `peer_ip`,
`peer_mac`, `peer_management_ip`, `export_port`, `peer_python`, `transfer_root`,
and `transfer_url`. Select addresses and artifacts for the actual reservation.
The transfer endpoint must be running and reachable from the peer, with adequate
space. The existing `tools/paper/transfer_results.py` service supplies this during
campaigns; do not provision another server on an occupied endpoint. The peer's
host Python must be available outside benchmark mount namespaces. The campaign
uses `python3` from the host PATH; never reference a private client-image path.
Capture does not reconfigure NICs, boot, reset, or change provisioning.

For the existing 519555 benchmark topology, resolve this explicit configuration
from its immutable hardware manifest:

```sh
python3 tools/enzian/trace_capture.py campaign-config --config HARDWARE.json > TRACE.json
python3 tools/enzian/trace_capture.py capture --config TRACE.json --output NEW_DIRECTORY
```

The CLI refuses to overwrite a capture. Each session saves configuration, source
hashes, the matching map, original VIO outputs, pre/post-freeze status, stable
write pointer, receiver logs, complete raw bytes and SHA256, JTAG comparisons,
restoration state and a relative-file hash manifest. A per-target lock serializes
canonical control operations. Trace recording remains frozen after restoration.
Only unwrapped captures of at least 4096 bytes are currently supported; wrapped
buffers fail explicitly rather than silently persisting a prefix.

`capture.json` is durable progress/failure state. `summary.json` exists only after
successful persistence, integrity and restoration. `manifest.json` retains partial
failure evidence too. Stored trace loss/event-count validation belongs to the
matched analysis tool; recorder flags are preserved without interpreting a sticky
post-freeze loss bit as pre-freeze loss.

Ethernet failure is terminal: retain local/peer partial files, restore controls
when a saved snapshot exists, keep tracing frozen, and do not reset or fall back
to a full JTAG read. A primary failure remains visible if cleanup also fails.
If the receiver completed but export failed, explicitly retry export only:

```sh
python3 tools/enzian/trace_capture.py recover-export --config TRACE.json --output EXISTING_DIRECTORY
```

This requires a saved receiver-complete receipt and unchanged frozen recorder.
Each export attempt retains prior partial files. It does not trigger another dump.
Successful remote RAM copies are retained until the caller chooses to release
those resources after validating local persistence.

For recording-off controls without a dump, `freeze --config TRACE.json --output
CONTROL_DIRECTORY` saves the original controls and freezes only; `preserve` on
that same directory restores saved values while keeping recording frozen.
`status` reads the current recorder. Never call restore under a fresh label:
the session directory owns its snapshot, not an independently invented name.

## Validation

```sh
nix develop -c python3 -m unittest discover -s tools/enzian/tests -p test_trace_capture.py
nix develop -c python3 -m unittest discover -s data/eci/sys_trace/tests -p test_dump_trace_udp.py
```

Campaign tests additionally enforce no capture calls for loaded or RTT-only
cohorts. Source changes require a reviewed compatible campaign resume, not a new
hardware acquisition of every completed cohort.
