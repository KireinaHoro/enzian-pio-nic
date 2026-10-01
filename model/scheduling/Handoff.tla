------------------------------ MODULE Handoff ------------------------------
EXTENDS Integers, Sequences, FiniteSets
CONSTANTS Nonlocking, WorkerTx, WithStall, BadDrain, BadPark, BadReuse, BadParity
Workers == IF WithStall THEN 1..3 ELSE 1..2
Clients == IF WithStall THEN 1..4 ELSE 1..3
Moving == {1}
Domains == 0..3
InitialOwner == [w \in Workers |-> IF w = 1 THEN 0 ELSE w]
Destination == [w \in Workers |-> 1]
Capacity == [d \in Domains |-> 1]
BadReservation == FALSE
A == INSTANCE SelectedAddresses
Addresses == A!Addresses
Colors == 0..63
ColorOf == A!ColorOf
Directions == {0,1}
Stalled == IF WithStall THEN {4} ELSE {}
AllowedDirections == [c \in Clients |-> IF c \in {1,3} THEN Directions ELSE {0}]
Queues == Colors
QueueOf == [c \in Colors |-> c]
QueueDepth == 1
Pools == {0}
PoolOf == [c \in Clients |-> 0]
Slots == Cardinality(Workers)+1
SharedAck == TRUE
Slices == {0,1}
SliceOf == [c \in Colors |-> c % 2]
ReadSlots == Cardinality(Workers)+1
VARIABLES assignment, reads, invQ, held, acknowledgments, phase, parity, direction,
          remaining, completed, unlockQ, oldCache, prefixFree, reused, mailbox, returnedParity
S == INSTANCE Assignment
ClientWorker(c) == IF c = 3 THEN 1 ELSE IF c = 4 THEN 3 ELSE c
ClientDomain(c) == IF c = 1 THEN 0 ELSE IF c = 3 THEN 1 ELSE IF c = 4 THEN 3 ELSE 2
Control == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |->
    A!ControlTable[ClientDomain(c)+1][ClientWorker(c)][d+1][p+1]]]]
Dependencies == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> <<Control[c][d][1-p]>>]]]
P == INSTANCE ProgressSystem
\* Instantiate Goal 1 itself on the post-handoff clients, not a copied predicate.
NewClients == Clients \ {1}
New == INSTANCE ProgressSystem WITH
    Clients <- NewClients,
    Control <- [c \in NewClients |-> Control[c]],
    Dependencies <- [c \in NewClients |-> Dependencies[c]],
    AllowedDirections <- [c \in NewClients |-> AllowedDirections[c]],
    PoolOf <- [c \in NewClients |-> PoolOf[c]],
    phase <- [c \in NewClients |-> phase[c]],
    parity <- [c \in NewClients |-> parity[c]],
    direction <- [c \in NewClients |-> direction[c]],
    remaining <- [c \in NewClients |-> remaining[c]],
    completed <- [c \in NewClients |-> completed[c]]
transport == <<reads,invQ,held,acknowledgments,phase,parity,direction,remaining,completed,unlockQ>>
meta == <<oldCache,prefixFree,reused,mailbox,returnedParity>>
v == <<assignment,transport,meta>>
Quiet(w) == /\ phase[w] = "Submit" /\ w \notin held /\ w \notin acknowledgments
            /\ \A q \in Queues: \A i \in 1..Len(invQ[q]): invQ[q][i].txn # w
            /\ \A i \in 1..Len(unlockQ): unlockQ[i].txn # w
OldMessagesGone == /\ \A col \in Colors: \A i \in 1..Len(reads[col]): A!DomainOf[reads[col][i].address] # 0
                   /\ \A q \in Queues: \A i \in 1..Len(invQ[q]): A!DomainOf[invQ[q][i].address] # 0
                   /\ \A i \in 1..Len(unlockQ): A!DomainOf[unlockQ[i].address] # 0
