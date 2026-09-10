# Nested RPC runtime integration staging

Branch: `feat/nested-rpc`, started from upstream `0cb3600` on 2026-09-08.
The initial build-only stopping point is complete. The maintainer subsequently
authorized hardware validation using the upstream agent-accessible Enzian
workflow, merged from `ed1b45b` in `5cb21f4`. See
[recorded hardware results](../development/hardware-test-results.md).

## Imported scope

Runtime and public headers come from Sven Glinz's platform commit
`9e2518140ecf4423f0f01b8caded4ad1f708cf62`, recorded separately in `9c3feda`.
The import adds codecs, async calls, futures, callbacks, a pending-call table,
and a cooperative fiber scheduler. `sw/rt/{lauberhorn,worker,rpc}.c` replace
`sw/rt/eci.c`. The add/mul microbenchmark uses the new handler and XDR adapter
API with inline execution and borrowed thread-local result storage.

The mock kernel, student demos/benchmarks, Dandelion submodule, build scripts,
and dependency/deployment changes are excluded. Upstream `deployFs` still
packages the microbenchmark and real kernel module with the runtime closure.
Build with `nix build .#deployFs -L`; the out-of-tree build example is
`nix build .#dummy-app-build-with-nix -L`.

## Hardware/software boundary

Production RX/TX still use `core_eci_rx` / `core_eci_tx` and 2F2F cache-line
message passing. The runtime includes the real `sw/kmod/ioctl.h`; ioctls are
only used for existing service control and shutdown. No fast-path RX/TX
ioctls or software-mock descriptor fields are imported.

`lauberhorn_pkt_desc_t` is an in-memory software representation, not the raw
cache-line format. `sw/core/eci/core.h` packs/unpacks the generated Mackerel
`host_ctrl_info_*` layouts defined by
`hw/src/lauberhorn/host/eci/EciHost{Rx,Tx}CtrlInfo.scala`. Normal inline RPC
payloads occupy 48 bytes; nested-call TX has 24 inline bytes alongside PID,
cookie and wire-order endpoint/RPC fields. The current import changes neither
this transport/header code nor RTL, cache-line layout, barriers or kernel
mappings. Future ABI changes must update both sides and regenerate collateral.

The student's outgoing-call setup is not yet reconciled with hardware PID,
cookie, source-port configuration and byte-order requirements. A successful
software build does not establish a nested hardware round trip.

## Deferred work before merge

- Correct coalesced fiber wakeups and pending-slot/result ownership.
- Initialize fiber callback handlers and define pool-exhaustion behavior.
- Handle codec allocation/serialization failures, bound TX lengths and repair
  XDR object initialization and capacity handling.
- Reconcile the real service-deregistration ioctl argument and service IDs,
  worker shutdown/cleanup, cancellation and timeout progress on blocking RX.
- Restore upstream per-request CSV tracing. The imported runtime currently
  ignores `LAUBERHORN_SERVER_TRACE_CSV`; the existing microbenchmark CLI retains
  its optional trace path for the later restoration.
- Wire the hardware nested-call metadata and reply listen port, and address
  hardware in-flight-table capacity/acceptance and timeout retirement.
- Repair generated accessors for the nested descriptor's unaligned 32-bit
  fields. The matched header truncates the destination to 16 bits; the
  [hardware results](../development/hardware-test-results.md) include a
  host-side reproduction and the resulting bad ARP destination. Change the
  generator or coordinate a layout change with RTL, then regenerate collateral.
- Complete nested-call validation with the Enzian test routine, then merge into
  master. Dandelion remains an external application snapshot; initial hardware
  probes do not import it into this repository or establish composition support.

This is an integration staging branch, not a merge-ready runtime. Hardware
results apply only to the cases explicitly recorded. Earlier master-runtime
descriptions in the topic docs are historical
until these items are resolved. See [nested RPC design](nested-rpc.md) and
[Dandelion provenance](../research/dandelion.md).
