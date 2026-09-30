------------------------------ MODULE Steady ------------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS N, ColorCount, Slots, QueueDepth, SharedQueue, SharedAck, BadColor,
          StallCount, Minimal, ReadSlots
Clients == 1..N
Addresses == 0..(4*N-1)
Colors == 0..(ColorCount-1)
Directions == IF Minimal THEN {0} ELSE 0..1
Stalled == 1..StallCount
Control == [c \in Clients |-> [d \in Directions |->
    [p \in 0..1 |-> 4*(c-1)+p]]]
Dependencies == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |->
    IF Minimal THEN <<4*(c-1)+1-p>> ELSE <<4*(c-1)+1-p, 4*(c-1)+2+d>>]]]
ColorOf == [a \in Addresses |-> IF BadColor THEN a % 4 ELSE a % ColorCount]
Queues == IF SharedQueue THEN {0} ELSE Colors
QueueOf == [c \in Colors |-> IF SharedQueue THEN 0 ELSE c]
Slices == {0,1}
SliceOf == [c \in Colors |-> c % 2]
Pools == {0}
PoolOf == [c \in Clients |-> 0]
VARIABLES reads, invQ, held, acknowledgments, phase, parity, direction, remaining, completed, unlockQ
System == INSTANCE ProgressSystem WITH reads <- reads, invQ <- invQ,
    held <- held, acknowledgments <- acknowledgments, phase <- phase,
    parity <- parity, direction <- direction, remaining <- remaining,
    completed <- completed, unlockQ <- unlockQ
Spec == System!Spec
TypeOK == System!TypeOK
Tracking == System!Tracking
Conservation == System!Conservation
Settled == System!Settled
NoReadDependencyCycle == System!NoReadDependencyCycle
Progress == System!Progress
Admission == System!Admission
=============================================================================
