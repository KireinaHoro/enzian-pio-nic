# Hardware regression localization

`summary.json` contains a conserved FPGA-clock comparison of the preserved
516232 and 518689 hot kernel-off captures (1,000 requests each), source findings,
provenance and remaining limits. It does not use the fitted CPU clock.

Scripts are the recorded analysis inputs, run from the repository root through
`nix develop -c python3`. They expect the captures at their recorded paths and
write ignored output under `out/nullrpc-hw-regression/`. No hardware access is
performed. Recreate the old anchors with `tools/enzian/transport_anchors.py`,
using the paths in the summary's old provenance and its cached latency events;
the resulting file is `out/nullrpc-hw-regression/old-hot-anchors.json`.

`compare.py` asserts nonnegative, per-request MAC conservation and computes
means/medians. `app-boundaries.py` uniquely joins app/sys events to further
localize RX/TX transport intervals. `dcs-spotcheck.py` records representative
real requests nearest each MAC median; the compact old/new event timelines
are included here. Means, not sums of independent medians, provide the exact
cross-cohort additive comparison. Full raw captures and full per-request
analysis remain in the ignored output trees with recorded hashes.
