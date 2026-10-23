# Userspace TCP

Branches: `userspace-tcp-phase-two` in platform and `../lauberhorn-tcp`.
Platform phase two starts from `b9e0ab05` on `userspace-tcp-phase-one`.
Scope: [phase two](../docs/proposals/userspace-tcp.md#phase-2-shared-freebsd-tcp-library-and-validation-in-the-vm).
Keep build selection, CPU flags, VM configuration and kernel options in Nix.
Push incremental commits to the private GitLab remotes with `-o ci.skip`.
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

## Phase-two checkpoint and host-port work

TCP repository commit: `0ce3492` on `userspace-tcp-phase-two` (unmerged).
Imported FreeBSD 15.1-RELEASE-p6 at
`95719d8a895bf8170851e9844d9b4092b1d2b6fe`: 22 C files and 266 transitive headers,
plus COPYRIGHT. Compiler dependency files determine the
minimal header closure. `upstream.json` pins each file hash; `maint/import.py`
verifies/restores exactly that manifest. No F-Stack code or full FreeBSD tree
was imported.

`package.nix` builds a non-runnable TCP core plus a host substrate archive.
Selected vanilla helpers now include in_pcb, uipc_socket, uipc_sockbuf,
uipc_mbuf/uipc_mbuf2, subr_hash, jenkins_hash, SipHash and IPv4 checksum.
Only their 31 additional compiler-used headers were imported; the ignored
`out/tcp-import/dependencies.nix` used upstream headers temporarily to discover
the closure. The self-contained package does not depend on that checkout.

`port/` implements actual mutex/RW/SX locks, TLS FreeBSD thread/CPU context,
shared atomic counters, deferred reclamation, bounded malloc/UMA adapters,
mapped mbufs and shared callouts.
The queue lock is released before callbacks; associated connection locks
serialize callbacks on the same connection. Contention retains the pending
expiry and original deadline. Notification callbacks must be nonblocking;
actual emulator deadline publication belongs outside stack locks.

Nix validation passed (`out/tcp-host`, `/tmp/lh-tcp-mbuf-build2.log`, derivation
`g841jihlcy4gzhkvjja4qbx0f2xn1fkj-lauberhorn-tcp-core-0.1.drv`). The installed
`libexec/tcp-concurrency-test` also passed directly. Its four workers check
shared locking, cross-worker reset/stop/dispatch, overlapping callbacks on
independent connections, close during an unlocked callback, and deferred
reclamation. Negative cases reject concurrent read/write acquisition of a held
connection and concurrent callbacks on that connection. They check contention
preserves the overdue deadline, then retry delivers exactly once without a
packet or timer reset.

The PCB source exposed a lock-order requirement: `in_pcbinshash()` can wait
for SMR with PCB/hash locks held; `_inp_smr_lock()` leaves SMR before blocking
on the PCB lock. Network epoch readers can block on locks. The original shared
epoch/SMR grace domain was therefore replaced with separate reader classes.
Synchronous SMR waits now observe only SMR readers; deferred frees wait for both
SMR and network readers, retaining host callout lifetime protection. A regression
holds a network reader while another worker synchronizes SMR under a connection
lock, proving those classes do not deadlock each other. Another test retires a
bounded UMA object under an active SMR reader and verifies destruction/capacity
release only after that reader exits. Mbuf cloning tests use vanilla m_copym and
m_copydata: freeing the original keeps the cluster readable via its clone and
final release returns both headers. Full archive disassembly contains no
GS/CR-register/CLI/STI/INVLPG accesses (`out/tcp-import/allocation-disassembly.txt`).

Nix combines kernel objects then localizes their defined non-`lh_` symbols
before linking the native host-libc boundary; this separates kernel malloc/free
ABIs from libc. Function/data sections and link GC discard unused generic kernel
helpers in host fixtures. `required-host-symbols.txt` now reports unresolved
symbols after combining core and host objects (before GC), rather than the
original core-only list. The full stack still has unresolved interfaces.

These tests cover host primitives, not TCP connections or VM wakeup. No TCP
emulator integration or TCP VM acceptance has run. Phase one remains the only
VM functional acceptance evidence. The user requires a reasonably working
four-worker shared TCP implementation before stopping; no stress campaign is
required. In particular, verify a contended overdue timer wakes/runs after lock
release even with no new packets; pending metadata alone cannot prove liveness.

The available F-Stack scaffolding cannot
safely support timer reset/stop from arbitrary workers on shared connections.
At F-Stack revision `813b82016c5cebfcc545cb44fbbc19dd8d00e99d`,
`lib/ff_kern_timeout.c:191-193` puts `cc_cpu` in TLS and defines `CC_CPU(cpu)` as
the caller's wheel, ignoring the callout owner used by `callout_lock()`.
Its mutex/RW-lock overrides are no-ops, and `ff_subr_epoch.c` immediately calls
reclamation callbacks. Merely replacing lock macros would leave timer-wheel
ownership and reader lifetime unsafe. The proposal already requires working
replacements; this finding blocks reuse of those implementations, not the
feasibility of the overall design. The initial stop overstated this as a major
blocker: it is planned host-port implementation work, with no external
dependency or design decision currently preventing progress.

Next implement shared process/credential and protocol-domain state, ordered
SYSINIT execution, socket initialization/public API, monotonic clock updates,
and packet I/O. Close the link without dummy success stubs; explicit failure
boundaries currently reject physical-page/sendfile mbufs and NIC offload tags.
Mapped mbufs are bounded by Nix `mbufs`, but the socket send path may need more
mapped allocation helpers. Keep generic unsupported syscall/splice/kqueue paths
unreachable through the public API and fail them explicitly if reached. Import
only needed vanilla helper sources and their dependency closure. Preserve
vanilla timer lock/return-unlocked contracts.
Integrate native TCP and domain timer arm/ack/cancel into the emulator, package
the runnable library as the optional runtime companion, and run the phase-two
VM matrix. Timer parking must retain overdue work and avoid lost wakeups;
re-read the deadline after dispatch even when zero callbacks ran. Do not
substitute per-worker stacks, a global stack lock, or Linux TCP sockets.

Whole-domain recovery remains unsupported: failed domains retain resources
until guest restart, and existing nested deadlines do not activate a parked
domain. No Enzian execution or hardware coherence/timing validation was added.
