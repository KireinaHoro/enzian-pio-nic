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

So RX control and TX control collide on `DCU_IDX 0`, and the first RX/TX
overflow CLs collide on `DCU_IDX 1`. The per-flow stride does not automatically
fix this. Prefixes 0 through 7 keep the same low DCU index assignment; prefixes
8 through 15 flip odd/even but still keep the same DCU indexes. Prefix 16 starts
moving the control pair to `DCU_IDX 1`, which then collides with the default
overflow color.

This means the current prefix allocation is not a color allocation.

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