\* The dormant destination has no independent transport history before installation.
Init == /\ S!Init /\ P!Init /\ parity[3] = parity[1] /\ direction[3] = 0
        /\ (WorkerTx \/ direction[1] = 0) /\ oldCache = TRUE /\ prefixFree = FALSE /\ reused = FALSE
        /\ mailbox = -1 /\ returnedParity = [d \in Directions |-> 0]
Request == /\ phase[1] = "Held" /\ direction[1] = 0 /\ S!Request(1) /\ UNCHANGED <<transport,meta>>
Yield == /\ phase[1] = "Submit" /\ S!Yield(1) /\ UNCHANGED <<transport,meta>>
InfoAccept == /\ S!InfoAccept(1) /\ UNCHANGED <<transport,meta>>
\* NIC-internal obligations only; the CPU does not access any 2F2F CL here.
InfoRespond == /\ (IF BadDrain THEN phase[1] = "Submit" ELSE Quiet(1))
               /\ S!InfoRespond(1)
               /\ parity' = [parity EXCEPT ![3] = parity[1], ![1] = [d \in Directions |-> 0]]
               /\ direction' = [direction EXCEPT ![1] = 0]
               /\ remaining' = [remaining EXCEPT ![1] = 0]
               /\ completed' = [completed EXCEPT ![1] = FALSE]
               /\ mailbox' = 1
               /\ returnedParity' = (IF BadParity THEN [parity[1] EXCEPT ![0] = 1-@] ELSE parity[1])
               /\ UNCHANGED <<reads,invQ,held,acknowledgments,phase,unlockQ,oldCache,prefixFree,reused>>
Select == /\ S!Select(1) /\ UNCHANGED <<transport,meta>>
\* Whole-domain retirement is separate from ordinary handoff. This abstract
\* endpoint certificate still needs concrete implementation/refinement.
RetireDomain == /\ assignment[1].owner # 0 /\ OldMessagesGone /\ oldCache
                /\ oldCache' = FALSE
                /\ UNCHANGED <<assignment,transport,prefixFree,reused,mailbox,returnedParity>>
