# ECI 2F2F DCU Progress Notes

This note records the current hypothesis behind the bypass RX/TX spinlock in
`sw/kmod/bypass.c`, and what would be needed to remove it safely.

The short version is:

- The bypass lock is not just protecting shared software state. It is also
  masking an address placement problem.
- The current address map does not intentionally color 2F2F control and
  overflow cache lines across DCUs.
- The DCU RTL does support a practical head-of-line blocking path: a DCU has
  one read transaction manager and one write transaction manager, and the
  controller stalls incoming ECI events if the required manager or output path
  is busy.
- The current bypass and RPC address assignment gives each 2F2F flow a
  contiguous `ECI_CORE_OFFSET` region. That maps threads to 2F2F protocol
  state machines, but it does not guarantee forward-progress isolation at the
  DCU level.

## Current Software Behavior

Bypass has one kernel `lauberhorn_core_state_t`, with RX parity and TX parity
stored in `struct netdev_priv`. RX and TX can be entered from different kernel
contexts:

- TX: `netdev_xmit()` calls `core_eci_tx()`.
- RX: NAPI polling calls `core_eci_rx()`.

`sw/kmod/bypass.c` wraps both calls with `dp_lock`. The local comment already
states the suspected root cause: RX and TX CLs are not really independent, and
overflow CLs mapped to the same DCU as control CLs can deadlock and crash the
system.

RPC workers are different locally but not globally. A single worker thread runs:

1. `core_eci_rx()`
2. user handler
3. `core_eci_tx()`

so one thread does not intentionally overlap its own RX and TX routine. However,
multiple RPC workers and multiple user processes can run concurrently. Global
mutual exclusion across those processes would be difficult and would defeat the
intended parallelism.

## Current Address Assignment

The shared ECI layout is in `sw/core/eci/core.h` and is generated from the
hardware constants:

```text
RX control:       +0x0000 and +0x0080
RX overflow:      +0x0100 ...
TX control:       +0x8000 and +0x8080
TX overflow:      +0x8100 ...
per-flow stride:  ECI_CORE_OFFSET = 0x20000
```

Bypass uses prefix 0 and routes it to core slot 0:

```text
route_prefix_to_core(0, 0)
base = mem_node1_off_to_virt(0)
```

RPC datapath VMAs are assigned in `sw/kmod/chrdev.c`:

```text
thr->prefix = 1 + proc->idx * LAUBERHORN_NUM_WORKER_CORES + thr_idx
physical base = prefix * LAUBERHORN_ECI_CORE_OFFSET
```

When a worker is scheduled, `sw/kmod/sched.c` routes that prefix to one worker
core slot. `EciThreadClRouter` only rewrites at `ECI_CORE_OFFSET` granularity:
it matches `addr >> log2(ECI_CORE_OFFSET)` and preserves the low offset bits.
This is enough to select the correct 2F2F state machine, but it does not assign
RX/TX control and overflow CLs to safe DCU colors.

## DCU Address Coloring And Scrambling

With the current DC configuration (`DS_NUM_SETS_PER_DCU = 128`), the DCU ID is
in aliased byte-address bits `[12:7]`. The DCU index is `DCU_ID[5:1]`; the low
bit is the odd/even offset.

The important detail is that the DCU selection is based on the ECI-scrambled
address, not the raw physical address. In the code this is usually called
`aliasAddress()` / `unaliasAddress()`, but it is an address scrambling transform:
several low cache-line-index fields are XORed with higher address bits before
the DCS sees the address. A color allocator must therefore choose unaliased
physical addresses by running them through the ECI alias/scramble function and
checking the resulting DCU ID/index.

The relevant implementation references are:

- Scala/Spinal: `deps/blocks/blocks/src/jsteward/blocks/eci/EciCmdDefs.scala`
- Test model: `deps/blocks/tester/src/jsteward/blocks/eci/sim/package.scala`
- SystemVerilog/VHDL copies:
  `vivado/eci/static-shell/eci-toolkit/hdl/eci_cmd_defs.sv` and
  `vivado/eci/static-shell/eci-toolkit/hdl/eci_defs.vhd`

For prefix 0, the current layout starts like this:

| CL | unaliased offset | aliased offset | DCU_ID | DCU_IDX | O/E |
| - | - | - | - | - | - |
| RX ctrl 0 | `0x00000` | `0x00000` | 0 | 0 | 0 |
| RX ctrl 1 | `0x00080` | `0x00080` | 1 | 0 | 1 |
| RX overflow 0 | `0x00100` | `0x00100` | 2 | 1 | 0 |
| RX overflow 1 | `0x00180` | `0x00180` | 3 | 1 | 1 |
| TX ctrl 0 | `0x08000` | `0x08000` | 0 | 0 | 0 |
| TX ctrl 1 | `0x08080` | `0x08080` | 1 | 0 | 1 |
| TX overflow 0 | `0x08100` | `0x08100` | 2 | 1 | 0 |
| TX overflow 1 | `0x08180` | `0x08180` | 3 | 1 | 1 |

So RX control and TX control collide on the same global DCU colors per parity
(`DCU_ID` 0 and 1), and the first RX/TX overflow CLs collide on `DCU_ID` 2 and
3. The per-flow stride does not automatically fix this. Prefixes 0 through 7
keep the same low DCU index assignment; prefixes 8 through 15 flip odd/even but
still keep the same DCU indexes. Prefix 16 starts moving the control pair to
`DCU_IDX 1`, which then collides with the default overflow color.

This means the current prefix allocation is not a color allocation.

## Physical Allocation Design

The allocator should work in terms of colored 4 KiB pages, not contiguous
`base + offset` regions. The reason is practical: CPU mappings are page based,
but the 2F2F hazard is cache-line based. If control and overflow remain in the
same 4 KiB page, their colors are tied together by the address scrambling
sequence inside that page. That makes it hard to allocate enough independent
active RX/TX endpoints.

Change the logical layout first:

```text
RX control page:    logical +0x0000, uses CL 0 and CL 1
RX overflow page:   logical +0x1000, uses CL 0..N-1
TX control page:    logical +0x2000, uses CL 0 and CL 1
TX overflow page:   logical +0x3000, uses CL 0..N-1
preempt page:       logical +0x4000, non-bypass only
logical flow size:  at least 0x5000, rounded up for router decode
```

That implies relaxing the current `ECI_OVERFLOW_OFFSET == 2 * CL_SIZE`
assumption and teaching the hardware/software constants that overflow starts on
a page boundary. `core_eci_rx()` and `core_eci_tx()` can still do contiguous
`memcpy()` on the overflow area; the virtual mapping makes the logical overflow
page contiguous even if the physical page is not adjacent to the control page.

### Color Units

Use a global resource color:

```text
global_dcu_color = aliased_dcu_id = {dcu_idx, odd_even_bit}
```

This is equivalent to `(DCS slice, DCU_IDX)`. If a future trace shows that the
two odd/even slices share a lower-level blocking resource, the allocator can be
made more conservative by dropping `odd_even_bit` and coloring only by
`DCU_IDX`; that halves the usable color space. The current RTL/test model
tracks one read and one write per aliased DCU ID, so the first implementation
should use the full aliased DCU ID.

### Pools

For the current 64 global DCU colors, use two pools:

```text
control colors:  0..31
overflow colors: 32..63
```

Control pages consume two control colors, one per parity CL. Overflow pages
consume the colors used by their first `ECI_NUM_OVERFLOW_CL` cache lines.
Overflow pages may share colors with other overflow pages; they must not share
colors with any active control page. Control pages must not share control colors
with any other active control page.

For the common simulation/build shape with bypass plus four worker cores, there
are ten active 2F2F endpoints:

```text
bypass RX, bypass TX,
worker0 RX, worker0 TX,
worker1 RX, worker1 TX,
worker2 RX, worker2 TX,
worker3 RX, worker3 TX
```

Those need 20 control colors, which fits in the 0..31 control pool. Larger
builds should compute this at generation time:

```text
required_control_colors = 2 * 2 * NUM_CORES
                       = 4 * NUM_CORES
```

If `required_control_colors` exceeds the chosen control-color pool, either
reduce the number of concurrently active worker cores, shrink the overflow pool,
or use a color-aware scheduler that admits only a safe subset of threads at a
time.

### Example For Bypass Plus Four Workers

The following table is an example allocation. Physical page offsets are relative
to a reserved ECI-colored physical arena and are chosen by applying the ECI
scrambling function, not by inspecting raw address bits.

