# Upstream Dandelion baseline on Enzian

This packages the unmodified pre-Sven server and the original SOSP artifact
load generator into an AArch64 SquashFS runtime closure. Building is local;
none of these commands reserves, provisions, contacts, or modifies a board.
The October 8 authorized two-machine unloaded campaign passed; see the
[measurement record](../../../docs/research/dandelion-baseline.md).

## Pinned inputs and scope

- Server: `eth-easl/dandelion` commit
  `98c4968017c050391475a20da8e30bbabf6ea618`, immediately before Sven's port.
- Client: `eth-easl/dandelion_sosp_ae` commit
  `2a8e23d0aa641f9de91cbc35723ca2c6cd08536e`, unchanged `client/` Rust sources
  and its original Cargo.lock.
- Toolchain: the platform's pinned Nixpkgs and Crane from `flake.lock`.
- Server dependencies: this directory's Cargo.lock, generated against the
  upstream workspace. That revision has **no root Cargo.lock**; the old
  `server/Cargo.lock` does not describe its current workspace dependencies.
  This is a newly frozen dependency resolution, not recovered paper versions.

No Rust source, manifest, function ELF, process isolation, scheduling or
serialization code is patched. The prepared server source differs only by the
addition of the root Cargo.lock. Both binaries are release builds. Server
features are `mmu,reqwest_io`; the diagnostic variant additionally uses
`timestamp,archive`. The artifact's older `controller` feature does not exist
in this revision.

The worker is built separately, using the artifact's AArch64 flags:
`-C target-feature=+crt-static -C link-arg=-Wl,-fuse-ld=lld,--image-base=0xaaaaaaaa0000`.
`PROCESS_WORKER_PATH` is supplied by an installation wrapper, which can be
overridden explicitly for a separate control. The function ELF is the existing
`test_elf_mmu_aarch64_matmul` fixture, copied without rebuilding or modifying it.

The public artifact README supplies Figure 6/8/10 experiments. Its Figure 6
design runs 128x128 matmul, 97% hot function loads, 10,000 cold names, 180 seconds
per rate, coarse/fine rate steps 200/10. Each call still gets a fresh process.
Its source exposes `closed-unloaded`, which we use for a **Table-1-like** 1x1,
100% cold-load experiment. Choosing 1,000 samples and 1,000 cold names is our
explicit local preset, not a recovered Table 1 run configuration. Only the
process-backend matmul workloads are packaged here, not all paper applications.

The artifact points at the older `debug/hybrid_connection_upgrade_absolute`
branch; the public server also has `archive/sosp2025`. The requested March 2026
pre-port commit is **not claimed to be the exact paper revision**. Comparisons
on Enzian also differ from Morello/CloudLab hardware and kernel versions.

## Local build

From the platform repository root:

```sh
nix build --impure --file tools/enzian/dandelion-baseline/default.nix \
  image --out-link out/dandelion-baseline-image -L
nix eval --impure --json --file tools/enzian/dandelion-baseline/default.nix \
  --apply 'f: builtins.mapAttrs (_: v: toString v) (builtins.removeAttrs (f {}) ["shell"])' \
  > out/dandelion-baseline-paths.json
python3 tools/enzian/dandelion-baseline/verify.py out/dandelion-baseline-paths.json
```

`image` uses the same Nix `make-squashfs.nix` store-closure format as previous
Enzian application images. It contains server variants, a static worker, the
original client, matmul fixture, and a runner/diagnostic runtime. It does not
include a Lauberhorn module, runtime, FPGA image, or compiler.

For local x86-64 protocol smoke tests, use `--arg native true`; those results
are not Enzian performance evidence. The native build uses the matching
unmodified x86-64 function fixture and static worker.

```sh
nix build --impure --file tools/enzian/dandelion-baseline/default.nix \
  --arg native true runner --out-link out/dandelion-baseline-native
nix eval --impure --json --file tools/enzian/dandelion-baseline/default.nix \
  --apply 'f: builtins.mapAttrs (_: v: toString v) (builtins.removeAttrs (f {native=true;}) ["shell"])' \
  > out/dandelion-baseline-native-paths.json
# The local check isolates both fixed server paths and all network traffic.
# CAP_SYS_PTRACE exists only inside the new user namespace; host policy stays intact.
nix develop --impure --file tools/enzian/dandelion-baseline/default.nix shell -c \
  bwrap --unshare-all --uid 0 --gid 0 --cap-add CAP_SYS_PTRACE --die-with-parent \
  --ro-bind / / --dev /dev --tmpfs /dev/shm --tmpfs /tmp --proc /proc \
  --bind "$PWD/out" "$PWD/out" \
  python3 "$PWD/tools/enzian/dandelion-baseline/smoke.py" \
    "$PWD/out/dandelion-baseline-native-paths.json" "$PWD/out/dandelion-baseline-smoke"
```

