# Userspace TCP

Active branches: `userspace-tcp-phase-three` in platform and `../memcached`;
TCP remains pinned to completed phase-two `d80d5f8` on its phase-two branch.
The user authorized phase 3: one memcached source tree with TCP alongside UDP,
four instances with four threads each sharing four workers, separate bursts,
load below saturation and persistent connections.
Keep build selection, CPU flags, VM configuration and kernel options in Nix.
Push incremental commits to the private GitLab remotes with `-o ci.skip`.
Do not merge until hardware DEADLINE is implemented. Stop at major blockers.

## Phase-three working state

Memcached `46e8339` adds optional `lauberhornTcp` to its source-owned recipe.
Native TCP selects `-l IPv4 -p PORT -U 0`; UDP remains `-p 0 -U PORT`.
Shared connection parser locks permit migration across active workers. TCP
read buffers/response objects are independent of worker-local allocation pools;
response accounting retains its allocator's stats owner. Identity fds belong to
connection slots for the process lifetime, preventing cross-slot fd reuse races.
There is no TCP helper thread or application packet staging queue.

Readiness publication uses a shared atomic generation after input/timer dispatch.
Each parser scan snapshots it before taking locks and rechecks after releasing
all locks. A dispatch that skips a busy parser forces its owner to rescan; a
later dispatch can acquire that already-unlocked parser itself. Releasing a
closed slot also forces an accept-backlog pass before parking.

First full VM passed in 48.64 s: `out/userspace-vm-memcached-tcp-first-run`,
`/tmp/lh-memcached-tcp-vm-run.log`; driver `out/userspace-vm-memcached-tcp-first`.
It covers 16 TCP memcached streams from both Linux guests, partial commands/body,
pipelining, arithmetic, noreply, large responses, FIN/reset, existing UDP
memcached and the complete TCP/RPC/scheduler suite. This run predates the added
backpressure/upstream comparison and four-instance checks.

The expanded pre-fix suite failed on a 64-KiB store with a connection reset:
`/tmp/lh-memcached-colocation-run.log`,
`out/userspace-vm-memcached-colocation-run`. Fresh-VM single-store and complete
stream/backpressure reproductions passed (13.91 s and 18.08 s) in
`/tmp/lh-memcached-diagnose.log` and `/tmp/lh-memcached-diagnose-full.log`.
Root cause is not established; do not call this resolved based on those passes.
Current follow-up runs the complete suite against readiness fix `46e8339`:
`out/userspace-vm-memcached-readiness`, `/tmp/lh-memcached-readiness-run.log`.

Next checks: build/run expanded VM with upstream comparison, unread-response
backpressure, four domains/128 original streams, alternating bursts, observed
zero-core intervals and four-worker use, fixed 20 requests/s/domain low-load
phase. Test-only preload observes domain state and packet-worker IDs; its
ordinary thread never runs TCP or publishes timers. Add application-level
parked retransmission coverage and review allocation bounds/readiness races.

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

TCP repository: `d80d5f8` (shared stack, direct TX, DEADLINE adapter and bounded acceptance) on `userspace-tcp-phase-two` (unmerged).
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
callouts. The packet boundary copies IPv4/TCP input once into stack-owned mbufs and
outputs directly into the current worker’s Lauberhorn TX reservation, computing
checksums in place and committing before returning. No shared TX staging exists.
The interface/route are immutable for process lifetime. Unsupported IP options,
fragments, IPv6, AIO, sendfile/physical pages and descriptor observers are not
exposed. Kernel sleep is a fail-fast error; empty observer lists retain their
normal semantics. Wakeup increments a shared generation for consistent timer
snapshots; the adapter publishes the minimum before each worker can park.

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
handles. `passthru.runnable = true`; the optional runtime adapter is installed when a
matching runtime is supplied.

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
The first FreeBSD/Linux-peer VM echo run passed in 33.99 s:
`out/userspace-vm-tcp-stack-run`, `/tmp/lh-vm-tcp-stack-run.log`. Both guests ran
16 concurrent connections with sizes 1, 17, 1400, 4096 and 16384 and FIN shutdown.
The subsequent direct-TX run passed the complete suite in 32.07 s:
`out/userspace-vm-tcp-direct-acceptance-run`,
`/tmp/lh-vm-tcp-direct-acceptance-run.log`. Driver:
`out/userspace-vm-tcp-direct-acceptance`. TCP now fills the caller’s actual TX
reservation directly; the emulator copies it directly to its final skb, with no
intermediate packet buffer. RX retains one copy into FreeBSD-owned mbufs because
reassembly/socket data may outlive the RX borrow. Normal host checks passed in
`out/tcp-direct-tx`; ASan/UBSan checks passed in `out/tcp-direct-sanitized` and
`/tmp/lh-tcp-direct-sanitized.log`.

