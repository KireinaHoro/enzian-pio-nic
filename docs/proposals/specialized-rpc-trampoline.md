# Specialized RPC entry trampoline

Status: sketch; not implemented. No RX ABI or hardware changes are authorized by
this note.

The RX trampoline specializes the complete handling of a request: inline the
exact request-unmarshal, user-handler and response-marshal routines for each
registered RPC procedure, followed by reply submission. It is not merely a
specialized entry into the generic runtime's decode/handler/outcome/encode path.

Change the RX cache-line layout so every completed blocking RX supplies an
executable continuation address. Once RX returns, the runtime unconditionally
jumps indirectly to that NIC-provided address, without a software message-type
switch, service/codec lookup or control-reason check before the jump. The NIC
selects both procedure entries and runtime control entries:

| RX result | Selected continuation |
| --- | --- |
| RPC request | Procedure-specific inlined unmarshal → handler → marshal → reply submission |
| Nested RPC reply | Completion entry interpreting cookie, status and response payload |
| Kernel bypass packet | Packet entry interpreting length and packet bytes |
| Kernel neighbor/ARP work | Neighbor entry interpreting requested IP address and associated metadata |
| NACK | Host-work check, then return to RX |
| DESCHED | Enter `lh_yield` directly |
| DEADLINE | Check timers and invoke due timer callbacks |

Control entries perform their own required work and resume the receive loop
under the appropriate ownership/activation state. DEADLINE selects timer
processing; software still checks which timers are due and calls their
callbacks. NACK enters the host-work check directly; its relationship to
[HOST_WORK notification and liveness](host-work.md) must preserve that proposal's
progress requirements. These entries and the executable-address RX ABI are
proposed behavior, not existing transport support.

The single indirect jump is the common dispatch mechanism for all these RX
results. Checks inside an entry, timer callback invocation, and branches in
codec or handler bodies remain where their semantics require them. The
specialized request entry binds the complete request/reply path; it must not
reintroduce generic codec or handler dispatch after entry.

Move supported RPC-envelope validation into hardware before delivering the
trampoline address. The present call decoder selects the service in hardware,
but CALL/type and RPC-version checks are assertions rather than packet rejection;
credential/verifier validation and explicit malformed-length rejection need
implementation. Error and unsupported-message paths remain to be specified.

The intended comparison is necessary RPC work versus avoidable software
indirection and dispatch. The proposed LH path would eliminate the latter;
the current DPDK and Caladan baselines would retain it. This is an evaluation
hypothesis, not a measured speedup or a claim that those systems cannot also be
specialized.

