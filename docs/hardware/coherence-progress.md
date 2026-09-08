# ECI 2F2F DCU Progress Model

Status (2026-09-08): unresolved progress analysis. The maintainer confirms that QP ownership and DCU-color placement remain open. Current offsets are source-verified; candidate layouts and maximum-set calculations below are preserved design analysis, not installed constants or newly reproduced proofs. Trace observations are historical evidence, not current test results. See [QP design alternatives](queue-pair-design.md) and [implementation status](implementation-status.md).

This note describes the cache-line coloring requirement needed to make 2F2F
RX/TX progress. It intentionally does not assume a particular thread-to-worker
mapping design, but it does distinguish QPs whose RX and TX paths can issue
2F2F transactions concurrently from QPs whose software path serializes RX and
TX. The same model applies whether QPs are owned by logical threads, worker
cores, bypass, or another runtime abstraction.

The short version is:

- The bypass RX/TX lock is masking an address placement problem.
- The placement unit for progress analysis is the set of 2F2F directions that
  can be active at the same time.
- A full-duplex or interrupt-driven QP contributes both RX and TX colors to its
  active signature. A single-thread RPC worker contributes only its currently
  executing direction, because userspace calls RX and TX sequentially.
- A set of simultaneously active direction signatures is safe only if active
  control colors are globally unique and do not intersect any active overflow
  color.
- The current RX at `+0x0000` and TX at `+0x8000` layout is locally unsafe for
  a QP whose RX and TX paths can interleave: RX and TX control CLs land on the
  same DCU colors after ECI address scrambling.
- For a single-thread RPC worker, that same RX/TX overlap is not locally active,
  but its long blocking RX still contributes RX control colors to the global
  active set and can conflict with bypass or other active workers.
- Feasible solutions are admissible active sets of QP signatures. How those
  signatures are assigned to threads or workers is a separate
  scheduling/runtime design question.

## Terms

- **CL:** ECI cache line, 128 bytes.
- **Color:** the aliased DCU ID used by the DCS after ECI address scrambling.
  With the current DCS configuration this is `aliased_cli[5:0]`, i.e.
  `{dcu_idx, odd_even_bit}`.
- **Queue pair / QP:** one RX endpoint plus one TX endpoint.
- **QP layout:** the CL offsets used by a QP for RX controls, RX overflow, TX
  controls, and TX overflow.
- **QP signature:** the DCU colors induced by a QP layout at a particular
  physical address.
- **Signature universe:** the set of QP signatures the implementation can
  instantiate. For one fixed layout this is the set of signatures produced by
  all reachable masks. With multiple allowed layouts, it is the union across all
  layouts and reachable masks.
- **Active set:** the QP signatures that can issue 2F2F doorbell transactions
  concurrently.
- **Admissible active set:** an active set whose signatures satisfy the global
  progress invariant. In graph terms, signatures are vertices, compatibility is
  an edge, and admissible active sets are cliques.

If possible, we would like QPs to share the same layout under different offsets.
Terms under this simplified problem:

- **Block layout**: the shared **QP layout** for all QPs.
- **Block stride**: how far apart are blocks placed.  In the current design, this
  is denoted by `ECI_CORE_OFFSET = 0x20000`.

## Progress Requirement

For each QP, name the color groups:

```text
A = RX control CL 0
B = RX control CL 1
C = TX control CL 0
D = TX control CL 1
Erx = RX overflow CL colors
Etx = TX overflow CL colors
```

`A`, `B`, `C`, and `D` are single colors. `Erx` and `Etx` are sets of colors.

The reason to treat both control CLs within one direction symmetrically is
parity. Over time both control CLs can act as doorbells, and the opposite
control CL can be progress-critical while invalidations are pending. A static
proof should therefore treat every active control CL as doorbell-capable and the
other control CL plus every active overflow CL in that direction as
progress-critical.

The active signature depends on the QP execution class:

