# Lauberhorn Software

The software stack is split into three parts:

## `core`: shared implementations between kernel and user

Common logic shared between the userspace and kernel module will be implemented
here.  Most notably this includes the 2F2F message passing logic that handles
the two flipping control cachelines along with overflow cachelines; this is
used both in userspace (to handle RPC requests) and kernel (to handle bypass
core requests).

**Read request from worker module (RX)** (data): `core_eci_rx`

**Write response to worker module (TX)** (data): `core_eci_tx`

## `include/lauberhorn.h`: user-facing APIs (header-only)

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

**Start one application** (control): `lauberhorn_init`

Register an application to record the process group ID with the scheduler.  Worker threads are not created at this point yet.

_As in simulation_: `OncRpcSuiteFactory.enableProcess`

**Register a service in an application** (control): `lauberhorn_reg_srv`

Enable RPC as the next protocol for a given UDP listen port in the UDP decoder.
Register the service's program number, version, process ID, as well as current
PID and userspace handler function pointer with the RPC decoder.

This also registers a _schema_ that describes the input and output
messages of this service.  The `schema` argument should provide enough
information to the marshaling/unmarshaling routines in the runtime.

_As in simulation_: `OncRpcSuiteFactory.enableService`

**Create one worker thread** (control): `lauberhorn_create_worker`

Create a worker thread to run registered RPC handlers for incoming
requests.  The worker thread will run a tight loop to:
- read incoming request from the NIC
- unmarshal the request message
- call the handler
- marshal the response message
- write response to the NIC

**Destroy application and deregister all services** (control): `lauberhorn_fini`

Disable and remove all services, listen ports, and the process definition.  In
addition, this should also clear the session table in the RPC encoder.

## `rt`: runtime library in user-space

Implements all functions exported in `uapi` and `mgmt` headers, for every
backend.  This should be a dynamic library (`liblauberhorn_rt.so`) to
potentially avoid having to recompile applications for different backends.

This will import the functions defined in `core`.

## `kmod`: kernel module for control interfaces

Each backend has a separate kernel module.  They should implement one character device, `/dev/lauberhorn`, to implement the functions forwarded
by `rt`.  The character device installs mappings of the datapath
in user applications and enforces isolation.  It programs the raw hardware
registers.

In addition to implementing API calls from the userspace, the kernel module also implements a NAPI-based network device `lauberhorn0` that
handles bypass packets, including ARP.

# Build instructions

We implement cross-compiling with Nix.

```console
$ nix build ".#lauberhorn-kmod" -L
$ file result/lauberhorn.ko
result/lauberhorn.ko: ELF 64-bit LSB relocatable, ARM aarch64, version 1 (SYSV), BuildID[sha1]=[...], with debug_info, not stripped
$ nix build ".#lauberhorn-rt" -L
$ file result/liblauberhorn_eci.so
result/liblauberhorn.so: ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, with debug_info, not stripped
```