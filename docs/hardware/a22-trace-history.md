# A22/HAKS in retained captures and THX history

Checked 2026-10-01. **No A22/HAKS observation was found in the inspected captures.**
The available `thx-fsms` history nevertheless contains FAS (the abstract HAKS/A22
message) from its first FSM revision. Capture absence does not establish that
this message is impossible.

## Capture scope

[scan_a22_traces.py](../../tools/progress/scan_a22_traces.py) checks:

- Ten retained `data/eci/sys_trace/*/events.tar.gz` archives: **161,565,268 CSV
  event rows**, zero HAKS. These include iperf timeouts, interrupt choking,
  FLDX/invalidation-control variants and RPC/bypass failures. These are existing
  decoded exports; this scan does not regenerate them from raw data or establish
  their historical map provenance/completeness anew.
- **85 parsed DCS/ECI CSVs**, 36,942 rows, under `data/eci/dcs_trace`: zero A22
  request labels or HAKS wire labels. Copies/stages can overlap; these counts
  are not distinct transactions.
- The **322,322,624-byte F_MAC1** capture from revision `5cda901` and the recovered
  **211,086,144-byte F_MAC3** capture from `c5be158`: zero HAKS among either
  presented or accepted incoming ECI response samples, across app/sys stages
  and both slices. These are two frozen histories, not separate counts for the
  three identical F_MAC1 reads.

The full DMA files contain 20,145,164 and 13,192,884 128-bit sample slots. Incoming
response samples are selected using the matching maps' source/channel fields;
MRSP opcode 7 (bits 63:59) identifies HAKS. Interrupt, local-command, request,
forward and data payload sources are not misinterpreted as MRSP headers. Counts
include duplicated observations across app/sys stages and are not transaction
counts. Bubble markers are distinguished from nonzero sample-loss records.

Raw-capture hashes match the existing board evidence:

- F_MAC1: `b7532515fae2e5d4b500c302eb55d4bd75f2a80312130d9e18dc7532cb951c68`
- Recovered F_MAC3: `671ee92e64b5d03fabca4e97fc5efbf6ddb5fa0df1eae4af23a408a488cede5e`

Both maps are byte-identical to the corresponding archived CI hardware maps,
SHA-256 `705a7fc5f1d16499bf849e2583c5249335fd4001155ca011f387977e99dd64fe`.
See [board provenance](../development/cmac-trace-diagnosis.md#f_mac1-board-validation-september-28).
No new board run or remote capture was performed. Raw-only historical captures
without the selected decoded exports were not exhaustively re-decoded; this is
not a claim about every trace ever collected.

## Available THX history

All six commits reachable from the locally available refs were inspected:

| Revision | Date | Relevant contents |
| --- | --- | --- |
| `b269076` | 2025-06-19 | Initial README only; no FSM or DC generator |
| `982d521` | 2025-06-20 | First legacy and JSON remote FSMs; both contain ten FAS-output transitions |
| `e46f276` | 2025-06-20 | Event descriptions; FAS transitions unchanged |
| `7f9333b` | 2025-10-21 | Jasmin adds checker and corrected DC generator; generator retains A22 (16 occurrences), excludes V32/V32d declarations |
| `a97682d` | 2026-05-04 | TLA generator added; relevant FSM/DC sources unchanged |
| `a137b82` | 2026-05-06 | TLA transition sorting; relevant FSM/DC sources unchanged |

There is **no A22-free corrected generator or FAS-free remote FSM in this
available history**. This does not rule out an earlier unpublished experiment.
The DCS simplification committed in May 2025 predates the first checked-in THX
FSM; Git alone does not reconstruct the rationale of that earlier artifact.
Maintainer attribution of that work to Jasmin is retained. Her later checked-in
correction is the reproducible source for retaining A22.

## Reproduction

```sh
nix develop -c python3 tools/progress/scan_a22_traces.py \
  --output out/progress/a22-traces
```

The [evidence manifest](../../model/evidence/steady/a22-trace-history-evidence.json) lists
individual paths/counts, raw hashes, response-opcode counts, source-level counts
and historical revisions. Local ignored capture archives are required to repeat
the full scan; unavailable DMA files are explicitly reported as skipped.
The result complements the [THX reconstruction](thx-rom-reconstruction.md):
we have extracted-FSM and compatibility-check evidence for A22, but no observed
A22 in this inspected Lauberhorn capture corpus.
