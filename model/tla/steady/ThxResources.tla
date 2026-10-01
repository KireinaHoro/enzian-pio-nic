-------------------------- MODULE ThxResources --------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS Clients, Pools, PoolOf, Slots, SharedAck
VARIABLE held
vars == <<held>>
Init == held = {}
TypeOK == held \subseteq Clients
Used(p) == Cardinality({c \in held: PoolOf[c] = p})
Conservation == \A p \in Pools: Used(p) <= Slots
CanRead(c) == /\ c \notin held /\ Used(PoolOf[c]) < Slots
ReceiveRead(c) == /\ CanRead(c) /\ held' = held \cup {c}
\* Undischarged conservative admission assumption: servicing LCIA needs a spare
\* shared credit when SharedAck. Acceptance/release is atomic, not held afterward.
\* This is not a model of independently timed writeback/response buffers.
CompleteAck(c) == /\ c \in held /\ (~SharedAck \/ Used(PoolOf[c]) < Slots)
                  /\ UNCHANGED held
CompleteRead(c) == /\ c \in held /\ held' = held \ {c}
=============================================================================
