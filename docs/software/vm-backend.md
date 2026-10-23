# Software-only device backend

The VM backend builds the shared Lauberhorn kernel module and runtime for native
Linux. `LH_BACKEND_VM` selects it at compile time; Nix owns this selection and
all CPU, queue and guest settings. Runtime initialization checks the module's
backend ID before mapping buffers. Public application APIs are unchanged.
Native TCP transport carries complete IPv4/TCP packets for explicitly registered
endpoints. Domain-scoped DEADLINE activation drives RPC timeout processing.
The optional TCP runtime adapter connects the stack to transport and deadline
publication.

## Execution and ownership

`sw/kmod/vm.c` runs device operations in the `lh_device` kernel thread on a
housekeeping CPU. An IPv4 local-input hook selects registered UDP ports and
TCP address/port pairs, then
queues packets; the device thread validates and classifies them. Unregistered
ports retain ordinary host delivery. IP options and fragmented packets bypass
the emulator; malformed, oversized or unsupported RPC packets on registered
ports are dropped. Services support the existing fixed AUTH_NULL ONC-RPC format.

Application/domain registration, endpoint ownership, worker registration,
bootstrap yield, affinity installation and activation identity use the shared
kernel control and scheduling code. The device chooses eligible domains with
queued work, respecting each domain's declared worker count. A competing domain
can cause blocking RX to return DESCHED; the assignment changes only at the
subsequent explicit yield. Ordinary syscall blocking retains the assignment.
There is no asynchronous handler preemption.

Packet allocation follows `Scheduler.scala` at device-event boundaries:

- An admitted packet whose recipient's pre-enqueue fill is at least half the
  generated per-domain capacity requests an idle core, otherwise the first
  RX-ready core belonging to another domain, up to the declared worker count.
- Below that watermark, arrival requests only an idle core and only for a
  zero-core recipient.
- After local queued packets drain, background discovery selects the first
  eligible queued zero-core domain. It does not round-robin between active
  domains. Core reservations count toward recipient capacity before handoff.

The VM does not model RTL lookup pipeline latency, simultaneous-cycle arbitration
or ECI timing. Its event ordering establishes policy behavior, not cycle-exact
scheduling. Packet-driven reclaim does not protect donors above the watermark;
that restriction belongs specifically to DEADLINE activation below.

Each application maps private RX/TX storage for every device worker. Transport
ioctls validate the calling registered thread's active assignment. RX borrow and
TX reservation checks exist in the shared runtime and the device backend. RX
cannot block while either is outstanding. After DESCHED, transport operations
are rejected until yield installs the next activation. A TX completion means
that the host networking path owns a copy; it does not imply peer receipt.

The shared `sw/core/transport.h` interface selects ECI cache-line operations or
VM ioctls. Descriptors and common transport state live in `transport_types.h`;
the private VM ioctl ABI is in `sw/core/vm/abi.h`. VM buffers reuse the generated
per-worker stride but do not model ECI aliases, coherence or timing.

## Native TCP transport

`lauberhorn/tcp_transport.h` registers a host-order local IPv4 address and TCP
port before workers start. Registration reserves the port with a bound, inactive
kernel socket; it does not use Linux TCP for protocol processing. The address
must be local, the caller must be in the initial network namespace, and privileged
ports require bind capability. Repeating a registration with input handle zero
returns the existing handle for that domain. Reservations persist for domain
lifetime; endpoint teardown is not exposed. TCP and UDP port spaces are separate.

RX validates and copies complete IPv4/TCP packets into the ordinary domain queue.
TX validates packet lengths/checksums and verifies its source address/port against
the submitting domain's endpoint handle. Linux supplies IP routing and neighbour
resolution; the application supplies TCP state and wire bytes. Plain IPv4 without
fragmentation/options is supported. ECI registration and runtime TCP operations
return `EOPNOTSUPP` until the hardware path exists.

The runtime exposes attach, receive/release and buffer submission. Receive handles
DESCHED through the shared yield path and returns either a borrowed packet or a
payload-free DEADLINE generation. The optional TCP component copies RX input once into FreeBSD-owned mbufs before
releasing the RX borrow. TCP output fills and commits the current worker’s actual
TX reservation synchronously, including output under connection locks; no shared
TX staging queue exists. It publishes/acknowledges timers before parking. The runtime itself
has no dependency on the TCP library. Nix exposes the optional package as
`dev.x86_64-linux.software.tcpVm`.

## DEADLINE control and delivery

`lauberhorn/deadline.h` defines `SET_DEADLINE` and `GET_DEADLINE`. SET compares
`expected_generation` with the current domain generation, atomically acknowledges
old work and publishes a new generation. `next_deadline_ns` is absolute
`CLOCK_MONOTONIC` time; zero cancels. A fresh domain starts at generation zero.
Stale generations return `ESTALE`; deadlines less than the advertised minimum
lead time ahead of device processing return `ETIME`. Both leave state unchanged.
GET returns the current generation, time, minimum lead and pending/delivered
flags. If SET's result cannot be copied back after commit, GET reconciles state;
callers must not assume `EFAULT` rolled the command back. ECI returns
`EOPNOTSUPP` until phase-four implementation. The old `KICK_PROCESS` ioctl was
removed, and its number is not reused.