```text
full-duplex QP:
  controls = {A, B, C, D}
  overflow = Erx union Etx

single-thread half-duplex RPC worker, while blocked in RX:
  controls = {A, B}
  overflow = Erx

single-thread half-duplex RPC worker, while issuing TX:
  controls = {C, D}
  overflow = Etx
```

The full-duplex class covers paths whose RX and TX routines can issue 2F2F
doorbells concurrently, for example bypass RX and TX in interrupt contexts. The
single-thread half-duplex class covers the RPC worker runtime when one userspace
thread calls `core_eci_rx` and `core_eci_tx` sequentially, so the same worker
cannot have an RX doorbell and a TX doorbell outstanding at the same time.

The local rule for one active signature is:

```text
controls has no duplicate colors
controls intersects overflow == empty
```

The global rule for any active set is:

```text
active_controls = union(controls for every active signature)
active_overflow = union(overflow for every active signature)

active_controls has no duplicate colors
active_controls intersects active_overflow == empty
```

Overflow sets from different active signatures may overlap for progress. That
can reduce throughput, but it does not by itself place a stalled doorbell
transaction in front of a progress-critical invalidation.

## Address Scrambling

The color computation must use the ECI alias/scramble function. Choosing raw
physical addresses by unscrambled bit positions is not sufficient.

The relevant implementation references are:

- `deps/blocks/blocks/src/jsteward/blocks/eci/EciCmdDefs.scala`
- `deps/blocks/tester/src/jsteward/blocks/eci/sim/package.scala`
- `vivado/eci/static-shell/eci-toolkit/hdl/eci_cmd_defs.sv`
- `vivado/eci/static-shell/eci-toolkit/hdl/eci_defs.vhd`

The low aliased color bits include higher cache-line-index bits:

```text
aliased_cli[5]   = cli[5] ^ cli[18]
aliased_cli[4:3] = cli[4:3] ^ cli[17:16] ^ cli[6:5]
aliased_cli[2:0] = cli[2:0] ^ cli[15:13] ^ cli[7:5]
```

So both the fixed CL offset inside a QP layout and the physical block/page index
contribute to the resulting color.

## QP Signatures

For a QP placed at physical base `base`:

```text
qp_signature(base, layout) = {
  A = color(base + RX_CTRL0_OFFSET(layout))
  B = color(base + RX_CTRL1_OFFSET(layout))
  C = color(base + TX_CTRL0_OFFSET(layout))
  D = color(base + TX_CTRL1_OFFSET(layout))
  Erx = colors(base + RX_OVERFLOW_OFFSETS(layout))
  Etx = colors(base + TX_OVERFLOW_OFFSETS(layout))
}
```

A signature universe may come from:

- one fixed layout translated by different physical block indices,
- several layouts chosen per QP,
- different QP block strides,
- or any combination of those mechanisms.

The progress problem is then purely combinatorial: construct the signature
universe, expand each QP into the active signatures allowed by its execution
class, then find an admissible active set with size equal to the number of
directions that must be active at the same time.

If an admissible active set of size `K` exists, it is a valid static assignment
for `K` concurrently active direction signatures. If no such set exists, no
scheduler or mapping design can run those signatures concurrently while
preserving this DCU progress invariant.

## Fixed-Block Signatures

One practical signature family uses one contiguous block per QP and keeps all
RX/TX CL offsets inside that block. If every QP uses the same layout and the
block is aligned to a power-of-two stride, the signature often decomposes as:

```text
qp_signature(block_index) = local_signature xor block_mask(block_index)
```

The local signature comes from the RX/TX CL offsets inside the block. The block
mask comes from the scrambled high address bits, as contributed by the block index.

For the current `ECI_CORE_OFFSET = 0x20000`:

```text
ECI_CORE_OFFSET = 1024 CLs = 32 * 4 KiB pages
block_index q   = physical_base / ECI_CORE_OFFSET
block_mask      = (q >> 3) & 0x3f
```

There are 64 **reachable masks** `0..63`. The mask repeats every 512 block indices, with
eight distinct block indices per mask.

This maximum number of reachable masks is not a coincidence. In this fixed-block
model, the mask is the high-address contribution into the low aliased DCU color
bits:

