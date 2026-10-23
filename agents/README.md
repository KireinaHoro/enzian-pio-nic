# Agent context

Human descriptions and design rationale belong in README files and `docs/`.
These files hold continuation state by goal; follow [AGENTS.md](../AGENTS.md)
for working rules and topic routing.

- [Build and CI](build-ci.md): build ownership, validation references and continuation checks.
- [Runtime and applications](runtime-applications.md): accepted scope, bounded results and next checks.
- [Userspace TCP](userspace-tcp.md): phase-one backend preparation and VM acceptance prerequisites.
- [Caladan baseline](caladan.md): CS-A64 port integration, evidence and remaining checks.
- [Measurements](measurements.md): instrumentation, evidence and remaining measurement work.
- [Physical implementation](physical-implementation.md): timing pause and dependency ownership.
- [Hardware state](hardware-state.md): dated observations, preserved failures and reuse constraints.

Hardware observations are historical, not live reservations. Read hardware state
before any deployment and recheck actual state. No implementation or hardware
operation is authorized merely by a continuation note.
