# CMAC AXI-Lite initialization investigation — October 9

Branch: `fix/cmac-axil-init`. Hardware validation is pending; do not merge or
claim restored board alignment from these component checks.

## Failure mechanism

Before removal in `23b1a134`, `lauberhorn_eci.vhd` instantiated the CMAC
interconnect with `M01_BASE_ADDR=0x200000`, `M01_ADDR_WIDTH=11`. That exposes only
`0x200000–0x2007ff`. `sw/devices/cmac.dev` and the startup code access RS-FEC
correction at `+0x1000` and RX/TX enable at `+0x107c`: both are outside the
aperture. The actual interconnect returns DECERR for these accesses. MAC/version
register access within the first 2 KiB can succeed while FEC programming fails.

[AMD PG203 register descriptions](https://docs.amd.com/r/en-US/pg203-cmac-usplus/Register-Descriptions)
place the FEC controls at those offsets, with RX/TX enable defaulting to zero.
The autonomous no-register design ties FEC enable/correction/indication high.
This establishes a concrete programming-path defect consistent with the reported
failure to align; it does not prove that it was the only board-level cause.

## Correction

Restore the F_MAC0 AXI-Lite port, existing 64-to-32 adapter and two-target
interconnect. Map a full 64 KiB CMAC bank and explicitly pass local offsets to
the CMAC. Use the application clock/reset for register access and retain board
GT polarity. Register mode owns MAC/FEC controls; remove their external ties.
F_MAC1 keeps autonomous control so driver initialization cannot disable the
trace capture link. Static shell and toolkit sources are unchanged.

`init_bypass()` now starts CMAC before publishing a netdev. Startup validates the
version, pulses reset, enables FEC and checks its readback, enables RX with RFI,
waits for alignment, enables TX and clears RFI, then checks both aligned/status
bits in one status snapshot. Waits sleep rather than busy-wait. Each polling
phase is bounded to approximately ten seconds. Failures disable RX/TX and
report FEC/status/block-lock diagnostics. Netdev down does not stop the physical
MAC shared with RPC workers. There is no periodic carrier monitor or automatic
runtime recovery in this change.

The status register is mixed latched-low/latched-high, not a plain live word.
Polling consumes those latches. The restored generic width adapter can read a
neighboring 32-bit word on a 64-bit-side read; comprehensive statistics access
still needs a separate audit. Startup reads status at `+0x204` (upper lane).

## Validation

- Production interconnect simulation: historical 11-bit aperture rejects both
  FEC addresses; new 16-bit aperture routes them; version/status and NIC routes
  work, and the next unmapped bank returns DECERR.
- Production C startup with generated Mackerel accessors and fake MMIO passes
  success, inaccessible FEC, alignment timeout, status timeout, and invalid
  version cases. These tests do not model analog alignment or reset timing.
- Nix cross-build of the kernel module passes (Ubuntu arm64 6.8.0-64 ABI).

Reproduce from the branch with recursive submodules:

```sh
nix develop -c python3 tools/cmac/check-routing.py
nix build .#devHdrs --out-link out/cmac-dev-headers
nix develop -c python3 tools/cmac/check-startup.py out/cmac-dev-headers
nix build .#kmod --out-link out/cmac-kmod
```

Remaining: Vivado project/build, then matching-artifact cold boots with F_MAC0
connected to the correctly configured peer. Require FEC readback, alignment,
status and RPC traffic on at least two boots. Recheck reservations and actual
state, retain tracing through module load, and preserve failed captures before
reset according to the hardware test guide. A successful CI build alone is not
proof of RX alignment or timing closure.
