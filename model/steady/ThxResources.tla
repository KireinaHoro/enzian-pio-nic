-------------------------- MODULE ThxResources --------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS Clients, Pools, PoolOf, Slots, SharedAck
VARIABLES held, acknowledgments
vars == <<held, acknowledgments>>
Init == /\ held = {} /\ acknowledgments = {}
TypeOK == /\ held \subseteq Clients /\ acknowledgments \subseteq Clients
Used(p) == Cardinality({c \in held: PoolOf[c] = p}) +
           IF SharedAck THEN Cardinality({c \in acknowledgments: PoolOf[c] = p}) ELSE 0
Conservation == \A p \in Pools: Used(p) <= Slots
CanRead(c) == /\ c \notin held /\ Used(PoolOf[c]) < Slots
ReceiveRead(c) == /\ CanRead(c) /\ held' = held \cup {c}
                  /\ UNCHANGED acknowledgments
CanReceiveAck(c) == /\ c \in held /\ c \notin acknowledgments
                    /\ (~SharedAck \/ Used(PoolOf[c]) < Slots)
CompleteAck(c) == /\ CanReceiveAck(c) /\ UNCHANGED vars
ReceiveAck(c) == /\ CanReceiveAck(c)
                 /\ acknowledgments' = acknowledgments \cup {c}
                 /\ UNCHANGED held
SendUnlock(c) == /\ c \in acknowledgments
                 /\ acknowledgments' = acknowledgments \ {c}
                 /\ UNCHANGED held
CompleteRead(c) == /\ c \in held
                   /\ held' = held \ {c}
                   /\ UNCHANGED acknowledgments
=============================================================================
