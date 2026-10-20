# Lauberhorn

Lauberhorn is an OS-integrated smart NIC for low-latency RPC, prototyped on
Enzian with a coherent ECI datapath, FPGA request scheduling, Linux worker
management and a userspace runtime.

Start with the [system map](docs/architecture/overview.md).

## Using and developing Lauberhorn

- [Development setup and build commands](docs/development/workflow.md)
- [Nix packages and deployment products](docs/development/nix.md)
- [Validation: local checks and board testing](docs/development/validation.md)
- [Formal models and verification](docs/formal/README.md)
- [CI and dependency maintenance](docs/development/ci.md)
- [FPGA builds](docs/development/hardware-build.md)
- [Trace capture](tools/enzian/trace/README.md) and [trace analysis tools](data/eci/sys_trace/README.md)

## System and research

- [Hardware architecture](docs/hardware/architecture.md) and [implementation scope](docs/hardware/implementation-status.md)
- [Linux scheduling](docs/software/scheduler.md)
- [Runtime](docs/software/runtime.md) and [application programming model](docs/software/programming-model.md)
- [Research goals](docs/research/paper-goals.md) and [open design questions](docs/research/roadmap.md)
- [Hot-path performance analysis](docs/research/hot-path-performance.md)
- [Applications](docs/applications/README.md) and [protocol integration](docs/proposals/grpc.md)

Agent instructions and continuation state are in [AGENTS.md](AGENTS.md) and the
[agent context index](agents/README.md).
