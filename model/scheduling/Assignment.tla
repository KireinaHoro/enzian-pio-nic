----------------------------- MODULE Assignment -----------------------------
EXTENDS Integers, FiniteSets
CONSTANTS Workers, Moving, Domains, InitialOwner, Destination, Capacity,
          BadReservation, BadPark
VARIABLE assignment
vars == <<assignment>>
Idle == -1
Stages == {"Run", "Preempt", "Yield", "InfoPending", "Installed", "Selected", "Active"}
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
\* Read acceptance certifies the kernel's no-return fence for the old task.
InfoAccept(w) == /\ assignment[w].stage = "Yield"
                 /\ assignment' = [assignment EXCEPT ![w].stage = "InfoPending",
                      ![w].acked = TRUE, ![w].parked = ~BadPark]
\* Response commits destination ownership; internal readiness is a harness guard.
InfoRespond(w) == /\ assignment[w].stage = "InfoPending"
                  /\ assignment' = [assignment EXCEPT ![w].stage = "Installed",
                       ![w].owner = assignment[w].reserved, ![w].reserved = Idle]
\* These are CPU-local actions, not MMIO writes or NIC acknowledgments.
Select(w) == /\ assignment[w].stage = "Installed"
             /\ assignment' = [assignment EXCEPT ![w].stage = "Selected"]
Activate(w) == /\ assignment[w].stage = "Selected"
               /\ assignment' = [assignment EXCEPT ![w].stage = "Active"]
Sleep(w) == /\ assignment[w].stage = "Run" /\ ~assignment[w].sleeping
            /\ assignment' = [assignment EXCEPT ![w].sleeping = TRUE]
Resume(w) == /\ assignment[w].sleeping
             /\ assignment' = [assignment EXCEPT ![w].sleeping = FALSE]
Ordering == \A w \in Workers:
    /\ (assignment[w].stage \in {"InfoPending","Installed","Selected","Active"}
        => assignment[w].acked /\ assignment[w].parked)
    /\ (assignment[w].stage \in {"Run","Preempt","Yield","InfoPending"}
        => assignment[w].owner = InitialOwner[w])
=============================================================================