The first direct-TX VM run stopped before TCP on a scheduler fixture assumption:
concurrent draining can produce a donor below, rather than exactly at, half fill.
The assertion now verifies the actual at-or-below-watermark rule; scheduler
policy is unchanged. Failure evidence: `/tmp/lh-vm-tcp-direct-final-run.log`.
Exact-boundary acceptance needs a deterministically gated donor fixture rather
than inferring it from concurrent drain timing.

## Native TCP VM transport increment

The platform pins TCP `d80d5f8` as optional `dev.x86_64-linux.software.tcpVm`.
`REG_TCP_ENDPOINT` (ioctl 17) reserves a local address/port before registration;
raw RX/TX descriptors carry full IPv4/TCP packets. VM TX validates source ownership
and uses Linux IP routing only. Runtime attach/receive/release/send is in
`tcp_transport.h`/`sw/rt/tcp_transport.c`. DEADLINE events are surfaced to callers;
the optional TCP stack adapter now publishes timers and processes those events. ECI returns EOPNOTSUPP.

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

The bounded two-application TCP scheduling fixture now observes each domain through
read-only VM scheduling ioctls. Its main thread never runs timers or protocol work.
Each application uses four workers, and the test requires all four worker IDs to
process events. Twenty-four persistent connections per application carry alternating
bursts; each application is explicitly observed with zero assigned and reserved
workers, then resumes on an original connection. A 200 ms busy application handler
holds no TCP lock; another connection must reply while that handler remains busy.

Timer-only acceptance drops incoming pure ACKs before the emulator's ingress hook.
After the server's echo acknowledges the request, a competing TCP burst removes
all its workers. The test requires a new DEADLINE activation and captures the same
TCP sequence/payload retransmitted after that zero-worker observation, before any
new application data. Removing the filter and exchanging another payload verifies
continued connection progress. This is a bounded functional check, not stress.

Full VM acceptance passed in 42.64 s with these checks and existing UDP/RPC/
memcached, raw transport and both-guest TCP regressions:
`out/userspace-vm-tcp-timer-run`, `/tmp/lh-tcp-timer-run.log`;
Nix driver `out/userspace-vm-tcp-timer`, build log `/tmp/lh-tcp-timer-build.log`.
The earlier scheduling-only increment passed in 37.22 s:
`out/userspace-vm-tcp-concurrency-run`, `/tmp/lh-tcp-concurrency-run.log`.

The subsequent protocol VM suite passed in 43.63 s:
`out/userspace-vm-tcp-protocol-acceptance-run`,
`/tmp/lh-tcp-protocol-acceptance-run.log`; driver
`out/userspace-vm-tcp-protocol-acceptance`, build log
`/tmp/lh-tcp-protocol-acceptance-build.log`. Both local and peer Linux clients
advertise an observed zero receive window, leave another connection progressing,
then reopen and verify all 257024 echoed bytes plus FIN. Four abortive closes with
unread data each precede a successful replacement connection. A deterministic
raw peer sends a gap and duplicate segment, requires ACKs without application
bytes until the hole closes, verifies the exact ordered echo and exchanges FIN.
The complete scheduler/preemption/DEADLINE and UDP/RPC/memcached regressions pass
in the same run. This adds functional coverage, not a stress campaign.

Two fixture errors were corrected: blocking sendall prevented the backpressure
client from reaching its drain (now a sender thread), and ETH_P_IP capture missed
outgoing Ethernet ACKs (now ETH_P_ALL with explicit IPv4 filtering). Failure logs:
`/tmp/lh-tcp-flow-run.log`, `/tmp/lh-tcp-protocol-fixed-run.log`. No stack or emulator
change was needed. The successful 257024-byte cases do not prove arbitrary
GSO/GRO handling; the ingress still accepts only MTU-sized packets.

The final host checks add actual socket exhaustion/reuse, TX reservation and
commit rejection/recovery, concurrent same-PCB duplicate input, and listener
accept/close races. A real packet-output callback holds the PCB lock while a due
retransmission dispatcher enters; the same dispatch must wait locally and complete
after release without another packet. A subsequent retransmission holds the PCB
lock while three workers race close: none may finish before release, exactly one
wins, and the peer completes FIN. The fixture first observes an actual wire
retransmission to distinguish its RTO from an earlier emptied SYN-cache timer;
the initial fixture failure is preserved in
`/tmp/lh-tcp-pcb-earlier-callout-failure.log`.