```text
color = local_color ^ block_mask
```

The color is `aliased_cli[5:0]`, so it has six bits and therefore 64 possible
values. A block mask that translates colors in this model is also a six-bit
value. Thus:

```text
max_reachable_masks <= number_of_colors = 64
```

Some block strides expose enough varying block-index bits to reach all 64 masks.
Larger strides can hide some of those bits, reducing the reachable-mask count
below the number of colors.

Changing the block stride can change both layout freedom and reachable masks:

| Block size | CLs | reachable masks | mask period in block indices |
| - | - | - | - |
| `0x8000` | 256 | 64 | 2048 |
| `0x10000` | 512 | 64 | 1024 |
| `0x20000` | 1024 | 64 | 512 |
| `0x40000` | 2048 | 64 | 256 |
| `0x80000` | 4096 | 64 | 128 |
| `0x100000` | 8192 | 64 | 64 |
| `0x200000` | 16384 | 32 | 32 |
| `0x400000` | 32768 | 16 | 16 |

The current `0x20000` already reaches all 64 masks. Increasing the block strides
can make local layout easier, but once the stride reaches `0x200000` the mask
space starts shrinking.

## Current Layout Fails For Full-Duplex QPs

The current layout in `sw/core/eci/core.h` is:

```text
RX control:       +0x0000 and +0x0080      CL 0 and CL 1
RX overflow:      +0x0100 ...              CL 2 ...
TX control:       +0x8000 and +0x8080      CL 256 and CL 257
TX overflow:      +0x8100 ...              CL 258 ...
```

`+0x8000` is eight 4 KiB pages after RX. For an `ECI_CORE_OFFSET`-aligned base,
that offset changes page bit 3 but not the page bits that feed
`aliased_cli[5:0]`. Therefore RX and TX have the same local color signature:

```text
A == C
B == D
RX overflow colors == TX overflow colors
```

Changing only the physical block index gives translated versions of the same
bad full-duplex signature. None are locally valid for a QP whose RX and TX
directions can issue 2F2F transactions concurrently, because `A/B/C/D` are not
pairwise distinct.

For a single-thread RPC worker, this is not by itself a same-worker local
deadlock: one userspace thread calls RX and TX sequentially, so the active
signature is either `{A, B} + Erx` or `{C, D} + Etx`, not both at once. The
current layout can still be globally unsafe when that worker is active together
with bypass or other workers. Blocking RX is especially important because an RPC
worker may hold an RX doorbell read outstanding for a long time, so its RX
control colors remain part of the active set while other QPs try to make
progress.

## Current Layout Half-Duplex Capacity

With the current layout, RX and TX have the same color signature after ECI
address scrambling. For a single-thread half-duplex RPC worker, that means the
active RX and TX direction signatures are identical for coloring purposes:

```text
controls(mask m) = {m, m ^ 1}
overflow(mask m) = {2 ^ m, 3 ^ m, ..., 13 ^ m}
```

For the current `ECI_CORE_OFFSET = 0x20000`, all 64 masks are reachable. An
exact compatibility search over those masks gives a maximum independently
admissible half-duplex set of eight masks:

```text
{0, 14, 16, 30, 32, 46, 48, 62}
```

Expanded signatures:

| mask | control colors | overflow colors |
| - | - | - |
| 0 | `0, 1` | `2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13` |
| 14 | `14, 15` | `2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13` |
| 16 | `16, 17` | `18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29` |
| 30 | `30, 31` | `18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29` |
| 32 | `32, 33` | `34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45` |
| 46 | `46, 47` | `34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45` |
| 48 | `48, 49` | `50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61` |
| 62 | `62, 63` | `50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61` |

Representative block indices for these masks are:

| mask | block index `q` | physical base |
| - | - | - |
| 0 | 0 | `0x0000000` |
| 14 | 112 | `0x0e00000` |
| 16 | 128 | `0x1000000` |
| 30 | 240 | `0x1e00000` |
| 32 | 256 | `0x2000000` |
| 46 | 368 | `0x2e00000` |
| 48 | 384 | `0x3000000` |
| 62 | 496 | `0x3e00000` |

