# Follow-on protocol work: TCP, Protobuf and gRPC

Maintainer direction, 2026-09-08: the TCP and Protobuf student projects are finished research projects in external trees. Merge their work into the platform soon enough to limit drift, but give **Dandelion and the other first-full-system-paper applications higher priority**. Fully offloaded gRPC is a potential subsequent paper, not the first paper's required contribution or a currently implemented system.

## Inputs and scope

| Work | External source / documentation | Integration status |
| --- | --- | --- |
| Partial TCP offload | `../flavian-tcp/{platform,report}`; [TCP project](tcp.md) | External implementation; not merged into this checkout |
| Protobuf decoder | `../elena-protobuf/{platform,report}` plus project dependencies; [Protobuf project](protobuf.md) | External decoder work; not merged into this checkout |
| Application/runtime composition | `../sven-dandelion/{platform,dandelion,report}`; [Dandelion](dandelion.md) | Higher-priority application/runtime integration |

A finished student project does not imply production host support, complete protocol coverage or board validation. Preserve the distinction between report evidence, source implementation and intended full gRPC system. TCP transport and Protobuf receive decoding are ingredients; their combination alone does not demonstrate an offloaded gRPC implementation. The TCP report (`sections/02-background-and-motivation.tex`, gRPC subsection) explicitly leaves HTTP/2 stream/frame handling and gRPC envelope decoding outside its implementation. Those are additional follow-on components.

## Proposed integration approach

These are planning recommendations, not merges performed by this documentation task:

1. Record platform/report/dependency revisions and retain each project's reproducible tests and configurations. Consult the project notes before selecting commits; sibling dependency changes may be essential.
2. Adapt each project to the current generator, metadata types, generated headers, packet lifetime rules and tracing. Preserve the working ONC-RPC/ECI configuration and keep incomplete paths explicitly selectable.
3. Bring TCP host ownership and decoder schema/output contracts across the software boundary deliberately. Tests driven by a model or manually configured descriptor table do not replace a production runtime consumer.
4. Run component and integrated regressions, then real FPGA/OS checks appropriate to the claim. Keep baseline and enabled configurations comparable for area, timing and latency.
5. Define the follow-on gRPC contract separately: end-to-end transport/framing, request and response processing, schema registration, concurrency, error handling, resource bounds and isolation. Do not infer these contracts from a decoder or transport block in isolation.

Do not let this follow-on scope displace application integration and the Demikernel evaluation for [the first full-system paper](paper-goals.md). A specific merge order/date between TCP and Protobuf is not yet assigned. If a first-paper application needs one of these components, make that dependency explicit rather than silently promoting the entire gRPC agenda.