The adapter unit test links the real runtime adapter with controlled snapshots
and control ioctls. It verifies earlier mutation racing an older publication,
stale acknowledgement, ETIME retry, expiry at receive entry and no timer sweep
on NACK. It is publication-unit evidence, not a substitute for the real-stack VM.
Final normal four-worker/adapter checks passed in `out/tcp-phase2-final`,
`/tmp/lh-tcp-phase2-final.log`; ASan/UBSan in `out/tcp-phase2-sanitized`,
`/tmp/lh-tcp-phase2-sanitized.log`; one-worker protocol/resource checks in
`out/tcp-phase2-single`, `/tmp/lh-tcp-phase2-single.log`. Nix check logs contain
TCP_HOST_CONCURRENCY_PASS, TCP_PROTOCOL_PASS and TCP_RUNTIME_PUBLICATION_PASS
(normal/sanitized), and TCP_PROTOCOL_PASS (one worker).

Final pinned VM acceptance passed in 43.81 s:
`out/userspace-vm-tcp-phase2-final-run`, `/tmp/lh-tcp-phase2-vm-run.log`;
driver `out/userspace-vm-tcp-phase2-final`, build log
`/tmp/lh-tcp-phase2-vm-build.log`. It additionally exhausts actual stack sockets
inside the VM, reclaims them and starts the normal echo service. The capacity
comes from the TCP package's Nix metadata. Both Linux peers, reordering and all
four-worker/preemption/timer checks pass, as do the phase-one regressions.

## Phase-two completion boundary

The requested reasonably working shared four-worker TCP implementation is
complete within the supported IPv4 server interface. No stress campaign was run.
The branch must remain unmerged until hardware DEADLINE exists.

| Requirement | Evidence |
| --- | --- |
| Minimal pristine FreeBSD import, optional source-owned Nix component | Package manifest check, source/headers/archive builds; VM recipe explicitly selects runtime plus TCP |
| Actual transport lifetimes, per-worker direct TX | Raw transport ownership rejection fixture; all TCP VM traffic uses the shared RX/TX adapter, no production staging queue |
| Normal peers and protocol behavior | Both Linux guests: handshake, bidirectional streams, ACKs, zero-window recovery, FIN and reset recovery; raw peer: gap/duplicate reassembly |
| Four-worker concurrency and connection lifetime | VM requires all four worker IDs; independent reply during a busy handler; host same-PCB input, accept/close and timer/close negative tests |
| Capacity and rejection recovery | Actual socket exhaustion/reuse in host and VM; host callback TX admission/commit rejection; substrate allocation and mbuf ownership checks |
| Timer locking and publication | Real PCB contention/local retry; stuck-lock watchdog subprocess; adapter publication-unit races; VM sticky/generation/lead-time DEADLINE checks |
| Full preemption with live TCP connections | Two applications alternate bursts, each observed with zero assigned/reserved workers, and resume original streams |
| Timer-only progress after full preemption | ACK filter plus packet capture requires new DEADLINE activation and retransmitted sequence/payload before new application data |
| Donor policy and existing applications | VM protected-donor deferral and eligible-donor activation; UDP, RPC timeout and UDP memcached regressions |

Later work remains intentionally outside this phase: phase 3 ports our memcached
TCP support alongside UDP; phase 4 implements hardware DEADLINE/raw TCP steering,
cleans up reclaim rules and validates TCP/memcached on Enzian. Add a deterministically
gated exact-watermark donor fixture with that policy work; current acceptance
checks at-or-below eligibility and above-watermark protection. Active-open
runtime ephemeral-port steering, IPv6, IP fragmentation/options and whole-domain
recovery are outside the current interface. No real hardware execution occurred.

F-Stack's local reference revision `813b82016c5cebfcc545cb44fbbc19dd8d00e99d`
has no-op locks, immediate epoch reclamation and TLS callout ownership that
ignores the target CPU. These are unsuitable implementations to reuse, not a
major blocker to the intended port. Do not replace the current design with
per-worker stacks, a global stack lock or a helper TCP thread.

Whole-domain recovery remains unsupported: failed domains retain resources
until guest restart. No Enzian execution or hardware validation was added.