Each mask has eight equivalent block indices because
`mask = (q >> 3) & 0x3f`, but only one QP per mask can be active in this
independently admissible set. If bypass consumes mask 0 as an always-active QP,
the current layout and stride can accommodate at most seven additional
single-thread half-duplex RPC worker QPs in a color-unaware creation-capped
scheme.

This count assumes the worker QPs are truly half-duplex. If bypass RX and TX can
interleave under the current layout, bypass itself remains locally invalid under
the stricter full-duplex rule above; the eight-mask result should then be read
only as the half-duplex worker capacity, not as proof that the current bypass
layout is safe.

## Example Permissive Layout

One constructive fixed-block layout, assuming `ECI_NUM_OVERFLOW_CL = 12`, is:

```text
RX control:   CL 6 and CL 7       byte offsets +0x0300, +0x0380
RX overflow:  CL 8 .. CL 19       byte offsets +0x0400 .. +0x0980
TX control:   CL 38 and CL 39     byte offsets +0x1300, +0x1380
TX overflow:  CL 40 .. CL 51      byte offsets +0x1400 .. +0x1980
```

For block mask 0:

```text
A = RX control CL 6   -> color 6
B = RX control CL 7   -> color 7
C = TX control CL 38  -> color 47
D = TX control CL 39  -> color 46

RX overflow CL 8..19 colors:
  8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19

TX overflow CL 40..51 colors:
  33, 32, 35, 34, 37, 36, 39, 38, 57, 56, 59, 58

E = {
  8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
  32, 33, 34, 35, 36, 37, 38, 39, 56, 57, 58, 59
}
```

Applying a mask XOR-translates every color in the local signature:

```text
signature(mask m) = {
  A ^ m, B ^ m, C ^ m, D ^ m,
  { e ^ m | e in E }
}
```

For this layout, an exact compatibility search over the 64 reachable masks finds
a maximum full-duplex active set of eight masks. One maximum set that includes
mask 0 is:

```text
{0, 2, 4, 6, 24, 26, 28, 30}
```

These masks produce the following control colors:

```text
mask 0:   6, 7, 47, 46
mask 2:   4, 5, 45, 44
mask 4:   2, 3, 43, 42
mask 6:   0, 1, 41, 40
mask 24:  30, 31, 55, 54
mask 26:  28, 29, 53, 52
mask 28:  26, 27, 51, 50
mask 30:  24, 25, 49, 48
```

The 32 resulting control colors are pairwise distinct and have no intersection
with the union of the eight translated overflow sets. Thus these masks form one
admissible active set of eight full-duplex QP signatures for this layout.

The same layout also has a maximum direction-unaware half-duplex capacity of
eight masks. Therefore, under the current `0x20000` stride, this better layout
does not increase the creation-capped half-duplex thread count above the current
layout's eight-mask result. Its benefit is that the same count can be made safe
for full-duplex QPs, including bypass, whereas the current `+0x0000/+0x8000`
layout is locally invalid for full-duplex use.

For `ECI_CORE_OFFSET = 0x20000`, the block mask is derived from the block
index:

```text
mask := (block_index >> 3) & 0x3f
```

So one concrete eight-QP assignment is:

| QP | mask | valid block index example | physical base |
| - | - | - | - |
| QP0 | 0 | 0 | `0x0000000` |
| QP1 | 2 | 16 | `0x0200000` |
| QP2 | 4 | 32 | `0x0400000` |
| QP3 | 6 | 48 | `0x0600000` |
| QP4 | 24 | 192 | `0x1800000` |
| QP5 | 26 | 208 | `0x1a00000` |
| QP6 | 28 | 224 | `0x1c00000` |
| QP7 | 30 | 240 | `0x1e00000` |

These are only representative block indices. In general, any block index that
derives the same mask through this equation gives the same translated signature
for this analysis. The mask is derived from the block index; it is not an
independent property to check against the block index.

