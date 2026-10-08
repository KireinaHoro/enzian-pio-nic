# Pinned Dandelion integration

The flake pins the student's `69c3088f8f5333eab653ed5c9bd2f6da8f7f944a`
snapshot. `package.nix` applies the tracked patch to that source. The patch
updates C/Rust layouts, stores the context and shared endpoint at stable
addresses, retains address/codec ownership, initializes the endpoint before
workers, and uses the executing RPC worker's actual Linux CPU for process
execution. Logical engine slots remain indices into the engine array.

```sh
nix build .#runtimeTests .#dandelion .#dandelionDeployFs
python3 sw/apps/dandelion/check-fixture-results.py
```

`dandelionDeployFs` includes the matching runtime, kernel, application,
`server-tests` harness and snapshot's prebuilt AArch64 function fixtures. The
Rust/C ABI assertions compile for the supported 64-bit layout. The Python check
independently calculates fixture answers; it does not execute Dandelion.

The package accepts `lauberhornRuntime` through `platform.callPackage`, installs
executables in `bin`, assets in `share/dandelion`, and defaults to
`dandelion_lauberhorn`. Its wrappers default `PROCESS_WORKER_PATH` to the installed
worker and `DANDELION_LAUBERHORN_CONFIG` to the installed configuration. Both can
be overridden. For the later hardware campaign, set the latter to a writable-copy
configuration whose `daddr` equals the configured NIC IPv4 address. The default
is `192.168.129.129`; composition edges use internal FPGA loopback. Run the
packaged test harness from the peer with the environment described in its
`share/dandelion-tests/config.sh`, adjusting `LAUBERHORN_IP`, `DANDELION_SERVER` and
`DANDELION_TEST_BINARY_PATH` to the deployment if overriding the packaged default.
The test package is `dandelion.interactiveTests.smoke`; the image explicitly
selects it as `dandelion-tests` under `/nix/store/lauberhorn/apps`. Run serially
because tests share function/registry names:

```sh
lh-test run dandelion-tests -- --test-threads=1
```

The harness contains matmul, matmac, the matmul/matmac DAG, keyed sharding and
nested compositions. The nested case also runs four concurrent invocations
with different expected answers. Sharding verifies the full output multiset,
including multiplicity. Registry HTTP traffic targets the DUT, while RPCs are
sent by the second Enzian. A successful build is not a passing hardware test.

Endpoint teardown requires joined workers and no unresolved requests. Failed
teardown retains Rust storage; the kernel disables and quarantines the listener
on close. B4 shutdown/cancellation/reset recovery remains open. See the
[endpoint contract](../../../docs/software/nested-rpc-endpoints.md) and
[blocker queue](../../../docs/software/nested-rpc-blockers.md).

Set `DANDELION_STATE_DIR` to a dedicated writable function registry directory
(default `/tmp/dandelion_server`); server cleanup removes that directory. Redirect
logs outside the store. Packaged immutable assets do not depend on the working
directory. Image construction uses the shared manifest and helper contract.

Packaging validation on 2026-09-16 after merging master `2890f3c`: the native
runtime tests, installed-runtime demo, Dandelion application/test harness, and
both deployment images built successfully. Interactive/workflow helper checks
were satisfied from the Nix cache. Extracted images passed `check-image.py`
(3,498 microbenchmark and 3,893 Dandelion links), target ELF dependency and kernel
vermagic checks; wrapper defaults, fixture asset paths and application source
identities were inspected. The fixture reference calculation passed. Logs and
extracted-image reports are under ignored `out/nix-contract-validation/`.
These were dirty-tree packaging builds, not deployable revision evidence; no
Vivado implementation or board campaign ran.


September 17 API migration: patch `0003-explicit-request-outcomes.patch` makes
registry resolution precede fiber admission. Atomic functions return a reply
inline; compositions return a deferred function plus owned, resolved registry
state. The wire procedure/envelope is unchanged. C/Rust outcome layout assertions
and explicit codec output capacities accompany the migration. `dandelion.codecTests`
runs the bounded encoder regression under the target emulator; it is separate
from the board-dependent interactive suite.

The student fixture adapter `tools/enzian/student-request-outcomes.patch` replaces
the printing callback with a third future. Its response now waits for all three
children, and partial submission failure drains those already admitted. The sum
still uses only the first two children. Historical runtime revisions continue to
use the historical fixture API through `student_build.nix`.

September 17 validation: the bounded Rust codec test executed under target
emulation and passed. The application, smoke harness, runtime/kernel and both
deployment images built; extracted image closure/ELF checks passed (3,892 links
and four ELF entries for Dandelion). Native runtime tests and three nested RTL
simulations passed, as did the server/bypass simulation suites. Logs are under
ignored `out/b3-20260917/`. These dirty-tree builds do not establish a deployable
checkpoint pair; no board campaign or composition execution on hardware ran.

The request-context follow-up changes the handler's third argument from a wire
XID to an opaque 32-bit context token. Dandelion ignores it; client messages and
BJSON envelopes are unchanged. Hardware retains the wire XID and UDP reply
address. Possible client-metadata query APIs are documented in
[reply contexts](../../../docs/software/reply-contexts.md), not implemented.

October 8 resumption: HOST_WAKE is on hold and the existing application adapter
is the active validation target. The peer test client now accepts
`LAUBERHORN_CLIENT_IP` (default `0.0.0.0`, letting routing select its source)
instead of binding to the student's fixed `10.0.0.5`. Set it to the peer's
data-interface IPv4 address when management and data routes coexist. HTTP
registration/control calls have a 5-second connect timeout and 30-second total
timeout; UDP replies retain their 5-second timeout. These limits bound failed
fixtures, not application execution policy. Run the suite serially, including
its internally concurrent nested requests.
