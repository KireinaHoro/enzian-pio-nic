# Nested RPC runtime integration

Integrated from `feat/nested-rpc`, started at upstream `0cb3600` on 2026-09-08.
Merge to master approved by the maintainer on 2026-09-23.
The initial build-only stopping point is complete. The maintainer subsequently
authorized hardware validation using the upstream agent-accessible Enzian
workflow, merged from `ed1b45b` in `5cb21f4`. See
[recorded hardware results](../../agents/runtime-applications.md#historical-bundled-nested-rpc-validation).

## Imported scope

Runtime and public headers come from Sven Glinz's platform commit
`9e2518140ecf4423f0f01b8caded4ad1f708cf62`, recorded separately in `9c3feda`.
The initial import added codecs, async calls, futures, callbacks, a pending-call table,
and a cooperative fiber scheduler. `sw/rt/{lauberhorn,worker,rpc}.c` replace
`sw/rt/eci.c`. The add/mul microbenchmark uses the new handler and XDR adapter
API with inline execution and borrowed thread-local result storage. Subsequent
integration removed callback calls in favor of the generic reply/fail/defer/forward
contract, retaining futures for nested calls from deferred fibers.

The mock kernel, student demos/benchmarks, Dandelion submodule, build scripts,
and dependency/deployment changes are excluded. Upstream `deployFs` still
packages the microbenchmark and real kernel module with the runtime closure.
Build with `nix build .#deployFs -L`; the application-contract example is
`nix build .#dummy-app-build-with-nix -L`.

## Hardware/software boundary

Production RX/TX still use `core_eci_rx` / `core_eci_tx` and 2F2F cache-line
message passing. The runtime includes the real `sw/kmod/ioctl.h`; ioctls are
used for service/endpoint control, cancellation, deadline activation and shutdown. No fast-path RX/TX
ioctls or software-mock descriptor fields are imported.

`lauberhorn_pkt_desc_t` is an in-memory software representation, not the raw
cache-line format. `sw/core/eci/core.h` packs/unpacks the generated Mackerel
`host_ctrl_info_*` layouts defined by
`hw/src/lauberhorn/host/eci/EciHost{Rx,Tx}CtrlInfo.scala`. Normal inline RPC
payloads occupy 48 bytes; nested-call TX has 24 inline bytes alongside PID,
cookie and host-order endpoint/RPC fields. Integration changed the coupled
RTL/header/kernel/runtime contract; deployments must use matching generated
collateral. Wire byte-order conversion occurs in hardware.

Registered endpoints supply hardware PID, cookie, reserved source ports and FPGA
reply listeners. Remote and internal-loopback calls, futures, forwarding,
admission/error paths and lifecycle recovery have passed the recorded Enzian
campaign. See the [endpoint contract](nested-rpc-endpoints.md) and
[hardware evidence](../../agents/runtime-applications.md#historical-bundled-nested-rpc-validation).

## Follow-up work after merge

The [maintainer's merge disposition](nested-rpc-blockers.md#merge-disposition--2026-09-23)
is authoritative. Timing improvement proceeds separately; the cross-process
port/XID late-reply race remains a test to run. Historical startup failures are
considered likely incomplete ECI link bring-ups, now gated by the updated agent
workflow, rather than a remaining merge blocker. Dandelion validation is reserved
until the planned scheduler/critical-section design changes land.

Dandelion remains an external pinned snapshot with tracked integration patches
and flake application/test/deployment outputs. Initial hardware probes and the
new software builds do not establish composition support.

Hardware results apply only to the cases explicitly recorded. Earlier
synchronous-runtime descriptions retained in the topic docs are historical. See [nested RPC design](nested-rpc.md) and
[Dandelion provenance](../research/dandelion.md).
