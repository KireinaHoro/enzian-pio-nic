# Raft and etcd

**Planned application; not implemented.** The selected direction is a Go Raft
embedding followed by etcd, using the proposed [Go runtime and goroutine
scheduler](../proposals/go-runtime.md). The earlier
[raft-rs service design](../proposals/service-execution.md) remains an alternative
design reference, not the selected implementation path.

## Dependencies and staging

First establish native Go RPC receive/reply, concurrent goroutines and nested
calls. This depends on [HOST_WORK](../proposals/host-work.md), host-driven
allocation and targeted maintenance support. Real memcached hash expansion is
a separate acceptance case for those shared mechanisms; it does not validate
Raft or etcd.

Then embed the [Go Raft core](https://github.com/etcd-io/raft), which leaves
network and disk I/O to its embedding. Port etcd and its client interface after
the embedding's transport and persistence semantics are defined. Go null-RPC
success alone does not establish Raft correctness or etcd compatibility.

## Application integration decisions

- Define native RPC adapters for peer messages, ownership and bounded admission.
  Etcd's [HTTP peer transport](https://github.com/etcd-io/etcd/tree/main/server/etcdserver/api/rafthttp)
  and its gRPC client interface require separate adapter work. Native Go RPC is
  not standard-library `net/rpc` or grpc-go compatibility.
- Specify durable storage and ordering of persistence relative to protocol
  messages and acknowledgements. The runtime proposal supplies no durability
  guarantee.
- Design snapshot and bulk transfer, compatibility and failure semantics before
  claiming a complete etcd service. Complete kill/exit recovery remains outside
  the initial runtime stage.

Keep worker-core–endpoint affinity and domain ownership checks from the
[implemented scheduling contract](../software/scheduling-control-interface.md).
The application must distinguish admitted requests, active native operations and
waiting goroutines; goroutine count does not increase endpoint or RPC-credit
capacity. Use [paper goals](../research/paper-goals.md) for evaluation scope and
[validation](../development/validation.md) for the build-to-board workflow.
