# Lauberhorn Software

The software stack is split into three parts:

## `uapi`: user-facing APIs (header-only)

User applications, i.e. RPC programs, call into these functions.  Functions
exported here should be backend-agnostic to allow applications to stay
portable.

Implementation of these functions will sit inside the `rt`.  These functions
should not require `sudo` to call.  Control-path functions will be forwarded to
the kernel module through a user-accessible device file; the kernel module
implements access control.  Data-path functions will operate directly on memory
mappings created by the kernel.

## `rt`: runtime library in user-space

Implements all functions exported in `uapi` and `mgmt` headers, for every
backend.  This should be a dynamic library (`liblauberhorn_rt.so`) to
potentially avoid having to recompile applications for different backends.

## `mgmt`: management APIs

Administrative APIs that need to be invoked with `sudo` -- these are not
intended to be called by most applications.  Includes changes to global
configurations, access to raw registers, and debug information / statistics.
Most of these should be wrappers to `ioctl`s to a privileged device file,
provided by the kernel module.

Implementation of these functions will sit inside separate libraries for each
backend (e.g. `liblauberhorn_mgmt_eci.so`).  Separate management utilities may
be built -- these might have to link against `mgmt` libraries for multiple
backends, so functions in the `mgmt` APIs should be prefixed by the backend (
e.g. `lauberhorn_pcie_set_block_cycles`).

## `kmod`: kernel module for control interfaces

Each backend has a separate kernel module.  They should implement two device
files to take `ioctl`s from the user space:

- `/dev/lauberhorn_user`: unprivileged requests from `rt`.  The kernel module
  translates requests such as "install one service" to actual register writes.
- `/dev/lauberhorn_mgmt`: privileged requests from `mgmt`.  Including but not
  limited to raw register accesses.

TODO: do we want to support loading multiple backends at once?  Device
enumeration, etc. quickly get very complicated

# Build instructions

TODO: build instructions -- build everything with CMake (including [kernel
modules](https://gitlab.com/christophacham/cmake-kernel-module))

# Building `rt` and `kmod` for a specific backend

Three backends are planned for Lauberhorn:

- ECI: 2F2F message-passing between NIC and CPU + interrupts
- PCIe: MMIO registers polling + interrupts
- Mock: shared memory polling + POSIX signals (simulator)

# Building a user application

