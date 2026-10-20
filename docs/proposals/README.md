# Open proposals

Reusable mechanism designs and alternatives, independent of a particular
application. Application-specific plans belong in [applications](../applications/README.md)
and cross-reference the mechanisms they could use. Inclusion here does not authorize
implementation or establish acceptance; each proposal states its scope.

- [Service execution and resident applications](service-execution.md): event-driven service lifetime; raft-rs alternative.
- [TX completion extensions](tx-completion.md): operation results and completion boundaries.
- [Lookup scaling and software spill](lookup-spill.md): hot tables, ownership and slow paths.
- [Go runtime integration toward etcd](go-etcd.md): selected design direction, including HOST_WORK.
- [TCP/Protobuf integration toward gRPC](grpc.md): follow-on protocol scope.
- [RX-return response transmission](rx-return-responses.md): coupled response submission and receive.

- [Request-context metadata](request-context-metadata.md): application queries and TCP context extensions.

See [system architecture](../architecture/overview.md) for implemented contracts
and [paper goals](../research/paper-goals.md) for evaluation objectives.