Distinct masks are not equivalent to admissibility. Distinct masks only produce
different translated signatures. Two distinct masks are admissible together only
if their translated signatures satisfy the global rule: no duplicate active
control colors and no active-control versus active-overflow intersection. The
compatibility search must therefore check signature intersections, not just mask
inequality.

Expanding every selected QP signature:

| QP | mask | control colors `A,B,C,D` | overflow colors `E`, sorted |
| - | - | - | - |
| QP0 | 0 | `6, 7, 47, 46` | `8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 32, 33, 34, 35, 36, 37, 38, 39, 56, 57, 58, 59` |
| QP1 | 2 | `4, 5, 45, 44` | `8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 32, 33, 34, 35, 36, 37, 38, 39, 56, 57, 58, 59` |
| QP2 | 4 | `2, 3, 43, 42` | `8, 9, 10, 11, 12, 13, 14, 15, 20, 21, 22, 23, 32, 33, 34, 35, 36, 37, 38, 39, 60, 61, 62, 63` |
| QP3 | 6 | `0, 1, 41, 40` | `8, 9, 10, 11, 12, 13, 14, 15, 20, 21, 22, 23, 32, 33, 34, 35, 36, 37, 38, 39, 60, 61, 62, 63` |
| QP4 | 24 | `30, 31, 55, 54` | `8, 9, 10, 11, 16, 17, 18, 19, 20, 21, 22, 23, 32, 33, 34, 35, 56, 57, 58, 59, 60, 61, 62, 63` |
| QP5 | 26 | `28, 29, 53, 52` | `8, 9, 10, 11, 16, 17, 18, 19, 20, 21, 22, 23, 32, 33, 34, 35, 56, 57, 58, 59, 60, 61, 62, 63` |
| QP6 | 28 | `26, 27, 51, 50` | `12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 36, 37, 38, 39, 56, 57, 58, 59, 60, 61, 62, 63` |
| QP7 | 30 | `24, 25, 49, 48` | `12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 36, 37, 38, 39, 56, 57, 58, 59, 60, 61, 62, 63` |

The aggregate check is:

```text
active_controls = {
  0, 1, 2, 3, 4, 5, 6, 7,
  24, 25, 26, 27, 28, 29, 30, 31,
  40, 41, 42, 43, 44, 45, 46, 47,
  48, 49, 50, 51, 52, 53, 54, 55
}

active_overflow = {
  8, 9, 10, 11, 12, 13, 14, 15,
  16, 17, 18, 19, 20, 21, 22, 23,
  32, 33, 34, 35, 36, 37, 38, 39,
  56, 57, 58, 59, 60, 61, 62, 63
}

active_controls intersects active_overflow = empty
```

This consumes 32 distinct control colors and 32 overflow colors. Together they
use all 64 colors, with overflow colors intentionally reused across QPs.

If more simultaneous QPs are required, the right next step is an automated
layout search:

1. Enumerate candidate RX/TX CL offsets inside a chosen block stride.
2. Reject layouts that fail the local QP rule.
3. Compute translated signatures for all reachable block masks.
4. Build a compatibility graph over signatures.
5. Search for an admissible active set with the required active QP count.
6. Emit hardware/software constants for the selected layout and physical
   assignment.

The same search can also allow heterogeneous layouts, where different QPs use
different local offset choices. Heterogeneous layouts enlarge the signature
universe, but the requirement is unchanged: the active signatures must form
an admissible active set.

<details>
<summary>Pseudocode to find a permissive layout</summary>

The following pseudocode treats colors as integers in `0..63`, where
`color(byte_addr)` applies ECI address scrambling and extracts
`aliased_cli[5:0]`.

### Find A Locally Viable Block Layout

Given an upper bound `max_e_size` for the overflow color set `E`, search for one
local QP layout whose control colors are distinct and disjoint from its overflow
colors.

