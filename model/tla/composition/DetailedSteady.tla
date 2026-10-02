---------------------------- MODULE DetailedSteady ----------------------------
EXTENDS SteadyGeometry
CONSTANTS DcTable, ThxTable, WriteService
VARIABLES reads, invQ, held, phase, parity, direction, remaining, completed, endpoints
System == INSTANCE ProgressSystem
\* Optional implementation of the steady layer's abstract Complete action.
\* Each target exchange starts with a resident exclusive line. Cold read/refill
\* correctness is checked separately by LineEndpoint; shared pool/directory
\* behavior remains represented by the steady abstraction, not by this adapter.
E(a) == INSTANCE LineEndpoint WITH Line <- a, CpuLoad <- "LS",
    InitialPermission <- "Exclusive", BadEarlyAck <- FALSE, LinkDepth <- 2,
    ep <- endpoints[a]
all == <<reads,invQ,held,phase,parity,direction,remaining,completed,endpoints>>
Advance(a,states) == \E next \in states: endpoints' = [endpoints EXCEPT ![a] = next]
Init == System!Init /\ endpoints = [a \in Addresses |-> E(a)!Initial]
Client == /\ ((\E c \in Clients: System!Admit(c) \/ System!Work(c) \/ System!Inv(c) \/ System!Ack(c))
             \/ (\E c \in Clients,d \in Directions: System!Return(c,d)))
          /\ UNCHANGED endpoints
Start(q) == /\ invQ[q] # <<>> /\ Head(invQ[q]).type = "Invalidate"
    /\ \E a \in Addresses: a = Head(invQ[q]).address
       /\ Len(reads[ColorOf[a]]) = 0 /\ Advance(a,E(a)!SubmitStates)
    /\ UNCHANGED <<reads,invQ,held,phase,parity,direction,remaining,completed>>
Internal(a) == /\ Advance(a,E(a)!InternalStates)
    /\ UNCHANGED <<reads,invQ,held,phase,parity,direction,remaining,completed>>
Complete(q) == /\ invQ[q] # <<>> /\ Head(invQ[q]).type = "Invalidate"
    /\ \E a \in Addresses: a = Head(invQ[q]).address
       /\ Advance(a,E(a)!AcceptStates) /\ System!Process(q)
\* Re-establish the next exchange's warm entry contract after LCIA acceptance.
\* This is an explicit environment action, not a silent clean-cache assumption.
Warm(a) == /\ endpoints[a].app = "Done"
    /\ \E q \in Queues: invQ[q] # <<>> /\ Head(invQ[q]).address = a /\ Head(invQ[q]).type = "Invalidate"
    /\ endpoints' = [endpoints EXCEPT ![a] = E(a)!Initial]
    /\ UNCHANGED <<reads,invQ,held,phase,parity,direction,remaining,completed>>
Next == Client \/ (\E q \in Queues: Start(q) \/ Complete(q))
               \/ (\E a \in Addresses: Internal(a) \/ Warm(a))
Spec == Init /\ [][Next]_all
    /\ (\A c \in Clients \ Stalled:
        SF_all(System!Admit(c) /\ UNCHANGED endpoints)
        /\ WF_all(System!Work(c) /\ UNCHANGED endpoints)
        /\ SF_all(System!Inv(c) /\ UNCHANGED endpoints)
        /\ SF_all(System!Ack(c) /\ UNCHANGED endpoints))
    /\ (\A c \in Clients \ Stalled: \A d \in AllowedDirections[c]: WF_all(System!Return(c,d) /\ UNCHANGED endpoints))
    /\ (\A q \in Queues: WF_all(Start(q)) /\ WF_all(Complete(q)))
    /\ (\A a \in Addresses: WF_all(Internal(a)) /\ WF_all(Warm(a)))
TypeOK == System!TypeOK
Tracking == System!Tracking
Conservation == System!Conservation
Progress == System!Progress
Admission == System!Admission
EndpointSafety == \A a \in Addresses: E(a)!TypeOK /\ E(a)!NoProtocolError /\ E(a)!CompletionSound
\* TLC checks the substitution against the imported abstract action relation.
AbstractBehavior == System!Init /\ [][System!Next]_<<reads,invQ,held,phase,parity,direction,remaining,completed>>
=============================================================================