Activate == /\ S!Activate(1) /\ UNCHANGED <<transport,meta>>
FreePrefix == /\ ~prefixFree
              /\ (BadReuse \/ (assignment[1].owner # 0 /\ ~oldCache /\ OldMessagesGone))
              /\ prefixFree' = TRUE /\ UNCHANGED <<assignment,transport,oldCache,reused,mailbox,returnedParity>>
Reuse == /\ prefixFree /\ ~reused /\ reused' = TRUE
         /\ UNCHANGED <<assignment,transport,oldCache,prefixFree,mailbox,returnedParity>>
Sleep == /\ phase[1] = "Submit" /\ S!Sleep(1) /\ UNCHANGED <<transport,meta>>
Resume == /\ S!Resume(1) /\ UNCHANGED <<transport,meta>>
ActiveClients == {c \in Clients: assignment[ClientWorker(c)].owner = ClientDomain(c)}
Admit(c) == /\ c \in ActiveClients
            /\ assignment[ClientWorker(c)].stage \in {"Run", "Active"}
            /\ ~assignment[ClientWorker(c)].sleeping
            /\ P!Admit(c) /\ UNCHANGED <<assignment,meta>>
\* PREEMPT completes a normal abstract 2F2F exchange, then remains latched until yield.
Work(c) == /\ P!Work(c) /\ UNCHANGED <<assignment,meta>>
Inv(c) == /\ P!Inv(c) /\ UNCHANGED <<assignment,meta>>
ReceiveAck(c) == /\ P!Ack(c) /\ UNCHANGED <<assignment,meta>>
Unlock(c) == /\ P!Unlock(c) /\ UNCHANGED <<assignment,meta>>
Return(c,d) == /\ (WorkerTx \/ d = 0) /\ P!Return(c,d) /\ UNCHANGED <<assignment,meta>>
Process(q) == /\ P!Process(q) /\ UNCHANGED <<assignment,meta>>
DeliverUnlock == /\ P!DeliverUnlock /\ UNCHANGED <<assignment,meta>>
Next == \/ Request \/ Yield \/ InfoAccept \/ InfoRespond \/ Select \/ Activate \/ RetireDomain
        \/ FreePrefix \/ Reuse \/ Sleep \/ Resume \/ DeliverUnlock
        \/ \E c \in Clients: Admit(c) \/ Work(c) \/ Inv(c) \/ ReceiveAck(c) \/ Unlock(c)
        \/ \E c \in Clients, d \in Directions: Return(c,d)
        \/ \E q \in Queues: Process(q)
UsedDirections(c) == IF WorkerTx THEN AllowedDirections[c] ELSE {0}
UsedQueues == UNION {UNION {UNION {
    {QueueOf[ColorOf[Dependencies[c][d][p][i]]]: i \in 1..Len(Dependencies[c][d][p])}
    : p \in 0..1}: d \in UsedDirections(c)}: c \in Clients \ Stalled}
UnusedEmpty == \A q \in Queues \ UsedQueues: invQ[q] = <<>>
Spec == Init /\ [][Next]_v
        /\ WF_v(Yield) /\ WF_v(InfoAccept) /\ WF_v(InfoRespond) /\ WF_v(RetireDomain)
        /\ WF_v(Select) /\ WF_v(Activate) /\ WF_v(Resume)
        /\ WF_v(FreePrefix) /\ WF_v(Reuse) /\ WF_v(DeliverUnlock)
        /\ (\A c \in Clients \ Stalled:
            SF_v(Admit(c)) /\ WF_v(Work(c)) /\ SF_v(Inv(c)) /\ SF_v(ReceiveAck(c)) /\ WF_v(Unlock(c)))
        /\ (\A c \in Clients \ Stalled: \A d \in UsedDirections(c): WF_v(Return(c,d)))
        /\ (\A q \in UsedQueues: WF_v(Process(q)))
CurrentCompatible == \A c,e \in ActiveClients, d,f \in Directions, p \in 0..1:
    /\ ColorOf[Control[c][d][p]] # ColorOf[Control[c][d][1-p]]
    /\ (c # e => (\A a \in P!Footprint(e,f): ColorOf[Control[c][d][p]] # ColorOf[a]))
\* Goal 1's resource predicate projected onto the currently installed endpoints.
\* Old/new endpoints share one physical QP, but are never admitted concurrently.
CurrentSettled == P!TypeOK /\ P!Tracking /\ P!Conservation /\ P!UnusedQueuesEmpty /\ UnusedEmpty /\ CurrentCompatible
Safety == /\ S!TypeOK /\ S!CapacitySafe /\ S!Ordering /\ CurrentSettled /\ P!NoReadDependencyCycle
          /\ oldCache \in BOOLEAN /\ prefixFree \in BOOLEAN /\ reused \in BOOLEAN
DrainBeforeInstall == assignment[1].stage \in {"Installed","Selected","Active"} => Quiet(1)
PrefixSafe == (prefixFree \/ reused) => (~oldCache /\ OldMessagesGone /\ assignment[1].owner # 0)
\* Check the actual return boundary; later operations legitimately change parity.
ActivationContract == Activate => (New!Settled' /\ mailbox' = 1 /\ returnedParity' = parity'[3]
                                   /\ Quiet(1))
SteadyEntry == assignment[1].stage = "Active" => New!Settled
ActivationSafe == [][ActivationContract]_v
SleepRetainsAssignment == [][(Sleep \/ Resume) => assignment'[1].owner = assignment[1].owner]_v
HandoffFinishes == (assignment[1].stage = "Preempt") ~> (assignment[1].stage \in {"Active"})
PeerProgress == ([]<>completed[2]) /\ ([]<>~completed[2]) /\ []<>(phase[2] = "Held")
PrefixRetires == (assignment[1].stage \in {"Active"}) ~> reused
=============================================================================