```text
find_local_layout(block_size_cl, num_overflow_cl, max_e_size):
  for rx_ctrl0 in 0 .. block_size_cl - 1:
    rx_ctrl1 = rx_ctrl0 + 1
    rx_ov_start = rx_ctrl0 + 2
    rx_ov_end = rx_ov_start + num_overflow_cl

    if rx_ov_end > block_size_cl:
      continue

    rx_used_cls = range(rx_ctrl0, rx_ov_end)

    for tx_ctrl0 in 0 .. block_size_cl - 1:
      tx_ctrl1 = tx_ctrl0 + 1
      tx_ov_start = tx_ctrl0 + 2
      tx_ov_end = tx_ov_start + num_overflow_cl

      if tx_ov_end > block_size_cl:
        continue

      tx_used_cls = range(tx_ctrl0, tx_ov_end)

      if intersects(rx_used_cls, tx_used_cls):
        continue

      A = local_color(rx_ctrl0)
      B = local_color(rx_ctrl1)
      C = local_color(tx_ctrl0)
      D = local_color(tx_ctrl1)

      controls = {A, B, C, D}
      if size(controls) != 4:
        continue

      E = set()
      for cl in range(rx_ov_start, rx_ov_end):
        E.add(local_color(cl))
      for cl in range(tx_ov_start, tx_ov_end):
        E.add(local_color(cl))

      if size(E) > max_e_size:
        continue

      if intersects(controls, E):
        continue

      return Layout(
        rx_ctrl = [rx_ctrl0, rx_ctrl1],
        rx_overflow = range(rx_ov_start, rx_ov_end),
        tx_ctrl = [tx_ctrl0, tx_ctrl1],
        tx_overflow = range(tx_ov_start, tx_ov_end),
        local_signature = {A, B, C, D, E}
      )

  return none
```

This version assumes each direction is laid out as two adjacent control CLs
followed by a contiguous overflow run. A more general generator can enumerate
non-contiguous overflow CLs or different RX/TX shapes; the local test remains
the same.

### Find Compatible Masks For A Layout

Given a local layout, enumerate all reachable masks and find compatible sets of
masks. For `ECI_CORE_OFFSET = 0x20000`, the reachable mask set is `0..63` and
`mask(block_index) := (block_index >> 3) & 0x3f`.

```text
translated_signature(local_signature, mask):
  return Signature(
    controls = {
      local_signature.A ^ mask,
      local_signature.B ^ mask,
      local_signature.C ^ mask,
      local_signature.D ^ mask
    },
    overflow = { e ^ mask for e in local_signature.E }
  )

signatures_compatible(sig_a, sig_b):
  if intersects(sig_a.controls, sig_b.controls):
    return false

  if intersects(sig_a.controls, sig_b.overflow):
    return false

  if intersects(sig_b.controls, sig_a.overflow):
    return false

  return true

find_compatible_masks(local_signature, required_qps):
  candidates = []

  for mask in reachable_masks():
    sig = translated_signature(local_signature, mask)

    # A reachable mask should still satisfy the local rule.
    if size(sig.controls) != 4:
      continue
    if intersects(sig.controls, sig.overflow):
      continue

    candidates.append((mask, sig))

  graph = CompatibilityGraph()
  for each candidate c:
    graph.add_node(c.mask, c.sig)

  for each pair (a, b) in candidates:
    if signatures_compatible(a.sig, b.sig):
      graph.add_edge(a.mask, b.mask)

  # Any clique of size required_qps is one valid concurrently active mask set.
  return find_clique(graph, required_qps)
```

Once the compatibility search chooses a desired mask, corresponding block
indices can be generated by inverting the mask equation:

```text
block_indices_for_mask(mask):
  # For ECI_CORE_OFFSET = 0x20000:
  # derived mask := (block_index >> 3) & 0x3f
  for k in 0 .. infinity:
    yield (mask << 3) + k * 512
    yield (mask << 3) + 1 + k * 512
    yield (mask << 3) + 2 + k * 512
    ...
    yield (mask << 3) + 7 + k * 512
```

### Check A Concrete Assignment

Given a block layout and a list of concrete block indices, verify the invariant
directly. This is the check to run on generated assignments and in debug tooling.

