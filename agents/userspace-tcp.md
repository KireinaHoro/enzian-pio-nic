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

TCP repository commit: `1cc5cf2` on `userspace-tcp-phase-two` (unmerged).
Imported FreeBSD 15.1-RELEASE-p6 at
`95719d8a895bf8170851e9844d9b4092b1d2b6fe`: 13 C files and 235 transitive headers,
plus COPYRIGHT, 2,834,003 bytes total. Compiler dependency files determine the
minimal header closure. `upstream.json` pins each file hash; `maint/import.py`
verifies/restores exactly that manifest. No F-Stack code or full FreeBSD tree
was imported.

`package.nix` builds a non-runnable TCP core plus a host substrate archive.
`port/` implements actual mutex/RW/SX locks, TLS FreeBSD thread/CPU context,
shared atomic counters, epoch/SMR deferred reclamation, and shared callouts.
The queue lock is released before callbacks; associated connection locks
serialize callbacks on the same connection. Contention retains the pending
expiry and original deadline. Notification callbacks must be nonblocking;
actual emulator deadline publication belongs outside stack locks.

Nix validation passed (`out/tcp-host`, `/tmp/lh-tcp-host-build5.log`, derivation
`7k6x8f8ffywg5r21l1pflibnm1z7bn53-lauberhorn-tcp-core-0.1.drv`). The installed
`libexec/tcp-concurrency-test` also passed directly. Its four workers check
shared locking, cross-worker reset/stop/dispatch, overlapping callbacks on
independent connections, close during an unlocked callback, and grace-period
reclamation. Added negative cases explicitly reject concurrent read/write
acquisition of a held connection and concurrent callbacks on that connection.
They check that contention preserves the overdue deadline, then retry delivers
exactly once without a packet or timer reset. A missing test-stage barrier was
fixed after an assertion raced next-stage timer setup. Core disassembly after
the TLS overlays had no GS/CR-register/CLI/STI/INVLPG accesses.

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

Next implement shared process/VNET state, allocation, PCB/socket initialization
and packet I/O, then close the link without dummy success stubs. Import only
needed vanilla helper sources and their dependency closure. Resolve host-libc
symbol collisions when introducing kernel malloc/free/printf (localize the
combined kernel-side relocatable or prefix symbols). Preserve vanilla timer
lock/return-unlocked contracts and audit SMR wait callers against held locks.
Integrate native TCP and domain timer arm/ack/cancel into the emulator, package
the runnable library as the optional runtime companion, and run the phase-two
VM matrix. Timer parking must retain overdue work and avoid lost wakeups;
re-read the deadline after dispatch even when zero callbacks ran. Do not
substitute per-worker stacks, a global stack lock, or Linux TCP sockets.

Whole-domain recovery remains unsupported: failed domains retain resources
until guest restart, and existing nested deadlines do not activate a parked
domain. No Enzian execution or hardware coherence/timing validation was added.