Expiry occupies one reserved domain slot independent of packet queue capacity.
The next eligible RX delivers payload-free `TY_DEADLINE` and its generation to
one worker, without an RX borrow or packet-buffer release obligation. The event
remains pending but is not redelivered until acknowledged/replaced through SET.
Core reservation or activation does not acknowledge it. If successive generations
remain due alongside packets, a packet is served between deadline deliveries.
Already accepted packet delivery is not withdrawn.

A zero-core domain may receive one idle core or an RX-ready donor core whose
queue is at or below half capacity. Assigned and reserved cores prevent further
deadline-only allocation. Pending deadline domains rotate for allocation; packet
arrival reservations run first. When all donors are protected or executing
handlers, expiry remains pending. Long handlers, syscall sleep and unbounded
fiber draining retain the cooperative limitation. Detection uses the emulator's
bounded event-loop wait; no punctual callback guarantee is implied.

The RPC runtime consumes DEADLINE and services/rearms its pending-call timers.
Submission, completion and cancellation update the published minimum; ordinary
RX performs no timeout clock read or sweep-election CAS. RPC owns the domain
timer while using this path: independent timer providers must be multiplexed
before sharing a domain. The native UDP receive API is not a deadline consumer.
The optional TCP adapter supplies its own exclusive domain timer publisher. The VM policy fixture uses the
low-level RX interface to test timer-only activation and TX. Read-only
`LH_VM_SCHEDULE_STATE` reports this domain's queue capacity/fill, actual and
reserved assignments, RX-ready workers and allocation counts by reason.

## Bounds and lifecycle

Nix defaults to 64 ingress packets and a 1 ms blocking-RX NACK interval.
Per-domain capacity comes from generated `RX_PKTS_PER_PROC` (16 by default),
matching the hardware watermark. Nix sets a 1 ms minimum deadline lead time. Incoming and outgoing RPC context tables use the
generated session bound (16 in the acceptance configuration). Full incoming
RPC context tables return SYSTEM_ERR; full outgoing tables reject TX for retry.
Queue allocation/admission failure drops packets. RPC cookies and remote tuple
checks protect reply matching; explicit retirement releases outgoing contexts.

Kernel sockets reserve registered UDP ports and supply outgoing IP/checksum and
neighbor handling. Native endpoint namespace/privilege checks remain shared.
Service deregistration is restricted to before worker registration in the VM;
it purges queued calls before releasing the handler. Whole-domain failure
retains domain slots, mappings and endpoint reservations as in the existing
prototype. Restart the guest for a fresh domain lifecycle. Killing a handler is
not a supported recovery mechanism. RPC deadlines can activate a completely
parked domain when an idle core or unprotected RX-ready donor is available.

## Validation

The Nixpkgs `testers.runNixOSTest` recipe in `nix/checks/vm.nix` supplies a
six-vCPU guest, four isolated worker CPUs, the matching module/runtime,
rpcbind, native application packages and a second guest as network peer.
Run on a Linux host with accessible KVM:

```sh
nix build .#checks.x86_64-linux.userspace-vm
```

The Nix builder must advertise the `kvm` system feature. Alternatively, build
and run the same test driver directly where `/dev/kvm` is accessible:

```sh
nix build .#checks.x86_64-linux.userspace-vm.driver -o out/userspace-vm
mkdir -p out/userspace-vm-run
out/userspace-vm/bin/nixos-test-driver -o out/userspace-vm-run
```

The test exercises zero-length through MTU-sized UDP, four-worker activation,
competing-domain handoff, syscall blocking, RX/TX ownership rejection and host
bypass. It runs the existing nested-validation application through immediate,
deferred, forwarded, timeout, cancellation, pending-table saturation and error
cases, then fills and releases emulator RPC contexts. UDP memcached covers
store/get, arithmetic, delete and segmented replies. The peer guest exercises
native UDP and memcached over the virtual network. These are functional checks,
not performance measurements or ECI hardware acceptance.

Before the application regressions, a two-domain policy fixture checks the
pre-enqueue watermark, idle-first and RX-ready allocation, local-drain background
discovery, protected-donor deadline deferral, reclaim at the watermark, full-queue
notification admission, single delivery across four workers, zero-to-one
activation, stale/too-soon rejection, cancellation, earlier rearm and timer-only
TX. It reboots the guest afterward because whole-domain teardown is unsupported.

The nested-RPC fixture also records its own assignments through read-only
diagnostics. A competing UDP burst takes every core while a nested reply is
withheld; DEADLINE must reactivate the parked domain, retire the call, resume its
waiter and reject a late reply. The observing main thread performs no timeout
processing or publication. RPC regression cases then verify continued progress.
