# Registered nested RPC endpoints and local compositions

Implementation batch on `feat/nested-rpc`, 2026-09-14. This document records the
accepted contract; the blocker queue records validation status. B1 hardware
acceptance remains pending. Do not deploy this software with checkpoint 2812450.

## Ownership and control

Call `lauberhorn_rpc_endpoint_init(ctx, ep)` before creating workers and
`lauberhorn_rpc_endpoint_destroy(ctx, ep)` after joining them. Both return zero
or minus one with `errno`. Existing configuration fields and outgoing-call
signatures are retained; appended private transport state must start at zero.
Duplicate initialization returns `EALREADY`; destruction of an uninitialized
endpoint succeeds. Registered endpoints must not be copied or mutated. Keep the
endpoint, context, address and codec storage alive until destruction succeeds.
Control operations and worker creation/join require external serialization.
Retain codec storage until all returned results using that codec are freed,
even when the associated call has completed.

Initialization validates/caches the IPv4 address and uses a fixed-width padded
control ioctl to reserve a Linux UDP socket without reuse and an FPGA reply
listener. The kernel derives the hardware PID from the open device context,
rejecting identities that do not fit the generated PID width. A hardware PID is
a process identity, not a NIC datapath or a Linux CPU ID. Handles belong to the
open file context; another process cannot use its control operations.

The current NIC is restricted to the initial network namespace. Registration
from another namespace returns `EXDEV`. Endpoint source ports are selected from
49152–65535, skipping Linux bindings, registered FPGA services and reserved
reservations. Service and endpoint table writes are serialized, capacity is
checked before installation, and partial registration failures are unwound.

## Completion and lifetime contract

The cookie is a protocol-independent pending-slot/generation token. ONC-RPC XID
initially has the same numeric value, but is separately checked after locating
the slot by cookie. A slot is retired before its 16-bit generation wraps;
exhaustion returns `EAGAIN`. Numeric descriptor fields remain host-order; ECI converts
wire fields as specified in the [boundary contract](nested-rpc.md#endianness-at-the-host-boundary).
Sending uses cached PID, source port and address, without control ioctls.

Destruction returns `EBUSY` while workers or unresolved calls remain. A software
timeout alone does not prove hardware retirement: runtime timeout/cancellation
now uses an acknowledged PID/cookie retirement command before releasing its
reservation. Failed retirement retains unresolved ownership. Device close now
uses the [whole-process drain protocol](process-recovery.md) before releasing
listeners, sockets and table ownership. The previous permanent endpoint quarantine
has been removed. Reclamation does not report success while drain is unresolved;
matching-board recovery acceptance remains B4's closure gate.

## Internal loopback design and acceptance

Local routing must intercept encoded ONC-RPC calls and replies before UDP/IP/
Ethernet construction when the destination equals the configured NIC IPv4
address. Synthesized UDP/IP metadata and raw encoded RPC payload enter normal
UDP classification, RPC decode, service lookup, packet allocation, scheduling,
session lookup and completion matching. Unsupported services retain the normal
classification/error path, with no fallback to switch reflection.

Call and reply buffers must be separate and bounded, with whole-packet space
reserved before acceptance and registered ready/valid boundaries. External and
local ingress arbitration must keep metadata, payload and acknowledgment tied
to one packet. In-flight installation must finish before a local request becomes
visible. Local traces must distinguish delivery from physical TX/RX.

Dandelion must use a pinned external snapshot with tracked integration patches,
one shared initialized endpoint targeting the local NIC IP, and persistent
codec/address storage. Engine slots must remain distinct from Linux CPU IDs;
process engines execute on the actual isolated worker placement. B3/B6 fixes
and fixtures belong in separate commits from transport changes.

Required software/RTL gates: registration/error/ownership/collision/capacity/
rollback/destruction tests; cached descriptor and stale-cookie/XID tests; local
round trips with asymmetric fields, inline/overflow payloads, immediate replies,
mixed local/remote traffic, concurrent callers and backpressure; absence of local
nested CMAC traffic; Rust/C layout checks; independently checked single-function,
matmul/matmac DAG, sharding and nested-composition results, including concurrency.

The later shared Enzian campaign builds one final checkpoint, reviews timing,
and deploys matching headers, kernel/runtime, Dandelion, fixtures and squashfs.
Run add/mul, standalone/nested adders and Dandelion baselines, then compositions
on one FPGA-backed instance driven by the second Enzian. Retain manifests,
captures, local traces, affinity evidence, release tests and failed trials in the
[hardware checklist](nested-rpc-blockers.md#pending-bundled-implementation--enzian-checks).
Composition support remains unvalidated until those hardware cases pass.
Performance, general overload recovery and B4 abnormal-exit recovery are outside
this batch's acceptance claim.
