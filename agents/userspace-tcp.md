# Userspace TCP phase one

Branch: `userspace-tcp-phase-one`, based on `46048cfc`.
Scope: [phase one](../docs/proposals/userspace-tcp.md#phase-1-shared-kernel-module-and-runtime-with-vm-emulation).
Keep build selection, CPU flags, VM configuration and kernel options in Nix.
Stop at major blockers; no hardware deployment or TCP stack import is part of
this phase.

## Current implementation

- Runtime CPU flags are owned by Nix. The existing AArch64 package retains
  ThunderX tuning; `dev.x86_64-linux.software.runtimeNative` compiles the full
  shared runtime without tracing. It still uses ECI, not emulated transport.
- `sw/kmod/activation_backend.h` separates committed device activation, idle
  notification and yield-request reads from `sched.c`. `activation_eci.c`
  implements those operations. Shared registration, thread claiming, affinity
  installation and yield fencing are unchanged. This is only the activation
  boundary, not a complete backend abstraction.

## VM execution prerequisite

The host exposes Intel VT-x, has `kvm_intel`/`kvm` loaded, and provides
`/dev/kvm` outside the agent sandbox. An unsandboxed open and KVM_GET_API_VERSION
ioctl succeeded with API version 12 on October 23. The sandbox hides the device;
the earlier inference that the host lacked KVM was incorrect. No alternate host
or TCG choice is needed. Run VM validation with the appropriate sandbox
escalation. Nix currently omits `kvm` from its advertised system features; check
and configure the Nix VM test execution path without confusing that declaration
with hardware availability. No VM acceptance has run.

## Validation

- Before changes, Nix built the ECI kernel module and AArch64 runtime.
- After moving CPU flags, Nix built both native and AArch64 runtimes;
  command output is in `/tmp/lh-phase-one-build.log` (temporary evidence).
- Nix built the module after activation extraction and passed `runtime-tests`
  (including ownership, activation, RPC, UDP and sanitizer checks);
  `/tmp/lh-phase-one-checks.log`.

## Next implementation

Define a compile-time backend selection shared by module and runtime in Nix.
Separate registration/control programming, worker setup, mapping, transport and
failure fencing from ECI while preserving the existing shared lifecycle.
Add bounded mapped RX/TX storage and ioctl operations with activation identity
and borrow/reservation checks. Implement device work in a kthread, with network
hooks only submitting work, and preserve unmatched host traffic. Add Nix VM
packaging and acceptance for native UDP, ONC-RPC, nested RPC, bypass, multi-worker
handoff and exhaustion. Native TCP and domain timer APIs belong to phase two.

Current lifecycle intentionally retains failed domains and endpoint reservations;
whole-domain recovery remains unsupported. Existing nested RPC deadlines do not
activate parked domains. Preserve these limitations explicitly rather than
silently claiming the emulator establishes a stronger hardware contract.