| Endpoint | control colors | control phys page | overflow colors | overflow phys page |
| - | - | - | - | - |
| bypass RX | 0, 1 | `+0x000000` | 32..43 | `+0x901000` |
| bypass TX | 2, 3 | `+0x200000` | 48..59 | `+0xb03000` |
| worker0 RX | 4, 5 | `+0x004000` | 32..43 | `+0x909000` |
| worker0 TX | 6, 7 | `+0x204000` | 48..59 | `+0xb0b000` |
| worker1 RX | 8, 9 | `+0x800000` | 32..43 | `+0x911000` |
| worker1 TX | 10, 11 | `+0xa00000` | 48..59 | `+0xb13000` |
| worker2 RX | 12, 13 | `+0x804000` | 32..43 | `+0x919000` |
| worker2 TX | 14, 15 | `+0xa04000` | 48..59 | `+0xb1b000` |
| worker3 RX | 16, 17 | `+0x202000` | 32..43 | `+0x921000` |
| worker3 TX | 18, 19 | `+0x002000` | 48..59 | `+0xb23000` |

The example intentionally reuses two overflow color patterns, but every endpoint
gets a distinct physical overflow page. Reusing overflow colors is acceptable
for the deadlock argument because overflow-overflow sharing does not put a
stalled doorbell read in front of a progress-critical invalidation. It may still
cost throughput, so a performance-oriented allocator can stripe overflow pages
across more high-color patterns.

Do not hard-code these offsets as the design. The allocator should scan the
reserved physical arena for pages whose first two cache lines have the requested
control colors, and pages whose first `ECI_NUM_OVERFLOW_CL` lines are all in
the overflow-color pool.

### Mapping Model

The CPU should see one contiguous logical 2F2F region per bypass/worker context.
The physical mapping behind it should be discontiguous:

```text
logical +0x0000 -> selected RX control physical page
logical +0x1000 -> selected RX overflow physical page
logical +0x2000 -> selected TX control physical page
logical +0x3000 -> selected TX overflow physical page
logical +0x4000 -> selected preempt physical page, for workers
```

For userspace workers, `chrdev.c` should map each logical page separately
instead of using one `remap_pfn_range()` over a contiguous PFN range. For bypass,
the kernel should use an equivalent virtually contiguous mapping, for example a
small `vmap()`/remap-backed region, instead of assuming that
`mem_node1_off_to_virt(0) + offset` is the physical layout.

The thread router must also become page/subregion aware. Today it matches and
rewrites one prefix for the whole flow. With colored pages, it needs entries
like:

```text
thread physical RX control page    <-> core logical RX control page
thread physical RX overflow page   <-> core logical RX overflow page
thread physical TX control page    <-> core logical TX control page
thread physical TX overflow page   <-> core logical TX overflow page
thread physical preempt page       <-> core logical preempt page
```

Incoming DCS AXI requests should be matched against the active thread's physical
colored pages and translated to the selected core slot's logical pages. Outgoing
LCI/LCIA/UL traffic from the 2F2F protocol should be translated back from core
logical pages to the active thread's physical colored pages.

### Thread Allocation And Scheduling

There are two viable policies for RPC threads:

1. **Static safe allocation:** give every registered RPC worker thread a unique
   control-color group. This is simple but can only support
   `control_pool_size / 4` threads if every thread has both RX and TX endpoints.
   With a 32-color control pool, that is eight threads.
2. **Color-aware active allocation:** give each thread a color group from a
   finite set and make the scheduler admit only non-conflicting groups
   concurrently. This matches the actual hardware limit better: only
   `NUM_WORKER_CORES` RPC threads plus bypass are active at once.

The second policy is likely the practical one. Store each thread's RX/TX color
group in `struct thr_def`. When scheduling a thread, check that its control
colors do not collide with any active thread and that none of its control colors
are in the active overflow pool. If there is no safe color group, leave the
thread unscheduled until a worker core frees a compatible group.

Bypass should reserve its RX and TX control colors permanently because it can
run concurrently with all RPC workers and is not managed by the RPC scheduler.

### Remaining Non-2F2F Memory Risk

This allocation isolates the 2F2F control and overflow pages. It does not, by
itself, prove progress against unrelated memory accesses that happen to use the
same DCU colors. A full guarantee would also require page coloring for memory
that isolated worker cores and the bypass path touch while a doorbell read is
outstanding, or a proof that those accesses cannot sit in front of
progress-critical invalidations.

