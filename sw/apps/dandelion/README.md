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
`server_tests` harness and snapshot's prebuilt AArch64 function fixtures. The
Rust/C ABI assertions compile for the supported 64-bit layout. The Python check
independently calculates fixture answers; it does not execute Dandelion.

For the later hardware campaign, set `PROCESS_WORKER_PATH` to the packaged
`bin/mmu_worker`, and `DANDELION_LAUBERHRON_CONFIG` (the upstream spelling) to a
configuration whose `daddr` equals the configured NIC IPv4 address. The default
is `192.168.129.129`; composition edges use internal FPGA loopback. Run the
packaged test harness from the peer with the environment described in its
`config.sh`, adjusting `LAUBERHORN_IP`, `DANDELION_SERVER` and
`DANDELION_TEST_BINARY_PATH` to the deployment. Run serially because tests share
function/registry names:

```sh
server_tests --test-threads=1
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
