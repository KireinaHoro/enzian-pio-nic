# Inline-TX null-RPC measurement inputs

This campaign uses unchanged pipeline 518689 hardware (`45cbfe52`) and rebuilt
software `8a1521ee`. Build the pinned image with:

```sh
nix develop -c nix build --impure --expr '(import ./tools/enzian/nullrpc_inline_build.nix {}).deploy' --out-link out/nullrpc-inline/deploy.img
```

The adjacent JSON report records hashes, CI jobs, actual CPU assignments,
clock-fit bounds, error recovery and limitations. `summary.json` is the setup
harness result: two CCPI bring-up failures, followed by successful cold boot and
setup traffic. Four sequential measured cohorts then ran on that boot. The
cold flush domain has four workers; the measured domain has one.

The acquisition scripts are exact recorded inputs, not safe replay defaults.
Check reservations, preserve existing captures, use fresh local/shared/ba2
labels and stage matching artifacts before a new harness run. Do not replay
these paths against the current services. `hot-off2` is the valid kernel-off
hot retry; the original barrier failure sent zero requests. The corrected
export script produced 4,032 runtime rows without drops. Full failures and raw
captures remain under `out/nullrpc-inline/` and peer RAM; they are not checked
into Git. Prior campaign captures were verified before the board reset.

For analysis, restore each cohort's client CSV, server CSV, trace map and raw
capture at its recorded path. Run `analyze-cached.py` (a cached wrapper around
`data/eci/apps/plot_latency.py`) with:

```text
CLIENT.csv SERVER.csv --no-plots --worker-cpu-base 44
--trace-process-id 1668 --combine-rpc-ingress --rx-trace-invalid-host-id 63
--trace-map out/contiguous-transport/hw-2884502/lauberhorn_trace_dma_map.json
--trace-dump RAW.bin --audit-json SEED.json
```

Keep this seed audit: only its hardware identities/times are used to extract
anchors; its legacy CPU clock fit is not a calibrated one-way measurement.
Then run, through `nix develop -c python3`:

```text
tools/enzian/transport_anchors.py --audit SEED.json --events latency-events.json
--trace RAW.bin --map TRACE_MAP.json --capture-summary CAPTURE_SUMMARY.json
--output ANCHORS.json
```

Repeat the first analysis with `--transport-anchors ANCHORS.json` and a distinct
corrected audit output. The extractor requires unwrapped bounded captures,
matching hashes, no stored loss markers, unique control request/response joins,
and one TX control forward/ack. The analyzer rejects causally inconsistent
clock fits. Half-RTT symmetry is still an explicit assumption; bounds are in
the adjacent report. Anchors-v2 add control-forward proof to the first three
cohorts without changing any response/request timestamps or clock fits.

Regenerate activation/handoff analysis with the corrected audits, then use
`report.py`, `control-report.py`, and `verify-inline.py`. The latter checks
commit-to-egress transactions: pre-commit cold PREEMPT cleanup is a separate
operation and may invalidate overflow lines. The complete plotting command is:

```sh
nix develop -c python3 data/eci/apps/plot_hot_cold.py \
  --captures out/nullrpc-inline --handoff out/nullrpc-inline/handoff.json \
  --trace-map out/contiguous-transport/hw-2884502/lauberhorn_trace_dma_map.json \
  --labels hot-off2 cold-off hot-on cold-on --output out/nullrpc-inline/figures
```

The combined PDF is `hot-cold-latency.pdf`; SVG/PNG/PDF panels and their selected
request data accompany it. Raw RTT/MAC distributions use all 1,000 measured
requests per cohort. Waterfalls use real requests nearest median RTT with
kernel tracing on. Kernel-off controls retain runtime and FPGA probes.
