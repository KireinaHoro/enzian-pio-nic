# Lauberhorn Software

The software stack is split into three parts:

## `core`: shared implementations between kernel and user

Common logic shared between the userspace and kernel module will be implemented
here.  Most notably this includes the 2F2F message passing logic that handles
the two flipping control cachelines along with overflow cachelines; this is
used both in userspace (to handle RPC requests) and kernel (to handle bypass
core requests).

**Read request from worker module (RX)** (data)

**Write response to worker module (TX)** (data)

## `uapi`: user-facing APIs (header-only)

User applications, i.e. RPC programs, call into these functions.  Functions
exported here should be backend-agnostic to allow applications to stay
portable.  Main API calls needed are as follows.

Implementation of these functions will sit inside the `rt`, the userspace
runtime library.  These functions should not require `sudo` to call.
Control-path functions will be forwarded to the kernel module through a
user-accessible device file; the kernel module implements access control.
Data-path functions will operate directly on memory mappings created by the
kernel.

A reference application template will call these functions and accept RPC
handler functions to dispatch requests to them; a simple RPC application can
then just use this template directly.  For more complicated applications
where more state and scheduling might be involved, these functions can also
be called directly.

**Start one application** (control)

Update scheduler state to register PID and max number of threads (parallelism)
with the scheduler.

_As in simulation_: `OncRpcSuiteFactory.enableProcess`

**Register a service in an application** (control)

Enable RPC as the next protocol for a given UDP listen port in the UDP decoder.
Register the service's program number, version, process ID, as well as current
PID and userspace handler function pointer with the RPC decoder.

_As in simulation_: `OncRpcSuiteFactory.enableService`

**Destroy application and deregister all services** (control)

Disable and remove all services, listen ports, and the process definition.  In
addition, this should also clear the session table in the RPC encoder.

**Read one request/write one response** (data)

Call the respective function in `core` to acquire encoded RPC message, then
decode message to generate a nested RPC message object to pass to the user
handler function.

## `rt`: runtime library in user-space

Implements all functions exported in `uapi` and `mgmt` headers, for every
backend.  This should be a dynamic library (`liblauberhorn_rt.so`) to
potentially avoid having to recompile applications for different backends.

This will import the functions defined in `core`.

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

In addition to implementing API calls from the userspace, the kernel module:

- handles unexpected packets by forwarding them to a Linux network device
- forwards packets sent by the host stack to the bypass Tx pipeline
- resolves ARP requests and update the neighbor table
- runs timers to retire entries in the neighbor table and RPC session table

TODO: do we want to support loading multiple backends at once?  Device
enumeration, etc. quickly get very complicated

# Build instructions

SW requires some generated files from the HW:
* `devices/lauberhorn_eci_*.dev`
* `../hw/gen/eci/*`

TODO: build instructions -- build everything with CMake (including [kernel
modules](https://gitlab.com/christophacham/cmake-kernel-module))

# Building `rt` and `kmod` for a specific backend

Three backends are planned for Lauberhorn:

- ECI: 2F2F message-passing between NIC and CPU + interrupts
- PCIe: MMIO registers polling + interrupts
- Mock: shared memory polling + POSIX signals (simulator)

# Building a user application

