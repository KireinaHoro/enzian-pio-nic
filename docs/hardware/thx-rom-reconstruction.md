# Reconstructing Jasmin's THX-informed DC ROM

Reproduced 2026-10-01. Maintainer confirms that the simplification and the later
F31/A31d wire change came from Jasmin's THX work. The checked-in artifacts let us
reconstruct the workflow, and reveal a distinction between the simplified DCS ROM
and her **corrected** generator. This supersedes treating all three removed
messages (V32, V32d, A22) as equally absent from THX.

## The available chain

1. [Extracted remote FSM](../../deps/thx-fsms/legacy/remote_fsm.txt) describes
   single-line observable THX behavior. The JSON version is under
   `specifications/remote_fsm.json`.
2. [check_DirC_csv.py](../../deps/thx-fsms/legacy/check_DirC_csv.py) maps DC symbols
   onto THX symbols, reads a DC CSV, reconstructs its stable-to-stable histories,
   and explores its composition with that extracted remote FSM.
3. [eci_stmc_auto_corrected.pl](../../deps/thx-fsms/legacy/eci_stmc_auto_corrected.pl)
   is Jasmin's corrected version of the existing DC generator. Commit `7f9333b`
   (Jasmin Schult, 2025-10-21) adds both it and the checker; the legacy README
   explicitly describes that relationship.
4. Running the corrected Perl generator produces `ECI_CC.csv`. The existing DCS
   `cc_rom_from_cc_tbl.pl` and templates turn that CSV into the package and SV ROM.

This is **THX-informed correction of the existing equation generator followed by
compatibility checking**, not direct ROM synthesis by the JSON-to-TLA generator.
No checked-in automatic transformation from THX JSON to the corrected Perl
source was found. We can reproduce the artifacts and checks, but not claim to
have recovered every manual reasoning step or the exact historical command log.

## Which messages disappear, and which remain?

The checker maps `V32 -> ES`, `V32d -> ES_d`, and `A22 -> FAS`. Inspection of all
outputs in the extracted remote JSON finds **zero ES, zero ES_d, and ten FAS
transitions**. Jasmin's [message table](../../deps/thx-fsms/figures/link-msgs.png)
identifies FAS as HAKS. Her corrected generator:

- Removes V32/V32d and their remote equations.
- Retains A22, unlike the simplified DCS ROM on `replyRA3`.
- Adds races from `2_A32d` involving V31 with A22, or V31d/WDDA with A22,
  including subsequent requests. The generator includes a repeated dirty
  equation family; reproducing it unchanged works.

A concrete extracted transition is a CPU in S awaiting its upgrade response:
`S[(LE, SE_d)] + FS_d -> ... + FAS`. Thus A22 cannot simply be declared absent
from the extracted FSM. Whether a restricted application never generates F32
is a separate interface restriction. The unrestricted legacy checker exercises
LC and LR, both of which can generate F32.

After adding RA3, the corrected locking ROM has **47 states/1128 explicit entries**.
Against the evolved DCS RA3+simplified ROM (47/1081), it adds exactly the 47 A22
entries, with **no differences on shared entries**. This gives a precise
reconstruction of what is missing from that simplified artifact.

## Reproduction and results

[reconstruct_thx_rom.py](../../tools/progress/reconstruct_thx_rom.py) runs the
historical generation audit, then generates Jasmin's original, its RA3 port and
a port onto the **existing non-locking generator**. The latter copies only her
`rmt_evts` and `rs_eqns` blocks into that generator and changes its 17 R12 terminal
equations to RA3; local non-locking semantics remain intact.

```sh
nix develop -c python3 tools/progress/reconstruct_thx_rom.py \
  --output out/progress/thx-rom-reconstruction
```

Pinned Perl/autovivification, Python and Verilator suffice. Generated CSV, ROM,
package, logs and detailed summary stay under ignored `out/`. The
[evidence manifest](../../model/steady/thx-rom-reconstruction-evidence.json)
records revisions, input hashes, counts and unique diagnostics.

| CSV checked against extracted THX | States / explicit ROM entries | Checker reports |
| --- | --- | --- |
| Original locking | 78 / 2028 | 22 problems |
| Simplified locking, RA2 | 48 / 1104 | 32 problems |
| Simplified locking, RA3 | 47 / 1081 | 38 problems |
| Simplified non-locking, RA3 | 43 / 946 | 35 problems |
| Jasmin corrected, RA2 | 48 / 1152 | 4 documented stable-request warnings only |
| Jasmin corrected, RA3 | 47 / 1128 | Same 4 warnings only |
| Existing non-locking + Jasmin corrections + RA3 | 43 / 989 | Same 4 warnings only |

The four warnings are IS_d or IE_d arriving while the directory is in stable
remote S or E. The script's final note explicitly permits mismatching stable
upgrade/request warnings because an eviction/downgrade can still be in flight.
We retain these diagnostics rather than report zero problems. The simplified
RA3 locking case additionally reports unhandled FAS during LC/LR histories.
All three corrected candidates pass standalone ROM lint. All 989 non-locking
entries match corrected-locking shared entries after erasing the post-LCA/LCIA
lock marker.

The legacy checker exits zero even when it reports problems; the wrapper parses
and checks its output. It uses set-valued messages (collapsing duplicates), a
15-step reconstruction bound and selected spontaneous events. Its
`has_transitions` flag is set before checking that a transition exists, so its
"deadlock" count is not a complete deadlock test. Zero deadlock reports must not
be advertised as liveness proof. It does not model finite shared resources,
concrete wire decoding, payload values, full NIC behavior or modern AXI fencing.
These results reproduce its intended compatibility diagnostic, not discharge all
single-transaction or system progress obligations.

## Why the F31 wire change belongs with this reconstruction

The maintainer confirms `2cee502` was requested by Jasmin. The available mapping
explains the correspondence: the checker maps **F31 to FE_d**; her message table
maps FE to **FLDX**, acknowledged by FA (**HAKD**). FV/FEVX and VA/VICDHI are
separate abstract operations. Changing F31 from FEVX_EH to FLDX_EH and classifying
its HAKD completion as A31d therefore aligns this wire path with the checker's
abstract F31 exchange.

The remaining LC issue is classification: F32 also elicits HAKD, so the opcode
alone cannot distinguish A31 from A32 (including data-bearing variants). Current
RTL chooses A31d for data-bearing HAKD globally. General LC/F32 support requires
using outstanding forward context to disambiguate, or an explicit interface
restriction excluding that operation. This is not evidence against the requested
F31 change. Corrected abstract-ROM compatibility does not repair this decoder
ambiguity automatically.

## Resulting candidate

Continue from the **existing non-locking generator**, port Jasmin's corrected
remote equations (retaining A22), and add RA3: the reproducible candidate is
43 states/989 entries. Do not use the 43/946 candidate as the full corrected
THX contract. The directory miss-to-invalid write fix, UL interface changes,
HAKD/context handling, response-fence proof and shared release-service checks
remain required before a production switch. No production submodule was changed
by this reconstruction.
