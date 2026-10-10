# October 10 null-RPC acquisition inputs

These are the exact manifest and acquisition scripts for the matched 518689
campaign, retained as provenance. Run from the repository root with the pinned
Nix environment. Copy inputs into `out/nullrpc-contiguous` to inspect the recorded
commands; choose fresh local, shared and ba2 labels before any new acquisition.
Check reservations and preserve prior board evidence before using the harness.
Do not replay scripts against the currently running services or existing paths.

The first `hot-on` client startup failed before traffic because of a stale peer
overlay. The recorded measured retry is `hot-on2`. A cold-on Vivado exit-time
segfault occurred after its dump trigger; the receiver completed and its existing
RAM capture was exported without another trigger or reset. See the adjacent
JSON report for hashes, controls, recovery evidence and measurement limitations.

The four runtime/client joins use `data/eci/apps/plot_latency.py` with
`--worker-cpu-base 44 --trace-process-id 1661 --combine-rpc-ingress
--rx-trace-invalid-host-id 63`, matching per-phase CSVs and dump/map files.
The sentinel recovery is bounded by consecutive per-core dispatches and records
the observed ID in each audit. Full source captures remain under
`out/nullrpc-contiguous`; paths and hashes are in the adjacent report.