Still to define: exact RX layout and entry ABI, including continuation state
and stack discipline; procedure/control pointer registration, validation and
lifetime under process isolation and reassignment; allocation and cleanup in
specialized entries; error, deferred/forwarded and nested-completion paths;
timer delivery/rearming and the continuation after yield; code-size tradeoffs;
and a matched comparison that isolates the specialization's savings.
Coordinate any implementation with the
[ECI boundary contract](../software/nested-rpc.md#endianness-at-the-host-boundary)
and [cache-line interface](../architecture/hw-sw-interface.md), including ownership
and progress rules and matched generated hardware/software collateral.

## Unified RX cache line

Every RX result, including kernel bypass/ARP, uses the same outer format:
an 8-byte executable entry pointer followed by 120 bytes of entry-specific data.
The entry determines the data format; there is no common type/length header.
Kernel entries must be kernel-owned targets selected within the kernel bypass
domain; user registrations must not supply kernel jump targets. User entries
are scoped to the owning process and registration lifetime.

| Entry | Entry-specific data |
| --- | --- |
| RPC request | 32-bit request token, reserved alignment space, argument payload |
| Nested RPC reply | Completion cookie, status, response payload |
| Kernel bypass | Packet length and packet bytes |
| Neighbor/ARP work | Requested IP address and required neighbor/source/protocol context |
| NACK / DESCHED / DEADLINE | Any state required by the selected control entry |

Request tokens and completion cookies serve analogous correlation roles but
refer to different tables and lifetimes. The existing nested-reply path also
checks a separate XID after cookie lookup; removing that host field requires
preserving equivalent matching in hardware or the new completion contract.
Exact completion/control metadata offsets and overflow indications remain open.

The RPC request specialization keeps numeric fields naturally aligned and
payload bytes wire-native:

| Bytes | Proposed contents |
| --- | --- |
| `0..7` | Executable entry pointer |
| `8..11` | Opaque 32-bit request token |
| `12..15` | Reserved; completes the 8-byte token slot |
| `16..127` | Fourteen 8-byte words of RPC payload (112 bytes) |

The second word reserves eight bytes for the current 32-bit request token and
future metadata, keeping payload naturally aligned at byte 16. No TLS pointer
is carried: worker TLS is already available on the executing OS thread, and
fiber stack switches do not install a separate TLS base. Application context
is baked directly into the specialized trampoline, not carried in the RX line.

The token selects the hardware reply context retaining the original client XID
and return address; it is not the wire XID or an outgoing nested-call cookie.
The entry retains it through reply submission. Deferred/forwarded requests must
save it in owned request state before later receives, preserving the existing
[reply-context lifetime](../software/reply-contexts.md). All remaining fourteen
words are RPC payload; no generic type or length word is added.

Do not spend RPC request cache-line space on a generic payload-length field. The specialized
codec knows fixed field sizes, and variable-length fields carry their lengths
in the encoded payload. For fixed-size codecs, either omit the receive-length
check under an explicit accepted-input contract or program a per-codec hardware
length filter so the CPU entry needs no such check. Hardware still retains the
actual received length for packet storage and transport accounting.

An encoded variable length describes intended content; it does not establish
that all those bytes arrived. Define how truncated messages, oversized embedded
lengths and padding are handled before enabling unchecked decoding. A hardware
min/max-length filter alone cannot validate every embedded length. The design
must specify codec-aware hardware validation, bounded decoding with a supplied
or derivable end, or an explicit restriction on accepted inputs. None requires
committing to a generic length word in this proposed line.

## Overflow placement alternatives

Compare two zero-staging-copy layouts, using the entry-specific inline capacity
`I` (112 bytes for the aligned RPC request above):

- **Split prefix:** retain the first `I` payload bytes in the selected control
  line and put the remainder in overflow. Inlined read accessors translate
  logical offsets to the control span or overflow span. Select the current
  control base once from parity; never read through the opposite control line,
  since that is a receive doorbell. Fixed-offset accesses can specialize to
  direct loads. Dynamic offsets require span selection, and accesses crossing
  the boundary require split reads/copies. Contiguous-slice and mutable parsers
  need corresponding adaptations.
- **Whole-message overflow:** complete small payloads remain inline; when a
  payload exceeds `I`, all bytes start at overflow byte zero and no payload is
  carried in the control line. A placement indication or entry-specific length
  selects the buffer once, after which existing contiguous codecs can read it
  directly. A distinct entry address could encode placement instead of adding
  a pre-jump type test. This preserves the current placement strategy with a
  larger inline capacity.

For a full sequential read of `N > I` bytes, the logical ECI line counts,
including the mandatory control line, are `1 + ceil((N-I)/128)` for split prefix
and `1 + ceil(N/128)` for whole-message overflow. Split prefix saves zero or one
overflow line, depending on size; it does not save one for every oversized
request. Actual cache misses, prefetch, retirement traffic and exposed latency
need measurement. With `I=112`, both layouts need two lines for 128-byte payloads;
200-byte payloads need two versus three. An always-overflow variant would also
add a payload-line access for small requests that fit in the delivered control
line.

No isolated accessor-overhead or overflow-fetch timing is available. Compare
generated code and matched hardware measurements for both parities, fixed and
variable offsets, boundary-crossing fields and representative sizes. Charge
address-selection instructions separately from extra coherence traffic; do not
use the historical TX reclaim latency as an RX overflow-read cost. Preserve
borrow lifetime, bounded reads and mutable replay in either layout. The existing
64-byte descriptor/64-byte payload split remains the implemented ABI until
matched hardware/software changes.

## Measurement plan

Separate Dispatch (service/codec selection, invocation, outcome routing and
scheduling handoff) from Runtime (validation, allocation and request/reply
bookkeeping). The acquired version-4 data combines these in Runtime/dispatch and
cannot isolate all removable dispatch costs. Existing setup intervals mix
selection with initialization, and codec/handler spans include indirect calls.

Place one timestamp at each transition between meaningful work intervals. Do
not introduce a separate “NIC submission starts” boundary: charge preparation
and handoff from network-packet-ready (LH: reply-descriptor-ready) through the
pre-publication boundary to NIC message passing. Retain distinct RX scheduling
wait where it separates Dispatch from NIC processing. Version-5 instrumentation implements this accounting for future acquisitions;
existing observations retain their original accounting.

Evaluate matched generic and specialized LH builds with identical codec and
handler semantics and instrumentation-off/on acquisitions. The measured scope
is request-to-reply publication, not total CPU work per request; see the
[measurement methodology](../research/hot-measurement.md) and
[timestamp contract](../development/rpc-breakdown.md).
