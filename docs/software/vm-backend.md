# Software-only device backend

The VM backend builds the shared Lauberhorn kernel module and runtime for native
Linux. `LH_BACKEND_VM` selects it at compile time; Nix owns this selection and
all CPU, queue and guest settings. Runtime initialization checks the module's
backend ID before mapping buffers. Public application APIs are unchanged.
Native TCP and domain-scoped timer activation are not implemented.

## Execution and ownership

`sw/kmod/vm.c` runs device operations in the `lh_device` kernel thread on a
housekeeping CPU. An IPv4 local-input hook selects registered UDP ports and
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

Each application maps private RX/TX storage for every device worker. Transport
ioctls validate the calling registered thread's active assignment. RX borrow and
TX reservation checks exist in the shared runtime and the device backend. RX
cannot block while either is outstanding. After DESCHED, transport operations
are rejected until yield installs the next activation. A TX completion means
that the host UDP stack owns a copy; it does not imply peer receipt.

The shared `sw/core/transport.h` interface selects ECI cache-line operations or
VM ioctls. Descriptors and common transport state live in `transport_types.h`;
the private VM ioctl ABI is in `sw/core/vm/abi.h`. VM buffers reuse the generated
per-worker stride but do not model ECI aliases, coherence or timing.

## Bounds and lifecycle

Nix defaults to 64 ingress packets and 64 queued packets per domain, with a
1 ms blocking-RX NACK interval. Incoming and outgoing RPC context tables use the
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
not a supported recovery mechanism. Existing nested-RPC deadlines are polled by
active workers; they do not activate an entirely parked domain.

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
