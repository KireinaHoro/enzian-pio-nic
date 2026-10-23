# Userspace TCP

Branches: `userspace-tcp-phase-two` in platform and `../lauberhorn-tcp`.
Platform phase two starts from `b9e0ab05` on `userspace-tcp-phase-one`.
Scope: [phase two](../docs/proposals/userspace-tcp.md#phase-2-shared-freebsd-tcp-library-and-validation-in-the-vm).
Keep build selection, CPU flags, VM configuration and kernel options in Nix.
Push incremental commits to the private GitLab remotes with `-o ci.skip`.
Do not merge this branch until the hardware DEADLINE path is implemented;
the user explicitly requires removal of RPC polling without an ECI fallback.
Stop at major blockers. TCP source import is authorized in `../lauberhorn-tcp`;
no hardware deployment or memcached port belongs to this phase. Keep the TCP
component optional in application Nix recipes and preserve the UDP path.

## Phase-one implementation

The shared kernel/runtime now select ECI or VM at compile time. The VM device
runs in a kernel thread and uses bounded queues, private mapped buffers and
transport ioctls. It shares registration, affinity, yield and activation logic
with ECI. Native UDP, ONC-RPC, nested RPC and UDP memcached run in the Nixpkgs
NixOS test. See the [backend contract](../docs/software/vm-backend.md) for
ownership and lifecycle limits. TCP and domain timer activation remain phase 2.

Nix owns runtime CPU flags, backend selection, kernel module compilation,
emulator queue/wait parameters and guest topology. AArch64 retains ThunderX
tuning. The existing nested-validation fixture now uses the public pending-call
admission limit rather than its larger backing table size for saturation tests.
Its CPU flags also come from the selected runtime package.

## Phase-one validation evidence (October 23, 2026)

- KVM works outside the agent sandbox (API version 12). The sandbox hides
  `/dev/kvm`; this is not a VT-x or host KVM failure. Nix does not advertise the
  `kvm` feature here, so build `.driver` and run it outside the sandbox.
- `out/userspace-vm-acceptance` is the Nix-built driver. Its full two-guest KVM
  acceptance run passed in `out/userspace-vm-acceptance-run/driver.log` (15.99 s).
  UDP: zero through 1472 bytes, four workers, competing domains, syscall
  blocking, runtime/kernel ownership checks and bypass. RPC: cases 0–14,
  post-error progress, 16-context admission exhaustion and recovery.
  Memcached: store/get, arithmetic, delete, 4096-byte segmented response.
  Second guest: UDP and memcached over the virtual network.
- ECI module, AArch64 runtime and runtime regression/sanitizer checks passed
  during integration: `/tmp/lh-vm-validation-build.log` and
  `/tmp/lh-vm-final-build.log`. Final Nix builds of `runtime-tests`, the ECI
  module, AArch64 runtime and `output-interface` all passed; see
  `/tmp/lh-vm-regressions.log` and `out/userspace-vm-regressions*`.
  Modified documentation links and `git diff --check` also passed.
- Initial failures and one-off diagnostic scripts remain ignored under `out/`.
  The first memcached test incorrectly assumed multi-get uses one sequence
  space; the pinned application's UDP sender frames each response separately.
  The maintained fixture now uses the established append/single-get
  segmentation case. No memcached source modification was needed.

## Phase-two current state

TCP repository: `060d11f` (public API and local contention retry) on `userspace-tcp-phase-two` (unmerged).
FreeBSD 15.1-RELEASE-p6 is pinned at
`95719d8a895bf8170851e9844d9b4092b1d2b6fe`: 26 C files, 282 headers and COPYRIGHT.
The manifest verifies all 309 pristine files. Helpers include PCB/socket,
sockbuf/mbuf, hashes/checksum, event handlers, sbuf, formatter and inet_ntoa.
No F-Stack source or full FreeBSD tree is imported. The ignored
`out/tcp-import/dependencies.nix` temporarily supplies upstream headers for
compiler dependency discovery; only reported headers enter the manifest.
The self-contained package does not depend on that checkout.

`port/` now runs ordered FreeBSD SYSINIT hooks, shared process/credential and
resource-accounting state, one IPv4 interface/route, per-worker context and clock
snapshots, actual mutex/RW/SX locks, atomic counters, allocation, mbufs and shared
callouts. The packet boundary copies IPv4/TCP input into stack-owned buffers and
copies checksummed output into a bounded queue drained outside stack locks.
The interface/route are immutable for process lifetime. Unsupported IP options,
fragments, IPv6, AIO, sendfile/physical pages and descriptor observers are not
exposed. Kernel sleep is a fail-fast error; empty observer lists retain their
normal semantics. Wakeup increments a shared generation, but it is not yet wired
to the runtime's parking handshake.

Network epochs and SMR have separate reader domains. PCB reinsertion can wait
for SMR with PCB/hash locks held; `_inp_smr_lock()` leaves SMR before blocking
on the PCB lock. Network epoch readers can wait on those locks, so merging the
reader classes would deadlock. Deferred frees wait for both classes, preserving
host timer callback lifetime. UMA allocations retain cache-line alignment even
when upstream asks only for pointer alignment: a sanitizer found that sockets
require 64-byte alignment despite UMA_ALIGN_PTR. This is fixed in `alloc.c`.

Callouts use one shared queue and associated connection locks. Per the user’s
latest decision, contention must retry locally without a DEADLINE rearm fallback;
a Nix-configured watchdog must fail fast on prolonged contention. The shared round-robin scan cursor avoids
starvation across bounded batches when an early slot repeatedly rearms. Notify
hooks must be nonblocking because reset/stop can invoke them under connection
locks; actual emulator deadline publication must happen outside stack locks.
Callbacks returning unlocked remain protected by a network grace period and
queue metadata independent of the embedded callout's lifetime.

Nix localizes kernel definitions before linking the native host-libc boundary,
separating kernel malloc/free/formatting ABIs from libc. Upstream subr_prf supplies
kernel formatting; native console functions are renamed and discarded. Function
and data sections permit link GC of unsupported generic kernel facilities.
`required-host-symbols.txt` reports undefined names before final link GC, so its
optional unused names are not proof that the protocol executable cannot link.
The package installs `lauberhorn/tcp.h`, the static archive and pkg-config
metadata. One through four workers are selectable in Nix; the protocol fixture
uses the public API/archive and races close across workers, rejecting stale
handles. `passthru.runnable = false` still indicates absent VM/runtime integration.

## Current phase-two evidence

- Normal self-contained Nix build passed: `out/tcp-host`,
  `/tmp/lh-tcp-protocol-final.log`, derivation
  `fg3pwjm9dblg0j3zbyf8brnsnh4h3dm4-lauberhorn-tcp-core-0.1.drv`.
- Final maintained sanitizer build passed: `out/tcp-sanitized`,
  `/tmp/lh-tcp-protocol-sanitized-final.log`, derivation
  `l6nxw65fc02ikk6rip6qqwflid826awh-lauberhorn-tcp-core-0.1.drv`.
  `sanitize = true` is a package Nix option. ASan/UBSan are enabled; leak checks
  are disabled for process-lifetime stack state, and ASan global registration
  is disabled so unused kernel facilities remain eligible for link GC.
- Both installed normal executables passed directly:
  `libexec/tcp-concurrency-test` and `libexec/tcp-startup-test`.
  The latter prints `TCP_PROTOCOL_PASS`: one real FreeBSD connection receives
  SYN, handshake ACK, data and ACK/FIN packets on different workers. Other
  workers accept/read/write/close it. The test verifies both data directions
  and the final FIN/ACK exchange. The synthetic peer lives in the fixture;
  this is not Linux-peer interoperability or VM transport acceptance.
- Negative substrate tests reject concurrent reader/writer acquisition and
  concurrent callbacks on one connection lock; overdue timers remain pending
  and retry delivers exactly once without a packet/reset. Separate connections'
  callbacks overlap. Further cases cover a repeatedly rearmed timer versus an
  unrelated expiry, close during an unlocked callback, SMR/lock-order progress,
  deferred UMA destruction/capacity release, allocation overflow and mbuf clones
  surviving release of the original.
- Manifest verification and non-vendor whitespace checks passed. The protocol
  executable has no GS/CR-register/CLI/STI/INVLPG accesses:
  `out/tcp-import/protocol-disassembly.txt`.
- Ptrace is disallowed even outside the sandbox. The ignored Nix sanitizer
  diagnostic recipe located the alignment issue; no external blocker remains.

Local contention retry checks passed (normal and ASan/UBSan):
`out/tcp-local-retry`, `out/tcp-local-retry-sanitized`,
`/tmp/lh-tcp-local-retry-final.log`, `/tmp/lh-tcp-local-retry-sanitized.log`.
The dispatcher releases queue protection, retries the same timer locally and
revalidates after reset/cancel. A stuck-lock subprocess must SIGABRT with the
watchdog diagnostic. `lockWaitMs` in Nix defaults to 100 ms; no rearm fallback.

## VM scheduler and DEADLINE integration

Platform commit `b50e064f` implements hardware-style packet allocation at event
boundaries: pre-enqueue half-capacity scaling, idle-first/first-ready victims,
and first-match zero-core discovery after local drain. Per-domain capacity comes
from generated headers; the VM no longer round-robins competing packet domains.
RTL pipeline timing is not modeled. DEADLINE is one reserved notification with
generation-checked SET/GET, lead-time rejection and atomic acknowledgement/rearm.
Allocation is zero-to-one only, otherwise RX-ready donors at or below the watermark.
Overload delay and long cooperative execution remain accepted limitations.

The final device-level Nix VM suite passed in 31.39 s:
`out/userspace-vm-deadline-final-run/driver.log`,
`/tmp/lh-vm-deadline-final-run.log`. Tests cover watermark scaling/background
selection, donor protection, single delivery across four workers/full queues,
stale and too-soon operations, cancellation, earlier rearm and timer-only TX.
UDP/RPC/memcached and the peer-guest regressions passed. Runtime/interface checks
and ECI kernel compilation passed (`/tmp/lh-vm-deadline-runtime-checks.log`,
`/tmp/lh-vm-deadline-eci-check.log`).

The subsequent RPC migration removes timeout polling on every backend. Publishing
occurs on call-state changes; DEADLINE drives sweeps and rearm. ECI nested calls
fail before TX until the hardware interface exists. The full-parked RPC fixture
passed: a UDP burst takes every core, then a no-reply timeout reactivates RPC,
retires its outgoing call and resumes the waiter; a late reply is ignored.
Final VM suite: 29.96 s, `out/userspace-vm-rpc-deadline-run/driver.log` and
`/tmp/lh-rpc-deadline-vm-run.log`; driver build is `out/userspace-vm-rpc-deadline`.
Final runtime/interface checks passed in `/tmp/lh-rpc-deadline-final-checks.log`,
including injected ETIME retries and stale-notification rejection. No polling
fallback remains on either backend. Timer publication currently scans the bounded
pending table on state changes; it does not scan or read the clock on ordinary RX.
RPC exclusively owns the domain timer; multiplexing independent TCP/application
timer providers in one domain needs a shared broker.

Phase 4 supplies ECI DEADLINE/raw TCP steering and explicitly specifies/cleans up
all scheduler reclaim rules. Public API changes are committed and pushed. Normal
four-worker, one-worker and ASan/UBSan public API checks passed:
`out/tcp-api-public`, `out/tcp-api-single`, `out/tcp-api-sanitized`.
No TCP connection has yet run in the VM.

## Native TCP VM transport increment

The platform pins TCP `060d11f` as optional `dev.x86_64-linux.software.tcpVm`.
`REG_TCP_ENDPOINT` (ioctl 17) reserves a local address/port before registration;
raw RX/TX descriptors carry full IPv4/TCP packets. VM TX validates source ownership
and uses Linux IP routing only. Runtime attach/receive/release/send is in
`tcp_transport.h`/`sw/rt/tcp_transport.c`. DEADLINE events are surfaced to callers;
there is no TCP stack adapter or timer publisher yet. ECI returns EOPNOTSUPP.

The maintained raw transport fixture passed against a Linux TCP client: SYN RX,
RST/ACK TX, wrong endpoint TX rejection, RX borrow/release rules. Full VM suite
passed at `out/userspace-vm-tcp-transport-fixed-run` and
`/tmp/lh-vm-tcp-fixed-run.log`; Nix driver `out/userspace-vm-tcp-transport-fixed`.
ECI kernel/runtime and output-interface checks passed:
`/tmp/lh-tcp-eci-compat.log`. This fixture constructs RST packets itself and is
explicitly not evidence of a FreeBSD connection in the VM.

An initial raw TX bug passed an skb-consuming function directly to
`net_xmit_eval`, which can evaluate its argument twice. The duplicate call caused
an emulator-thread kernel fault after emitting the first packet. Fixed by storing
the return value before invoking the macro; the successful VM suite includes the
no-kernel-Oops gate. Failed-stage diagnostics remain in
`/tmp/lh-vm-tcp-debug-run.log`. The first hanging probe was explicitly interrupted;
no test processes remain. The maintained probe now bounds marker waiting to 10 s
and prints the fixture log on failure.

Next integrate the optional stack with this transport and DEADLINE, using actual
Linux-peer connections. Account for host GSO/GRO packets in VM ingress: current
transport accepts only MTU-sized packets, so large transfers may expose virtual
network offload aggregation. Keep any configuration in Nix, or normalize packets
in the emulated wire boundary. Do not treat the raw transport probe as phase-two
completion.

## Remaining phase-two work

The objective remains a reasonably working shared four-worker TCP stack in the
Lauberhorn VM emulator. No stress campaign is required. VM scheduler/deadline and
existing application tests are separate from TCP acceptance; do not mark phase
two complete based on host TCP tests.

1. Public API and Nix package are implemented. Continue exercising their socket
   lifetime and nonblocking semantics through the actual VM transport.
2. The optional `tcpVm` Nix package pins the pushed TCP source beside runtime,
   without a mandatory runtime TCP dependency. Add it to the TCP VM application
   fixture recipe. The package supports one to four workers; concurrency substrate tests run
   in the four-worker configuration.
3. Native TCP VM kernel/runtime transport is implemented; connect the stack
   API and timer publication to it and the VM DEADLINE interface. No Linux TCP sockets may substitute for the stack.
   Preserve transport borrow release before DESCHED/yield. Timer expiry must be
   sticky/coalescible and activate a parked domain. Re-read the earliest deadline
   after bounded dispatch. Connection contention must resolve locally or fail
   its watchdog, never return for a deadline-rearm fallback. Earlier rearm and sleep-entry races
   need generation-safe publication/acknowledgement outside stack locks.
4. Run the phase-two VM functional matrix with normal TCP peers: four-worker
   packet spraying, independent connection progress while one handler is busy,
   flow control, loss/retransmit, reordering, FIN/RST, allocation/TX exhaustion,
   close/timer races, earlier rearm and expiry during parking. Add negative tests
   at the actual PCB/socket integration level, beyond the existing lock fixture.
   Specifically prove delayed expiry runs after lock release without new packets
   and that all-parked timer-only activation can transmit ACK/retransmission.
   Alternate bursts between two applications with established connections,
   prove each reaches zero assigned workers, then verify both resume on the same
   connections. Include timer expiry without incoming traffic while fully
   preempted, and service under competing load when an eligible donor exists.
   The VM now provides timer-only eligibility and wakeup; exercise the actual
   stack callbacks, not just the transport fixture. Preserve pending expiry
   across handoff and acknowledgement.
   Preserve phase-one UDP/RPC/memcached regressions. Stop after this demonstrated
   four-worker implementation; memcached TCP port and hardware remain later phases.

F-Stack's local reference revision `813b82016c5cebfcc545cb44fbbc19dd8d00e99d`
has no-op locks, immediate epoch reclamation and TLS callout ownership that
ignores the target CPU. These are unsuitable implementations to reuse, not a
major blocker to the intended port. Do not replace the current design with
per-worker stacks, a global stack lock or a helper TCP thread.

Whole-domain recovery remains unsupported: failed domains retain resources
until guest restart. No Enzian execution or hardware validation was added.
