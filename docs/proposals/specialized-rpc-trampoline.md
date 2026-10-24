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

## Packed request cache line

Pack the request into one 128-byte ECI coherence line, keeping numeric fields
naturally aligned and payload bytes wire-native:

| Bytes | Proposed contents |
| --- | --- |
| `0..7` | Executable entry pointer |
| `8..15` | Optional TLS/context pointer |
| `16..127` | Fourteen 8-byte words of RPC payload (112 bytes) |

If the entry can obtain its context from existing thread state, omit the second
pointer and use bytes `8..127` for fifteen payload words (120 bytes). Decide the
context convention at registration/entry specialization time; do not add a
generic optional-field test before jumping. A TLS/context pointer here is an
entry argument; whether it denotes runtime state or a language TLS base remains
an ABI decision.

Do not spend cache-line space on a generic payload-length field. The specialized
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

The packed line also omits the current request token. Specify how reply
submission identifies the current request, for example through an implicit
per-worker current-request context; deferred/forwarded work needs a way to retain
that identity across later receives. Payload overflow beyond 112/120 bytes and
its addressing remain open. Resolve both without silently taking words from
the stated inline capacity. The existing 64-byte descriptor/64-byte payload
split remains the implemented ABI until matched hardware/software changes.

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
