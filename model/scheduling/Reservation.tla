---------------------------- MODULE Reservation ----------------------------
EXTENDS Integers, FiniteSets
CONSTANTS BadReservation, FailSelection
Workers == {1,2}
Moving == Workers
Domains == {0,1,2}
InitialOwner == [w \in Workers |-> w-1]
Destination == [w \in Workers |-> 2]
Capacity == [d \in Domains |-> 1]
BadPark == FALSE
VARIABLE assignment
S == INSTANCE Assignment
v == <<assignment>>
Init == S!Init
Step(w) == S!Request(w) \/ S!Yield(w) \/ S!Acknowledge(w) \/ S!Drain(w)
           \/ S!RetireCache(w) \/ S!Park(w) \/ S!Activate(w)
           \/ (IF FailSelection THEN S!Unavailable(w) ELSE S!Install(w))
Next == \E w \in Workers: Step(w)
Spec == Init /\ [][Next]_v /\ (\A w \in Workers: WF_v(Step(w)))
Safety == S!TypeOK /\ S!CapacitySafe /\ S!Ordering
ReservationFinishes == \A w \in Workers:
    (assignment[w].stage = "Preempt") ~> (assignment[w].stage \in {"Active","Cancelled"})
NoLeakedReservation == \A w \in Workers:
    assignment[w].stage \in {"Active","Cancelled"} => assignment[w].reserved = -1
=============================================================================
