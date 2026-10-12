# Simulation seed isolation (October 3)

Pipeline 516080 (`dae5cf2386d4f3731e67c8c0780c63363bfd8423`), job
2865583, failed `OncRpcSim/rx-independent-progress-2-stalls` with a cache-line
reload timeout at `0x1300` (DCS alias `0x1780`). The printed setup seed was
`-1760455454`, simulation seed `1699615821`. The other 12 gates passed;
preparation 2865596, hardware 2865597 and report 2865598 were skipped.

## Why the printed seed was insufficient

The old shared fixture reseeded `scala.util.Random`, then called
`dut.doSim(test.name)` without an explicit seed. Spinal's `newSeed()` drew
from the same global RNG (or `SPINAL_SIM_SEED`). Meanwhile DCS simulation
threads used that RNG for voluntary evictions, reload counts, reload delays,
partial writes and eviction-target selection. `ParallelTestExecution` permits
these consumers and other fixtures' reseeds to overlap. Mill's
`testParallelism = false` does not remove this ScalaTest concurrency.

This is observed, not just a possible race: using the original pipeline source
and original blocks `a21d410828bcdd3ebefe53a5928dbfed6746b10c`, the same printed
seed gives actual Spinal seed **367665076** when selected alone (and with the
short progress siblings), but **725543570** in a full `OncRpcSim` run. The latter
trace differs at the first DCS read, including the packet contents. Both local
runs passed: reproducing the missing seed contract does not recover the exact
CI interleaving. CI's log independently shows `rx-allcores` running throughout
the failing test, and additional simulations starting before it failed.

CI's transcript was lost because the artifact collector searched only
`/tmp/nix-build-*.drv-*`, while Nix retained the job under
`/nix/var/nix/builds/nix-534-1921337625`. The downloaded artifact zip contains only
the build log. We therefore cannot identify CI's actual derived seed or reconstruct
its complete random stream. The collector now follows the directory printed by
Nix and retains transcripts, waves, traces and seed/input/runtime metadata.

Seed format 2 passes the printed seed directly to Spinal and makes fixture
configuration local to the test invocation. DCS and repository test stimulus
use `simRandom`. An explicit `-DsimSeed` takes precedence over `SPINAL_SIM_SEED`;
otherwise a per-test seed is derived from the setup seed and test name. Format 1
numbers must be replayed against the old fixture; the change is intentionally
not sequence-compatible.

## Randomness and shared-state audit

- ECI packet generation, host IDs, AXI/AXI-Lite/CMAC stream backpressure,
  randomized signal assignments and randomized `SimMutex` arbitration use
  Spinal's per-simulation RNG. DCS was the active path using the global RNG.
  PCIe stimulus and blocks tests also had global-RNG calls, now replaced.
- `CoreState.threadDbMtx` was a singleton containing simulation-thread queues.
  It is now private to each host-model instance. Concurrent suites must not
  share a simulation mutex even if its random arbitration uses `simRandom`.
- Spinal passes the explicit seed to a fresh `CoreSimManager` and the Verilator
  backend. The backend seeds `VerilatedContext` before model construction;
  seed zero is normalized to one for the native backend. CPU-affinity selection
  uses the global RNG, but is not stimulus. A warm-initialization probe did not
  change the isolated target's DCS trace and was removed.
- DCS cache maps use `BigInt` keys, host maps use integer keys, and relevant
  choices use seeded insertion/content. No identity-based keys or wall-clock
  stimulus were found on this path. Hash iteration is not a cross-version
  contract: matching Scala/toolchain/source inputs remains required.
- Spinal's separate `AxiMemorySim` and Wishbone helpers still contain global-RNG
  calls. They are not used by this ECI fixture. Blocks' TraceBufferDMA tests
  instantiate `AxiMemorySim` with interrupts disabled, non-Altera writes and
  AXI reads disabled; its random branches do not drive those tests. These
  library helpers are not covered by this fix. Do not infer library-wide
  determinism when enabling their randomized paths.
- The fixture's default seed selection is intentionally random and recorded.
  `RecoveryStreamBufferSim` also has explicit fixed local RNG seeds; these are
  independent, deterministic streams. Timing measurements and trace file
  metadata are not used to choose stimulus.

## Underlying test failure

The progress test waited for a packet on the network, but did not wait for its
forked `txSendSingle` to return. That API still performs control-line reads and
voluntary reload checks after network completion. The next iteration could
write the same line while those reads were in progress. A test could also finish
with its last TX call still outstanding.

With format 2, `repeat-test` found two manifestations before the test fix:

| setupSeed | simSeed | Result |
| --- | --- | --- |
| 339593329 | 1107897168 | Voluntary reload mismatch after 14 successful repetitions |
| -1202280656 | 1024428291 | Same `0x1300` reload timeout as CI after 48 successful repetitions |

The mismatch was replayed twice with identical packet/event traces. The timeout
was replayed with an identical trace. In the timeout trace, the first TX call
starts reloading `0x1380` at simulation time 8372400; the next TX begins writing
it at 8430400. A dirty voluntary invalidation at 8567400 then overlaps the
opposite-line read at 8607400, followed by the DCS reload/LCI conflict.

The fix joins each TX call before reusing its QP or ending the test, while
preserving simultaneous RX/TX and unrelated stalled workers. It does not disable
voluntary evictions, relax data assertions, or change RTL, layout or DESCHED.
Both failure seeds pass after this fix, as does a 100-seed `repeat-test` campaign.
The final original-seed replay produces identical event-trace SHA-256
`0c1daf2385adf245d35ee877ad2d6f78a83f86f982ad8324f4d9e1a466bbf56d`
when selected alone and among the full ECI CI selection. In both contexts the
actual Spinal seed is now the printed `1699615821`. The final ECI run passed
53 tests (one existing ignored); recovery passed 4, blocks passed 19, and CI
artifact-collection regression checks passed 6.
These are finite simulation results, not board acceptance or a proof of all
possible coherence behavior.

Local raw evidence is retained under `out/seed-reproduction/`; compact provenance
and validation are recorded in the [evidence ledger](../../model/evidence/scheduling/simulation-seeds-20261003.json).
