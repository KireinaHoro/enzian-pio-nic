---------------------------- MODULE SingleWorker ----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS DcTable, ThxTable, WriteService, WorkerDirections
Clients == {0}
Stalled == {}
Directions == 0..(WorkerDirections-1)
AllowedDirections == [c \in Clients |-> Directions]
Control == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> 4*d+p]]]
Dependencies == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> <<4*d+1-p,4*d+2,4*d+3>>]]]
Lines == 0..(4*WorkerDirections-1)
VARIABLES phase,parity,direction,remaining,completed,endpoints
C == INSTANCE TwoFClient
E(a) == INSTANCE LineEndpoint WITH Line <- a, CpuLoad <- IF a < 4 THEN "LS" ELSE "LE",
    InitialPermission <- "Invalid", BadEarlyAck <- FALSE, LinkDepth <- 2,
    ep <- endpoints[a]
client == <<phase,parity,direction,remaining,completed>>
vars == <<phase,parity,direction,remaining,completed,endpoints>>
Advance(a,states) == \E next \in states: endpoints' = [endpoints EXCEPT ![a] = next]
Init == C!Init /\ endpoints = [a \in Lines |-> E(a)!Initial]
DoorLoad == /\ phase[0] = "Submit"
    /\ (\E a \in Lines: a = C!Door(0) /\ Advance(a,E(a)!LoadStates))
    /\ UNCHANGED client
Admit == /\ endpoints[C!Door(0)].read = "Data"
    /\ C!Accepted(0) /\ UNCHANGED endpoints
Work == C!Work(0) /\ UNCHANGED endpoints
Prepare == /\ phase[0] = "NeedInv"
    /\ (\E a \in Lines: a = C!Target(0) /\ Advance(a,E(a)!LoadStates))
    /\ UNCHANGED client
Submit == /\ phase[0] = "NeedInv"
    /\ (\E a \in Lines: a = C!Target(0) /\ Advance(a,E(a)!SubmitStates)) /\ C!SubmittedInv(0)
\* The real doorbell RDD remains held while its opposite/control/overflow
\* invalidations run. AXI write service is independent of this read gate.
Internal(a) == Advance(a,IF a = C!Door(0) /\ phase[0] # "Return"
                           THEN E(a)!InternalStates \ E(a)!ReadStates ELSE E(a)!InternalStates)
    /\ UNCHANGED client
Finish == /\ phase[0] = "WaitAck"
    /\ (\E a \in Lines: a = C!Target(0) /\ Advance(a,E(a)!AcceptStates)) /\ C!InvalidationCompleted(0)
Return(d) == /\ endpoints[C!Door(0)].loaded
    /\ C!Returned(0,d) /\ UNCHANGED endpoints
\* Reuse goes through an actual new THX load and ROM read response before LCI.
Warm(a) == /\ ((phase[0] = "NeedInv" /\ a = C!Target(0))
                   \/ (phase[0] = "Submit" /\ a = C!Door(0)))
    /\ endpoints[a].app = "Done"
    /\ endpoints' = [endpoints EXCEPT ![a] = E(a)!Initial]
    /\ UNCHANGED client
Next == DoorLoad \/ Admit \/ Work \/ Prepare \/ Submit \/ Finish \/ (\E d \in Directions: Return(d))
        \/ (\E a \in Lines: Internal(a) \/ Warm(a))
Spec == Init /\ [][Next]_vars /\ WF_vars(DoorLoad) /\ WF_vars(Admit) /\ WF_vars(Work)
    /\ WF_vars(Prepare) /\ WF_vars(Submit) /\ WF_vars(Finish)
    /\ (\A d \in Directions: WF_vars(Return(d)))
    /\ (\A a \in Lines: WF_vars(Internal(a)) /\ WF_vars(Warm(a)))
Safety == C!TypeOK /\ \A a \in Lines: E(a)!TypeOK /\ E(a)!NoProtocolError /\ E(a)!CompletionSound
NoOutstandingAtReturn == phase[0] \in {"Return","Submit"} =>
    \A a \in Lines: endpoints[a].app \notin {"Pending","Ready"}
Progress == ([]<>completed[0]) /\ ([]<>~completed[0])
ReturnAfterDoorResponse == [][(phase[0] = "Return" /\ phase'[0] = "Submit") =>
    endpoints[C!Door(0)].loaded]_vars
OperationRefinement == C!Behavior
=============================================================================