This requires local user namespaces and CPUs 0–3 in the allowed affinity mask.
Do not relax host ptrace policy if the local environment cannot run the check;
retain the error as an infrastructure limitation.

## Board commands

Recheck reservations/state, preserve existing captures, and use the repository's deployment/console harness to mount the
closure. Provisioning remains exclusively through `emg`. Do not run the
artifact's CloudLab/Ansible setup on Enzian: it installs/builds software on
targets and changes machine configuration.

The runner accepts explicit commands and has no SSH or provisioning logic:

```sh
# DUT: foreground server and fresh working directory.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline server NEW_STATE_DIR 4
# Peer: original rload, one outstanding request, 1x1, 1,000 cold requests.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline unloaded DUT_IP NEW_RESULTS_DIR
# Peer: original Figure 6 process-backend sweep (use a fresh server registry).
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline figure6 DUT_IP NEW_RESULTS_DIR
```

For the Figure 6 configuration, use total CPU count 16, matching the artifact's
16-core host, with two frontend cores and one dispatcher core; that leaves 13
compute engines. A total count of four leaves one compute engine. These are
**absolute upstream CPU IDs 0..N-1**, not Lauberhorn workers 44..47. Upstream
controls placement; do not rewrite it or silently equate total cores with
compute-worker count. Choose an appropriate idle target before running.
The server requires executable `/dev/shm`; the deployment procedure must
check that prerequisite and preserve its original mount configuration.
Upstream uses fixed `/tmp/dandelion_server` and `/dev/shm/shm_*` names and removes
them on exit. A fresh working directory does not isolate these paths. The runner
refuses existing state; run only on an otherwise idle target, with no concurrent
Dandelion instance. Native smoke checks use private mount/network namespaces.
Use the ordinary Ethernet HTTP path on port 8080, independent of Lauberhorn.

Retain image/binary hashes, kernel, CPU model/frequency policy, CPU affinity,
command lines, stdout/stderr, and original latency CSVs. Inspect every CSV for
non-200 status/timeouts and verify the expected request count before accepting
results: the unchanged client can exit successfully after HTTP errors.
Never overwrite an existing results directory.

## Measurement definitions and timestamp limitation

The original `rload` uses `SystemTime` before `request.send().await` and after
that future returns. It reads and checks the body **after** stopping the timer.
Its `responseTime` CSV field is therefore HTTP response-header latency in
integer microseconds, not full-response RTT. Preserve this definition for
reproduction; do not silently relabel it as the previous UDP client's RTT.
It checks the returned data length and the final matrix element.

The pre-port timestamp implementation differs from the artifact's plot parser.
It has ten points rather than the older Arrival/EndService event format.
Further, `/stats` uses `Debug` on `UnsafeCell<Duration>`, which prints
`UnsafeCell { .. }` instead of timestamp values. The untouched artifact parser
cannot produce Table 1's breakdown from this revision's `/stats` response.
The actual values are still included in each BSON response's `timestamps`
string, truncated upstream to integer microseconds.

The separate `diagnostic` command preserves those strings and validates the
**whole** returned matrix, without modifying either upstream program:

```sh
# DUT: fresh diagnostic server and registry.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline server-timestamps NEW_STATE_DIR 4
# Peer: separate diagnostic client; not the paper's latency experiment.
/nix/store/...-dandelion-baseline-runner/bin/dandelion-baseline diagnostic \
  --host DUT_IP --out NEW_DIAGNOSTIC_DIR --samples 1000
```

It reports parsing, context loading, input transfer, `EngineStart→EngineEnd`,
and server entry through FutureReturn. It separately measures client elapsed
time through the full body. No EndService point exists, so neither the paper's
get/send-output interval nor its server total is reconstructed. Do not subtract
cross-machine clocks or combine these separate cohorts into an exact breakdown.

## Evidence

Build/protocol-check results are recorded in
[the baseline measurement record](../../../docs/research/dandelion-baseline.md).
No board results are implied by a successful image build.
