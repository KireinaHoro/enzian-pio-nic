# Contiguous TX payload layout, October 10

`TxPayloadLayout` checks the version-3 TX alias contract independently of the
older resource-progress models. RX chooses inline storage for length <= 64;
otherwise its contiguous payload begins at direction offset 0x100. TX uses the
reservation's inline bit, not final length, to choose the same physical regions.
The model represents four 64-byte beats and two 128-byte overflow lines. Capacity
ranges from zero to four beats and length from zero to capacity, including short
sends from an overflow reservation and cancellation. It does not change the
selected physical-worker address map or the older conservative dependency models.

Completed overflow writebacks can occur before the doorbell, during invalidation,
and after inline restoration. Control payload is saved independently of the
aliased packet slot. The inline guard ends at TX completion; later same-domain
writebacks are allowed. Only required overflow lines must drain for normal TX.
Full drain and scrub precede another domain's access.

The positive case passed: 1,506 generated / 837 distinct states, depth 9. Three
fault controls produced the intended counterexamples:

- Without restoration, a control writeback followed by overflow writeback leaves
  the aliased first beat wrong when the packet becomes available for transmission.
- Without late-write masking, an unused overflow line corrupts an inline packet
  after restoration, while transmission is still active.
- Without full preemption cleanup, the next owner sees previous transport data
  or retained dirty lines.

These findings support the safeguards in transport commit `a72ec4f9`; they do not
establish RTL refinement. Required-line invalidation assumes completed AXI writes
before LCIA completion. Preemption drain/scrub is atomic here; its implementation
ordering remains covered by the existing isolation/progress checks. This model
checks one transaction and one ownership transition, not repeated transactions,
CPU speculation, RX borrow lifetimes, DMA beat timing, or liveness. RX placement
is documented above but is not independently verified by this TX model. The
existing simulations cover concrete packet lengths and router write ordering.

Reproduce with the pinned toolchain:

```sh
nix develop -c python3 tools/scheduling/check_models.py \
  --output out/tx-layout-check \
  --case tx-layout --case tx-layout-no-restore \
  --case tx-layout-late-write --case tx-layout-no-cleanup
```

Captured configurations, source/tool hashes, logs and fault traces are under
`out/contiguous-transport/tx-layout-model-final/`; `summary.json` records all four
outcomes. These are bounded safety checks, not a full system progress claim.

The module inventory/import checks and SANY parsing also passed through
`nix develop -c python3 tools/model/check_modules.py --output NEW_DIR`;
recorded output is `out/contiguous-transport/tx-layout-modules/summary.json`.
