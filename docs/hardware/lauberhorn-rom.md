# Selected Lauberhorn coherence ROM

Implemented on the Goal 2 branch, 2026-10-01. The downstream ECI build now selects
an LCI-only, non-locking ROM. This replaces the previous auto-lock selection;
it has not yet been validated on the board or through physical implementation.

## Source and interface

The only new tracked ROM source in the DC submodule is
[`dc_stmc_lauberhorn/eci_stmc_auto.pl`](../../vivado/eci/directory-controller-slice/gen_dc_protocol/dc_stmc_lauberhorn/eci_stmc_auto.pl).
It starts from the existing non-locking Perl generator, imports Jasmin's corrected
remote equations (including A22 races), changes R12 replies to RA3, and restricts
local operations to LCI. It does not synthesize the ROM directly from THX JSON;
see the [reconstruction and provenance](thx-rom-reconstruction.md).

There is no LC/F32, LR/LW, autonomous ICI, or UL. The application interfaces have no UL ports, router lookup or mux/demux;
LCIA completes the local invalidation. The selected decoder rejects LC and UL.
A22/HAKS remains accepted. Clean HAKD is classified as A31 when the retained
forward slot identifies an F31 for the same address; otherwise it remains A21.
Dirty HAKD uses A31d and completion waits for write-data acknowledgment.

The actual directory write enable now requires a lookup hit, or a valid next
state and a free way. Thus an absent LCI cannot overwrite an arbitrary full-set
way, while hit invalidations still remove their entry. Removing locks does not
remove software/AXI ordering obligations: withheld reload data must enforce the
2F2F transfer boundary, including previously accepted requests and speculation.

## Size

| Machine | States | Permitted transitions | Stall entries | Forbidden entries | Input/state entries |
| --- | ---: | ---: | ---: | ---: | ---: |
| Previously selected auto-lock | 78 | 261 | 692 | 997 | 1950 |
| Selected LCI-only non-locking | 14 | 35 | 50 | 167 | 252 |

Permitted includes valid no-action transitions. These are table entries, not
synthesis area or timing measurements. Earlier audits counted the per-state
default branch too: 2028 entries for the old ROM and 266 for the selected ROM under that convention. The table above
counts explicit input/state pairs only. The converter also injected an unused
F32 enum, which the new build removes. States fall by 82%, permitted transitions
by 87%, and input/state entries by 87%. This is specialization to Lauberhorn's
protocol, not equivalence for arbitrary memory-backed DC clients.

## Reproduction and evidence

```sh
nix build .#dcsRom --out-link out/progress/lauberhorn-rom
nix build .#checks.x86_64-linux.dcs-rom-thx
nix build .#checks.x86_64-linux.dcs-lauberhorn
nix build .#checks.x86_64-linux.dcs-pressure
nix build .#eciVivadoInputs --out-link out/progress/lauberhorn-vivado-inputs
nix develop -c mill --no-daemon gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci
```

[Nix generation](../../nix/hardware/dcs-rom.nix) pins Perl/autovivification and the
existing converter/templates, fixes the generated timestamp, and exports CSV,
SV, source hashes, statistics and trace enums. No generated CSV/SV is tracked in
the new source directory. The real-DCU simulation consumes this derivation;
Vivado receives it through the checked portable input bundle and `--dcs_rom`.
The project enables `DCS_LAUBERHORN`. Trace maps use the generated enum values.
The Scala NIC simulator is LCI-only, with no lock/UL state, and selects exclusive read replies;
it remains an abstraction of the DCS RTL.

The [THX check](../../tools/progress/check_lauberhorn_rom.py) composes the generated
CSV with the unchanged extracted remote FSM, restricting only FPGA spontaneous
operations to EW/LCI. It checks the two documented stable-request diagnostics
(IE_d and IS_d in remote E), and rejects other diagnostics. It also checks no F32
output and retains A22 table support. This bounded legacy checker uses set-valued
messages and incomplete deadlock detection: passing is compatibility evidence,
not a single-line correctness or shared-resource liveness proof.

The [real DCU bench](../../hw/test/rtl/dcs_pressure/DcsLauberhornTb.sv) checks:

1. 32 absent LCIs complete without UL or directory writes.
2. R12/RDDA fills all 16 ways of one set with exclusive CPU lines.
3. An absent LCI completes against that full set under 40 cycles of output
   backpressure without modifying the directory.
4. Every resident tag subsequently invalidates through F31 and clean HAKD,
   without UL; this also checks preservation of the full set's contents.
5. Dirty HAKD waits through delayed WDDA before LCIA.
6. The selected decoder rejects LC and UL.

It uses actual DCU/controller/TSU/tag RAM and a simulation XPM primitive. It does
not cover whole-slice queues, all response races, hardware arbitration fairness,
or THX replacement. The historical auto-lock pressure suite remains a regression
and counterexample diagnostic for the old configuration.

The [evidence manifest](../../model/evidence/steady/lauberhorn-rom-evidence.json) records
generated-artifact hashes and checks run.

Remaining work is in the [roadmap](../research/roadmap.md): extend the AXI fence
and shared response-service composition, actual transport kill cleanup, broader
scheduling liveness, whole-slice routing/backpressure, and matched board tests.

The broader ECI run exposed an uninitialized simulation-only router prefix
scoreboard. Its enabled bits now reset false, matching an empty thread database.
The directed reproducer passed with setup seed 585039714 and simulation seed
781529635 after the reset fix. The original assertion left the harness's shared
router mutex held, causing subsequent setup timeouts; that run was stopped and
the suite rerun in a fresh process.

Final fast ECI regression: **34 passed, zero failed, one existing ignored test**.
The current routed design, existing addresses and scheduler are retained for the
requested hardware run; the address/scheduling pivot is not implemented here.

UL cleanup also removes the top-level VHDL signals and ILA probes. The generic
DCS block's legacy local-response input pins are tied inactive at its boundary.
Current completion trace names use `CtrlInvalidated` and `DataLciaDone`; latency
analysis accepts their historical names when reading older trace maps.

After removing the remaining UL ports/routing/ILA wiring, the full fast ECI
suite again passed **34 tests, zero failures, one existing ignored test**. The
Nix bundle also checks generated NIC/trace-DMA ports against VHDL and the ILA
probe declarations against connections before handing inputs to Vivado.
