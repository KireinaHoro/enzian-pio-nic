# Hot-path hardware/software cache-line interface

This document describes the implemented ECI payload-layout version 3 shared by
the FPGA, generated descriptors and C runtime. It covers RX/TX data placement,
coherence handoff and buffer ownership. The [queue-pair design](../hardware/queue-pair-design.md)
defines worker/domain address encoding and the HoL constraints on these offsets.
The [scheduling interface](../software/scheduling-control-interface.md) defines
activation and relinquishment. Match generated headers, runtime and bitstream
when changing any of these contracts.

## Address and cache-line layout

Each worker/domain alias occupies an 8 KiB block. The table gives byte offsets
from its unaliased, slice-relative base; the kernel mapping supplies the CPU
aperture. An ECI coherence line is 128 bytes, transferred as two 64-byte AXI beats.

| Region | Offsets | Role |
| --- | --- | --- |
| RX control parity 0 / 1 | `0x300` / `0x380` | Alternate request/response control lines |
| RX overflow | `0x400..0x9ff` | Twelve 128-byte lines; contiguous payload starts at `0x400` |
| TX control parity 0 / 1 | `0x1300` / `0x1380` | Descriptor/commit and returned status |
| TX overflow | `0x1400..0x19ff` | Twelve 128-byte lines; contiguous payload starts at `0x1400` |

Within either control line:

| Bytes | Contents |
| --- | --- |
| `0..63` | Descriptor/status half; valid fields depend on the request type |
| `64..127` | Complete inline payload when inline placement is selected |

Byte 56 is reserved in the current transport layout. It is not an application
payload-length mailbox. There is no separate payload length written there.
Offsets outside the transport regions are not extra payload capacity.

