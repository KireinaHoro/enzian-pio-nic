# CPU-NIC DPDK hot baseline

`summary.json` is reproduced by `python3 summarize.py`. CSVs include warmups;
validation joins every request to the client and checks the measured MAC interval
against client elapsed time. Percentiles use linear interpolation. Run manifests,
module hashes, isolated CPUs, binary hashes, return codes and NIC restoration are
in the adjacent JSON files. `image.json` identifies the runtime-only image.

`acquire.sh` is the acquisition recipe used for these samples, executed as root
inside a private mount namespace (`unshare -m bash acquire.sh ROLE LABEL COUNT
WARMUP [DELAY_NS]`). It mounts the matched image without replacing other processes'
store mounts. It uses fixed campaign-specific RAM output names and refuses reuse
through the maintained runner. For another campaign, change those paths and stage
the matched image before use. Follow the repository reservation and deployment
procedure; verify live addresses/MACs, isolated CPUs and board state first.

Run server on zuestoll14, wait for `LABEL-ready`, then client on zuestoll07.
The exact cohorts were `pilot 100 16`, `delay20us 100 16 20000` (server-only delay),
and `hot 10000 128`. The responder's first-request wait is bounded at 120 seconds.
Both sides use the same image, while the server additionally enforces timestamp
module provenance. Build it with `nix build .#trafficGeneratorImage`.

The data is acquired with hardware timestamp instrumentation enabled. The delay
control checks interval sensitivity, not the overhead of enabling timestamping.
Only the hot cohort is intended as the CDF baseline; pilot/control are validation.
