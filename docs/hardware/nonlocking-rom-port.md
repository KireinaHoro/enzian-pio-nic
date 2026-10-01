# Porting the existing non-locking ROM

Audit and reproducible trial generation, 2026-10-01. Start from the existing
`dc_stmc_auto_no_lock` generator/ROM, as requested. Production still uses auto-lock;
this investigation does not install a new table or change controller behavior.

## History and exact semantic changes

The active DCS pin `eb64df4` uses the old generated auto-lock table selected by
`vivado/eci/create_project.tcl`, not the evolved table on `replyRA3`. The existing
non-locking generator has not changed since `9c9abae` (2023 synchronization).
There are two table locations: generated artifacts under `gen_dc_protocol`, and
copies under `dcs/dcu`. They must not be confused when selecting a build baseline.

| Commit | Change and port implications |
| --- | --- |
| `9c9abae` | Baseline: locking 78 states/2028 explicit entries; non-locking 72/1800. Shared entries agree after erasing the post-LCA/LCIA lock marker. |
| `a818e5d` | Changes 24 remote equations ending `R12, 2pRA2` to `R12, 3pRA3`. An absent R12 now performs RDD, waits in `s1__3pRA3`, and RDDA sends RA3, ending in state 3. Existing R13/RA3 machinery is reused. Also changes one equation's **starting state** from 3 to 2: `2, V32, V21, R12, 3pRA3`. This extra change generates unexpected V32 acceptance and states such as `s1__3pRA3_R12`; its rationale is unrecorded. Do not include it in a pure exclusive-reply port. Historical generated table: 84/2184. |
| `0443f18` | Removes SEND_RA2 handling from controller and outgoing-header generator. This is outside the ROM; only appropriate once the selected table no longer emits RA2. Active pin still has RA2 handling. |
| `4bc6183` | Installs a simplified **RA2** generated locking ROM: 48/1104. We reproduced it exactly at the explicit-transition level by removing V32, V32d and A22 remote events and all remote equations mentioning them from the old generator. No replacement state-machine algorithm is needed. |
| `cf9b1cf` | Copies the simplified table/package into `dcs/dcu` and comments out decoder cases for VICC without/with data (V32/V32d) and HAKS (A22). Thus simplification includes a wire-input contract change, not just removal of unreachable enum names. |
| `acbf558` | Merges simplification into `replyRA3`; generated table combines simplification with RA3 (47/1081). It does not merely take the RA2 table from the other parent. |
| `2d77ada` | Updates locking generator/CSV to remove those events/equations. Its regenerated explicit transitions match the merged RA3+simplified ROM. The earlier anomalous V32 equation disappears with the other V32 equations. Most remaining script diff is whitespace; generation algorithm is unchanged. |

The simplification also changes 24 entries on surviving state/request pairs to
`NOT_ALLOWED`. Examples: `s1__3 + V21` previously waited for V32;
`s1pCI__1_A31d + A21` previously waited for V32; several R23 requests previously
stalled. The [machine-readable evidence](../../model/steady/rom-evolution-evidence.json)
lists every changed shared entry and counts removed entries. Therefore the
simplification is a restriction of accepted histories, not a demonstrated
semantics-preserving minimization. RA3 alone is not evidence for that restriction:
LC/LR can still downgrade a remote line via F32. The available commit messages do
not establish why THX cannot produce the excluded events/races.

## Trial ports from the existing non-locking source

The [audit utility](../../tools/progress/audit_rom_evolution.py) preserves the
existing non-locking local seeds, actions and generator algorithm. It applies
only the remote-equation edits above, uses its existing templates/CSV-to-ROM
converter, and writes candidates under ignored `out/`. No generated production
file is hand-edited.

| Non-locking candidate | States | Explicit entries | Change from original |
| --- | ---: | ---: | --- |
| Original | 72 | 1800 | Reproduces existing ROM |
| RA3 only | 71 | 1775 | One surviving entry changes (`s1__1,R12`); obsolete 2pRA2 state disappears |
| Simplification only, RA2 | 44 | 968 | 832 entries removed; 24 surviving entries change |
| RA3 + simplification | 43 | 946 | 854 entries removed; 25 surviving entries change |

All 946 candidate entries in the combined port match the evolved locking table's
shared entries after replacing next-state `s1pUL__` with `s1__`. The separate
simplification-only comparison matches 968 entries. These checks establish
mechanical portability of these changes onto the existing non-locking source.
They do not prove equivalence on lock states, legal THX histories, payload values,
resource progress, numeric enum encodings or safe integration.

All eight regenerated historical/candidate ROMs pass standalone Verilator lint.
Reproduction uses pinned Perl + autovivification and Verilator in `nix develop`:

```sh
nix develop -c python3 tools/progress/audit_rom_evolution.py \
  --output out/progress/rom-evolution
```

Local DCS Git history including the named commits is required (a shallow checkout
may need those commits fetched). Each output directory retains generator, CSV,
package, ROM and generation/lint logs. Summary records full revisions, generator
hashes and symbolic differences. Generated date comments are not compared.

## Wire changes are a separate integration issue

Two later changes are already present in active `eb64df4`, independently of the
old ROM it selects:

- `cd4cb3b` clears the SINV_H data mask.
- `2cee502` changes SEND_F31 from FEVX_EH to FLDX_EH and decodes data-bearing HAKD
  as A31d instead of A32d. Its commit and decoder comments explicitly say this
  makes the LC downgrade path inoperative; the ROM still contains LC/F32 paths.
  Non-data HAKD handling is separate. The old VICDHI decode also remains.

A port must retain the intended LCI wire behavior, and either restore unambiguous
LC response classification using appropriate transaction context or explicitly
exclude unsupported LC use. Passing an absent-LC trace test does not validate
LC against a dirty CPU line. These changes cannot be solved by copying the
simplified ROM. The active decoder also still handles V32/V32d/A22; installing
simplified definitions requires coordinated decoder edits or a deliberate
compatibility strategy.

## Recommended implementation order and remaining checks

1. Base the candidate on the **existing non-locking ROM/generator**. Port RA3 as
   an independent change; retain older race coverage initially. Treat historical
   simplification as a separate optional change until its THX event restrictions
   are justified. Both trial ports are reproducible now.
2. Fix the known full-miss commit hazard: miss-to-invalid must not write the
   arbitrary TSU way, hit-to-invalid must remove the matched way, and miss-to-valid
   must allocate a free way. Preserve lookup context through output backpressure.
3. Coordinate request/package enums, decoder, controller/header actions, NIC UL
   generation and trace metadata. The old non-lock package has no UL. Numeric
   encodings and trace maps must follow the selected generated package.
4. Check actual directory RAM preservation for hit/empty/full misses, transient
   collisions, clean/dirty LCI and response/victim reordering. Resolve the LC/HAKD
   contract before claiming general LC support. Standalone ROM lint is not these
   integration tests.
5. Extend the AXI response-fence model to earlier/in-flight reads, replay and
   speculation, plus preemption/kill without a held doorbell. Compose the chosen
   endpoint with shared writeback/response service and rerun scheduling liveness.

The [directory-pressure audit](directory-pressure.md) remains the source for
current counterexamples and the full-miss write hazard. No production switch,
new integration simulation, board validation or new liveness proof is claimed
by this ROM-port investigation.
