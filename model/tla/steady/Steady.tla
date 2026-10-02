------------------------------ MODULE Steady ------------------------------
EXTENDS SteadyGeometry
VARIABLES reads, invQ, held, phase, parity, direction, remaining, completed
System == INSTANCE ProgressSystem WITH reads <- reads, invQ <- invQ,
    held <- held, phase <- phase,
    parity <- parity, direction <- direction, remaining <- remaining,
    completed <- completed
Spec == System!Spec
TypeOK == System!TypeOK
Tracking == System!Tracking
Conservation == System!Conservation
Settled == System!Settled
NoReadDependencyCycle == System!NoReadDependencyCycle
Progress == System!Progress
Admission == System!Admission
=============================================================================
