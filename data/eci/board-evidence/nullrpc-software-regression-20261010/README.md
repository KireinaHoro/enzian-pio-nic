# CPU-side regression localization

Run `compare.py` from the repository root through `nix develop -c python3`.
It uses the exact measured request IDs from the old/new hot audits, excludes
warmups, and checks conservation across eight adjacent CPU timestamp intervals.
The full CSV paths and hashes are in `summary.json`. No hardware access occurs.

Assembly excerpts are from the deployed runtime binaries whose hashes are in
the summary. They substantiate additional XDR sizing/reservation work, larger
trace-state setup, disabled PMU checks and a disabled event call inside the
handler-labelled interval. They are not per-function cycle measurements.
The old pre-doorbell timestamp is absent, leaving a 44.915 ns aggregate boundary
remainder rather than an exact function-level explanation of every nanosecond.
