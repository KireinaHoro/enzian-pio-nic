----------------------------- MODULE Assignment -----------------------------
EXTENDS Integers, FiniteSets
CONSTANTS Workers, Moving, Domains, InitialOwner, Destination, Capacity,
          BadReservation, BadPark
VARIABLE assignment
vars == <<assignment>>
Idle == -1
Stages == {"Run", "Preempt", "Yield", "Ack", "Drained", "Retired",
           "Park", "Installed", "Active", "Cancelled"}
Init == assignment = [w \in Workers |->
    [owner |-> InitialOwner[w], stage |-> "Run", reserved |-> Idle,
     parked |-> FALSE, acked |-> FALSE, sleeping |-> FALSE]]
TypeOK == assignment \in [Workers ->
    [owner : Domains \cup {Idle}, stage : Stages, reserved : Domains \cup {Idle},
     parked : BOOLEAN, acked : BOOLEAN, sleeping : BOOLEAN]]
Used(d) == Cardinality({w \in Workers: assignment[w].owner = d}) +
           Cardinality({w \in Workers: assignment[w].reserved = d /\ assignment[w].owner # d})
CapacitySafe == \A d \in Domains: Used(d) <= Capacity[d]
Request(w) == /\ w \in Moving /\ assignment[w].stage = "Run"
              /\ ~assignment[w].sleeping
              /\ BadReservation \/ Used(Destination[w]) < Capacity[Destination[w]]
              /\ assignment' = [assignment EXCEPT ![w].stage = "Preempt",
                                                   ![w].reserved = Destination[w]]
Yield(w) == /\ assignment[w].stage = "Preempt"
            /\ assignment' = [assignment EXCEPT ![w].stage = "Yield"]
Acknowledge(w) == /\ assignment[w].stage = "Yield"
                  /\ assignment' = [assignment EXCEPT ![w].stage = "Ack", ![w].acked = TRUE]
Drain(w) == /\ assignment[w].stage = "Ack"
            /\ assignment' = [assignment EXCEPT ![w].stage = "Drained"]
RetireCache(w) == /\ assignment[w].stage = "Drained"
                  /\ assignment' = [assignment EXCEPT ![w].stage = "Retired"]
Park(w) == /\ assignment[w].stage = "Retired"
           /\ assignment' = [assignment EXCEPT ![w].stage = "Park", ![w].owner = Idle,
                                                ![w].parked = ~BadPark]
Install(w) == /\ assignment[w].stage = "Park"
              /\ assignment' = [assignment EXCEPT ![w].stage = "Installed",
                  ![w].owner = assignment[w].reserved, ![w].reserved = Idle]
\* No available thread is a completed failed dispatch, not a panic or a leaked reservation.
Unavailable(w) == /\ assignment[w].stage = "Park"
                  /\ assignment' = [assignment EXCEPT ![w].stage = "Cancelled", ![w].reserved = Idle]
Activate(w) == /\ assignment[w].stage = "Installed"
               /\ assignment' = [assignment EXCEPT ![w].stage = "Active", ![w].parked = FALSE]
Sleep(w) == /\ assignment[w].stage = "Run" /\ ~assignment[w].sleeping
            /\ assignment' = [assignment EXCEPT ![w].sleeping = TRUE]
Resume(w) == /\ assignment[w].sleeping
             /\ assignment' = [assignment EXCEPT ![w].sleeping = FALSE]
Ordering == \A w \in Workers:
    /\ (assignment[w].stage \in {"Ack", "Drained", "Retired", "Park", "Installed", "Active", "Cancelled"}
        => assignment[w].acked)
    /\ (assignment[w].stage \in {"Park", "Installed", "Cancelled"} => assignment[w].parked)
    /\ (assignment[w].stage \in {"Run", "Preempt", "Yield", "Ack", "Drained", "Retired"}
        => assignment[w].owner = InitialOwner[w])
=============================================================================