```text
signature_for_block(layout, block_index):
  base = block_index * ECI_CORE_OFFSET

  A = color(base + layout.rx_ctrl[0] * CL_SIZE)
  B = color(base + layout.rx_ctrl[1] * CL_SIZE)
  C = color(base + layout.tx_ctrl[0] * CL_SIZE)
  D = color(base + layout.tx_ctrl[1] * CL_SIZE)

  E = set()
  for cl in layout.rx_overflow:
    E.add(color(base + cl * CL_SIZE))
  for cl in layout.tx_overflow:
    E.add(color(base + cl * CL_SIZE))

  return Signature(controls = {A, B, C, D}, overflow = E)

check_assignment(layout, block_indices):
  active_controls = set()
  active_overflow = set()

  for block_index in block_indices:
    sig = signature_for_block(layout, block_index)

    # Local rule.
    if size(sig.controls) != 4:
      return false
    if intersects(sig.controls, sig.overflow):
      return false

    # Global rule against already accepted QPs.
    if intersects(sig.controls, active_controls):
      return false
    if intersects(sig.controls, active_overflow):
      return false
    if intersects(sig.overflow, active_controls):
      return false

    active_controls = union(active_controls, sig.controls)
    active_overflow = union(active_overflow, sig.overflow)

  return true
```

</details>

## Why The Deadlock Is Practical

The proposed failure mode needs two properties:

1. A doorbell read can remain outstanding while waiting for 2F2F invalidations.
2. A later transaction or invalidation needed for progress can be stuck behind a
   blocked transaction that shares DCU resources.

The 2F2F protocol has this shape:

- RX doorbell: reading the opposite RX control CL causes the FPGA to free packet
  state, invalidate RX overflow CLs, then invalidate the previous RX control CL.
- TX doorbell: reading the opposite TX control CL causes the FPGA to invalidate
  the TX control CL, then invalidate TX overflow CLs, then submit the packet.
- `EciDecoupledRxTxProtocol` forwards LCIA responses into UL responses and only
  advances once the expected invalidations have completed.

The DCU RTL supports the required blocking behavior:

- `rd_trmgr` allows only one outstanding read transaction.
- `wr_trmgr` allows only one outstanding write transaction.
- `dcu_controller` prioritizes read responses, then write responses, then new
  incoming ECI events.
- If the read manager, write manager, ECI transaction slot, RTG, or output
  response path is unavailable, the controller stalls the current ECI event. The
  RTL comments explicitly call this head-of-line blocking.

The test model encodes the same assumption. `DcsAppMaster` tracks one read and
one write per aliased DCU ID and separately limits slice-level in-flight reads.
The `rx-tx-interleaved` test in `OncRpcSim` says RX and TX control CLs
must not be placed on the same DCU. It remains registered with the `Slow` tag
and a FIXME to rework the address map and re-enable CI coverage; excluding
`Slow` omits it. The separate `rx-hol-blocking-free` test has a TODO-only body.