The authoritative arithmetic is generated from
[`selected-8k.json`](../../model/data/selected-8k.json) into
[`EciAddressMap.scala`](../../hw/src/lauberhorn/host/eci/EciAddressMap.scala) and
matching C helpers. Protocol-specific descriptor fields are generated from
[`EciHostRxCtrlInfo`](../../hw/src/lauberhorn/host/eci/EciHostRxCtrlInfo.scala) and
[`EciHostTxCtrlInfo`](../../hw/src/lauberhorn/host/eci/EciHostTxCtrlInfo.scala).
Use their Mackerel accessors rather than independent handwritten bit layouts.
RX includes valid/type/length and request metadata; invalid RX status distinguishes
NACK from DESCHED. TX includes type/length, protocol metadata and an explicit
`inline_payload` placement bit at bit 23. Numeric host fields and wire bytes
follow the [endianness contract](../software/nested-rpc.md#endianness-at-the-host-boundary).

## RX: contiguous payload and replay

Placement depends on the complete transport payload length:

- At most 64 bytes: the entire payload occupies the current control line's upper
  half. There is no split prefix in overflow.
- More than 64 bytes: the entire payload starts at overflow byte zero, relative
  to the overflow region. This includes reconstructed RPC prefixes and bypass
  headers that are part of the transport payload.

An opposite-parity control read drives retirement of the previously delivered
packet: overflow/control invalidations must complete before publication proceeds.
Same-parity rereads replay the current descriptor. The runtime exposes the
payload as a borrowed view; release ends the software borrow, while the next
acquisition retires the underlying hardware slot. Do not retain that pointer
across the next receive, yield or reassignment. Copy data needed by deferred work;
see [runtime ownership](../software/runtime.md#request-and-transport-ownership).

RX writebacks complete independently of the blocked read FSM, so LCIA service
cannot depend on that read finishing first. A private per-packet replay shadow
retains byte-masked parser mutations; it does not modify the backing packet
allocation. Shadow state is cleared for a new payload and during retirement/
descheduling. See [independent writeback service](#independent-rx-writeback-service) below and
the [proposed response path](../research/hot-path-performance.md#hardware-reuse-the-rx-return-half-for-responses).

### Independent RX writeback service

The RX router accepts a 128-byte write as two 512-bit beats, checks WLAST,
and returns OKAY with the accepted AW ID only after both beats. It retains B
under backpressure and includes queued/in-flight writes and held B responses in
quiescence accounting. This service cannot depend on the read FSM: the next
control read may itself be waiting for LCIA, which DCS issues only after this
writeback completes. The [permission-change contract](../hardware/eci-implementation.md#rx-writeback-and-permission-changes)
explains why an exclusive load can return data even without a CPU store.

The replay shadow honors byte strobes and preserves parser mutations across
conflict eviction/reload. Descriptor writes are ignored. Retirement clears shadow
validity after old-line invalidation; DESCHED also clears it. The shadow is not
a response channel or permission to transmit returned RX bytes.

RX retains the actual DMA-written body length and constructs the logical host
payload from that body plus any metadata prefix. Reads mask the final partial
payload beat and return zeros beyond the logical payload length, including after
replay overlay. Single-beat backing-store reads avoid crossing an allocation
boundary. Stale padding in packet RAM must never become successor-visible data.

## TX: reservation fixes placement

[`core_eci_tx_get_buffer`](../../sw/core/eci/core.h) reserves either the 64-byte
inline area or the overflow area with transport-MTU capacity. Commit checks that
the actual send length fits the reservation and writes its placement bit.

| Reservation | Actual length | Payload source |
| --- | --- | --- |
| Inline | `0..64` | Current control line, bytes `64..127` |
| Overflow | Any supported length up to reserved capacity | Overflow byte zero, even when the send is 64 bytes or shorter |

A short send from a large reservation stays in overflow. Software does not copy
it into the control line or infer placement from its final length. Conversely,
an inline descriptor with a length greater than 64 is invalid.

Commit reads the opposite TX control line. Hardware invalidates the current
control line and collects the overflow lines required by the actual payload
length. Inline and execution-control transactions need no payload-overflow
invalidation; an overflow send uses `ceil(length / 128)` lines. Full cleanup at
ownership change remains separate from this length-based normal commit.

The router submits the descriptor only after collection and any inline
restoration finish, then waits for the local TX result before returning status.
The runtime consumes the reservation on accepted, retry and error results;
transport pointers do not survive a subsequent commit or assignment change.
TX completion reflects the local DMA/output boundary, not remote receipt.

## Inline restoration and late-write protection

The inline upper half of a control line and overflow byte zero have distinct
coherent addresses but alias the first 64 bytes of the FPGA's TX packet slot.
An overflow writeback can therefore overwrite bytes previously delivered by a
control-line writeback. This is relevant even when the current reservation is
inline: unused overflow lines may still be dirty from earlier use.

The [TX router](../../hw/src/lauberhorn/host/eci/DcsTxAxiRouter.scala) handles this
aliasing as follows:

1. Capture the descriptor and inline bytes in separate registers, honoring byte
   strobes. The saved inline copy survives packet-slot writes and host rereads.
2. After control invalidation completes for an inline transaction, mark inline
   data committed and schedule restoration of the saved 64 bytes into the slot.
3. Finish the restoration's AXI B response before making the TX descriptor valid.
4. While inline data is committed, accept/drain overflow writebacks but suppress
   their packet-store byte strobes. They must not corrupt data while TX uses it.
5. Clear that protection when the local TX result arrives. Later same-domain
   writebacks are allowed; owner changes require full invalidation/drain/scrub.

For overflow placement, the control line's payload half does not overwrite the
packet slot. Restoration is for inline reservations; it is not repacking a short
overflow send. The [TX safety model](../formal/tx-payload-layout.md) checks a small
abstraction of these safeguards, not all RTL timing or the RX protocol.

## Ownership and optimization boundaries

Fresh accesses must match the installed domain/worker ownership. Accepted reads,
writebacks and invalidations retain their original full addresses through
completion. Before another owner can use the slot, cleanup drains old obligations
and scrubs payload state; retaining unused overflow lines after an ordinary TX
is not permission to expose them to a successor.

### Payload cleanup across owners

Address authorization and invalidating CPU cache lines do not erase the FPGA's
shared backing storage. TX scrubs the entire physical worker allocation after
the drained DESCHED event, including initial activation. The configured rounded
MTU is 1536 bytes. Successor TX reads/writes wait for every scrub B response;
ownership installation and RX service may proceed while this internal scrub
runs. The next preemption also waits for scrub completion. Old-owner transport
must already be drained, as checked by the router's quiescence assertion.
Scrub progress assumes eventual packet-buffer AXI write responses; it does not
wait for successor host traffic.

Clearing storage protects host reads, partial writes and DMA transmission of
bytes the successor never wrote. Inline and overflow addresses alias the same
allocation; their capacities are not additive. Packet-store writes outside the
allocation are redirected within the same slot with zero strobes; out-of-range
reads return zero. DCS still receives the original two-beat read or single write
response with its retained ID. These bounds also prevent access to a neighboring
allocation.

[Isolation regressions](../development/validation.md#payload-isolation-across-cooperative-ownership-changes)
cover A→B→A reuse, aliases, partial strobes, delayed scrub completion, unwritten
TX bytes and poisoned RX tails. The [domain-isolation model](../formal/domain-isolation.md)
separately checks admission and provenance under its bounded assumptions.

### Optimization boundaries

The [hot-path performance analysis](../research/hot-path-performance.md) uses
this interface when comparing instruction counts, copies, cache-line accesses
and coherence traffic. A proposed optimization must preserve reservation-fixed
placement, both parities, mutable RX replay, byte strobes, length boundaries,
writeback ordering and owner isolation. [Formal verification](../formal/README.md)
and [validation](../development/validation.md) describe complementary checks;
no instruction-count reduction follows merely from this layout description.