For a first implementation, the 2F2F page coloring is still the right boundary:
it removes the known RX/TX and worker/worker self-inflicted collisions and gives
traces a much cleaner shape. If crashes remain, the next suspect is unrelated
Linux/SKB/user memory traffic sharing a control color.

## Why The Deadlock Is Practical

The proposed failure mode needs two properties:

1. A doorbell read can remain outstanding while waiting for 2F2F invalidations.
2. A later transaction or invalidation that is needed for progress can be stuck
   behind the blocked transaction or behind a transaction that shares the same
   DCU resources.

The Lauberhorn 2F2F logic has this shape:

- RX doorbell: reading the opposite RX control CL causes the FPGA to free packet
  state, invalidate RX overflow CLs, then invalidate the previous RX control CL.
- TX doorbell: reading the opposite TX control CL causes the FPGA to invalidate
  the TX control CL, then invalidate TX overflow CLs, then submit the packet.
- `EciDecoupledRxTxProtocol` forwards LCIA responses into UL responses and only
  advances once the expected invalidations have completed.

The DCU RTL supports the required blocking behavior:

- `rd_trmgr` allows only one outstanding read transaction.
- `wr_trmgr` allows only one outstanding write transaction.
- `dcu_controller` prioritizes read responses, then write responses, then a new
  incoming ECI event.
- If the read manager, write manager, ECI transaction slot, RTG, or output
  response path is unavailable, the controller stalls the current ECI event. The
  RTL comments explicitly call this head-of-line blocking.

The test model also encodes this assumption. `DcsAppMaster` allows one read and
one write per DCU and separately limits slice-level in-flight reads. The disabled
`rx-tx-interleaved` test in `OncRpcSim` lists the same requirement: RX and TX
control CLs must not be placed on the same DCU, and the downstream read path
must allow enough in-flight requests.

This does not prove that every observed crash is this deadlock, but it makes the
scenario architecturally plausible.

## Crash Evidence

The screenshot in `~/Downloads/image(1).png` shows multiple ThunderX L2C-TAD
LFB entry timeouts followed by an SError in `netdev_xmit`. The low address-like
fields in the timeout lines include offsets around `0x8000`, `0x8100`,
`0x8200`, and `0x8300`, which match the current TX control and TX overflow
region. That is consistent with a stuck TX-side 2F2F transaction, but without a
full trace it is not proof of the dependency cycle.

Older notes under `data/eci/sys_trace/remote_fsm_trace_notes.md` reached a
similar partial conclusion for previous TX timeout traces: visible coherence
behavior can look valid until a final invalidation receives no visible ack, and
the L2C timeout suggests a ThunderX-side in-flight coherence entry stopped
making progress.

## Proposed Direction

The forward-progress requirement should be expressed as an address coloring
constraint, not a software spinlock.

A conservative version is:

- Treat every 2F2F control CL as a doorbell-capable CL.
- Treat every opposite control CL and overflow CL invalidated by the protocol as
  progress-critical.
- Do not map any doorbell-capable control CL to a DCU that may also need to
  process progress-critical invalidations for any concurrently active 2F2F flow.
- Do not rely on process-local locks. The color allocator must be global across
  bypass and all RPC worker prefixes.

In implementation terms, this likely requires:

1. Define a finite set of DCU color classes based on the scrambled/aliased DCU
   index.
2. Replace the fixed contiguous sub-layout with per-subregion colored physical
   offsets for RX control, RX overflow, TX control, TX overflow, and preemption
   control.
3. Extend the thread router from simple prefix replacement to subregion-aware
   translation, including reverse translation for LCI/LCIA/UL paths.
4. Allocate prefixes or physical pages from a global color allocator. If there
   are not enough safe colors for all active flows, fall back to scheduling or
   admission control for flows that would share a dangerous color set.

The color computation must use the ECI address scrambling function. Choosing
addresses by unaliased bit positions alone is not sufficient, and a simple
`base + stride` scheme can accidentally preserve the same DCU colors for many
successive prefixes.

## Questions For A Full Trace

A full trace should answer these before removing `dp_lock`:

- Which exact CL read was outstanding at the first L2C-TAD timeout?
- Which 2F2F state machines were waiting for LCIA or UL at that point?
- For every pending control and overflow CL, what were the aliased DCU_ID and
  DCU_IDX?
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