Under the full-duplex model above, the current layout gives a concrete same-QP
version of this hazard. As shown in
[Current Layout Fails For Full-Duplex QPs](#current-layout-fails-for-full-duplex-qps),
the current RX and TX blocks have the same local signature:

```text
RX control CL 0 color == TX control CL 0 color
RX control CL 1 color == TX control CL 1 color
```

So the bypass RX/TX spinlock is currently preventing two interrupt-context
routines from exercising the locally invalid RX/TX signature at the same time.
The single-thread RPC worker runtime has a different local execution class:
RX and TX are mutually exclusive because one userspace thread calls the two
routines sequentially. For that class, the same-QP cycle below is not active
unless another software path can overlap RX and TX for the same worker QP.

A plausible concrete case without that lock is:

```text
TX current CL is 1.
Host commits TX by reading TX +0x8000, the opposite TX control CL.
  TX +0x8000 is CL 256, color 0, and the TX router waits for txInvDone.

The TX FSM first needs to invalidate the previous TX control CL:
  TX +0x8080 is CL 257, color 1.

At the same time, RX current CL is 0.
Host commits RX by reading RX +0x0080, the opposite RX control CL.
  RX +0x0080 is CL 1, color 1, and the RX router waits for rxInvDone.

The RX FSM eventually needs to invalidate the previous RX control CL:
  RX +0x0000 is CL 0, color 0.
```

If the color-1 DCU is blocked by the outstanding RX doorbell read, the TX
control invalidation to `TX +0x8080` can fail to complete. That prevents
`txInvDone`, so the TX router stays in `waitInv`. Symmetrically, if the color-0
DCU is blocked by the outstanding TX doorbell read, the RX control invalidation
to `RX +0x0000` can fail to complete. The opposite parity case swaps colors 0
and 1. This cycle uses only the current control CL placements; it does not
require a TX overflow CL to have color 0.

The observed timeout pattern is consistent with this TX-side version. The L2C
TAD reported LFB entry timeouts for low address offsets in the TX region:

```text
+0x8000  TX control CL 0   color 0
+0x8100  TX overflow CL 0  color 2
+0x8200  TX overflow CL 2  color 4
+0x8300  TX overflow CL 4  color 6
```

In `DcsTxAxiRouter`, the read FSM accepts a DCS AXI read address only in `idle`.
A read of the opposite TX control CL is treated as the TX doorbell: it records a
host request and enters `waitInv` until `invDone` is observed. While the FSM is
in `waitInv`, it does not accept another `dcsQ.ar` request. A TX overflow refill
read would normally take the overflow path into `readPktBuf`, but it cannot do
that while an earlier TX doorbell read is still waiting for invalidation
completion.

`invDone` is `txInvDone` from `EciDecoupledRxTxProtocol`, and that signal is
asserted only after the TX state machine has invalidated the TX control CL,
invalidated the required TX overflow CLs, observed the corresponding LCIA/UL
completion, and reached the submit state. Therefore, if the TX invalidation
sequence is stuck for any reason, the TX router can stop servicing later refill
reads to TX overflow CLs. Under this interpretation, the observed `+0x8100`,
`+0x8200`, and `+0x8300` timeouts are consistent downstream symptoms of a stuck
TX doorbell transaction.

Older notes under `data/eci/sys_trace/remote_fsm_trace_notes.md` reached a
similar partial conclusion for previous TX timeout traces: visible coherence
behavior can look valid until a final invalidation receives no visible ack, and
the L2C timeout suggests a ThunderX-side in-flight coherence entry stopped
making progress.

## Questions For A Full Trace

A full trace should answer these before removing `dp_lock`:

- Which exact CL read was outstanding at the first L2C-TAD timeout?
- Was `DcsTxAxiRouter.readFsm` in `waitInv`, and if so which TX control CL was
  stored in `readCmd`?
- Were later TX overflow refill reads queued in `dcsQ` or upstream while the TX
  router was waiting for `txInvDone`?
- Which 2F2F state machines were waiting for LCIA or UL at that point?
- For every pending control and overflow CL, what were the aliased DCU ID and
  DCU index?
- For each such CL, what was the raw physical address before scrambling, and
  what was the exact scrambled/aliased address observed by the DCS?
- Did the missing progress-critical event target a DCU whose read manager was
  already occupied by a stalled doorbell read?
- Did any later ECI event for the same DCU stall in `dcu_controller` because of
  `tr_rd_req_ready_i`, `tr_wr_req_ready_i`, `tr_eci_full_i`, or
  `eci_rsp_ready_i`?
- Were AXI AR/AW queues in `dcs_2_axi`, `dcs_cdc`, or the Lauberhorn AXI
  routers holding an older transaction that prevented the required invalidation
  from reaching the DCU?
- Do the final unacked `SINV` or `SINV_H` messages, if any, correspond to the
  same DCU color as a blocked doorbell CL?

Until those are answered, the bypass spinlock should be treated as a workaround
for an unresolved DCU progress invariant, not as merely a software shared-state
lock.
