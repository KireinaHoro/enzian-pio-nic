# Caladan AArch64 port differences

The [CS-A64 baseline](caladan-enzian.md) runs Caladan's **mlx5 directpath with the simple scheduling policy**
on Enzian's ThunderX-1 and ConnectX-5. Applications retain direct access to NIC
queues; the IOKernel retains scheduling and core allocation. It does not replace
directpath with centralized packet processing. The comparison below is with
x86 Caladan using the same policy and signal-based preemption, not IAS or UINTR.

The runtime structure is preserved, but its machine-level costs are not
identical. The main additional costs are per-thread addressing and preemption
counter updates. Idle waiting also makes a different power/performance tradeoff.

| Mechanism | x86 implementation | AArch64 implementation and cost |
| --- | --- | --- |
| Per-thread reads | One GS-relative load. | A fixed-width assembly helper reads TPIDR_EL0, loads the Caladan allocation offset from libc TLS, then loads the value. This adds a call/return and address-generation instructions. |
| Preemption counter | GS-relative memory add/subtract; enable tests the resulting flags. | Inline LL/SC update on enable, testing the decrement result directly; disable uses an assembly helper. More instructions and possible exclusive-store retries, but no syscall or outlined atomic dispatch. |
| Context switching | Assembly saves/restores the x86 callee-saved registers. | Assembly saves/restores the AAPCS64 callee-saved integer registers and low halves of v8–v15 with paired loads/stores. The larger saved state follows the ABI. Preemption enable stays inline; only pending requests call C. |
| Signal preemption | Signals interrupt user threads and enter the scheduler. | Same mechanism, with an assembly counter increment and PC checks to make nested signals and interrupted TLS reads safe. Signals remain unmasked; no extra signal-unmask syscall. |
| Memory ordering | x86 ordering permits ordinary loads/stores and compiler barriers for several operations. | Acquire/release operations and explicit barriers enforce CPU and NIC DMA/MMIO ordering. These instructions preserve the same publication/ownership contracts on ARM. |
| Idle core handoff | MONITOR/MWAIT waits for a scheduling request. | Up to 64 acquire polls per idle callback. This keeps the core executing instead of entering monitored sleep, increasing idle CPU/power cost and potentially reducing activation latency in the baseline’s favor. WFE is not substituted directly because this callback runs with IRQs masked and needs interrupt-safe wakeup. |
| Shared RX-buffer layout | DPDK uses 64-byte alignment. | Header and object sizes follow the target DPDK definitions (128-byte alignment on ThunderX). Address reconstruction remains compile-time arithmetic. |
| Hashing and checksums | Hardware CRC32C; word-wide Internet checksum. | Hardware CRC32C through ACLE intrinsics and `-march=armv8-a+crc`; 64-bit Internet checksum accumulation. Neither uses a bitwise software CRC fallback. |

## Why TLS reads need PC restart

TPIDR_EL0 remains owned by libc. A signal can migrate a Caladan user thread to
another pthread between reading its TLS base and loading the value. The signal
handler recognizes that instruction interval and rewrites the saved PC to the
reader's entry, so the address is recomputed on the destination pthread. Once
the value load completes, no restart is needed—just as migration can follow a
completed x86 GS-relative load.

This adds recovery work on an interruption, rather than an exclusive-monitor
probe on every read. Accesses made with preemption disabled need no restart.
Retaining a per-thread pointer still requires preventing migration while using
it; the value-read protocol does not extend a pointer's lifetime.

## Interface differences

Clock reads use CNTVCT rather than the x86 TSC. Ordinary reads are unordered;
the ordered helper adds ISB. Scheduler time conversion requires integral counter
ticks per microsecond, and the ARM `rdtscp` wrapper returns auxiliary CPU ID zero.
Custom per-user-thread libc TLS (`thread_set_fsbase` with a nonzero argument) is
unsupported. These are interface restrictions, separate from instruction costs.

## Masked-SGI wake limitation

The October 20 ThunderX probe masked IRQs (`DAIF=0xc0`), disabled the timer
event stream and waited with `LDAXR/WFE` on an otherwise untouched page.
An asynchronous cross-call SGI sent at about 25 ms did not wake the waiter in
three trials; rescue SEV at about 133 ms did, and the callback ran after IRQ
restoration. Three SEV-only controls also woke at rescue. This bounds the result
to that ThunderX configuration and explains why masked-SGI wakeup is not used
as a replacement for polling. Detailed probe provenance is in the
[port continuation](../../agents/caladan.md#masked-sgi-probe).
