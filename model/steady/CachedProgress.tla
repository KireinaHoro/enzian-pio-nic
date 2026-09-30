----------------------------- MODULE CachedProgress -----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS SharedEvictions, BlockDowngrade, DisableReplacement
Clients == {1,2}
Colors == 0..3
Addresses == 0..11
ColorOf == [a \in Addresses |-> a % 4]
Directions == {0}
Stalled == {1}
AllowedDirections == [c \in Clients |-> Directions]
Control == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> 2*(c-1)+p]]]
Dependencies == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> <<2*(c-1)+1-p>>]]]
Queues == Colors
QueueOf == [s \in Colors |-> s]
QueueDepth == 1
Slices == {0,1}
SliceOf == [s \in Colors |-> s % 2]
ReadSlots == 3
Pools == {0}
PoolOf == [c \in Clients |-> 0]
Slots == 3
SharedAck == TRUE
Lines == Addresses
Sets == Colors
SetOf == ColorOf
Ways == 2
EvictionQueues == IF SharedEvictions THEN {0} ELSE Sets
EvictionQueueOf == [s \in Sets |-> IF SharedEvictions THEN 0 ELSE s]
EvictionDepth == 2
VARIABLES reads,invQ,held,acknowledgments,phase,parity,direction,remaining,completed,unlockQ,
          resident,directory,dirty,evictions,retiring
P == INSTANCE ProgressSystem
R == INSTANCE RetainedCache
transport == <<reads,invQ,held,acknowledgments,phase,parity,direction,remaining,completed,unlockQ>>
cache == <<resident,directory,dirty,evictions,retiring>>
v == <<transport,cache>>
\* Every set is initially full; a pre-existing dirty eviction shares the stalled
\* client's color. All live control lines miss. No clean-cache/empty-set premise.
Init == /\ P!Init /\ parity[1][0] = 0
        /\ resident = ({0} \cup (4..11)) \ {4,8}
        /\ directory = ({0} \cup (4..11)) \ {8} /\ dirty = resident
        /\ retiring = {}
        /\ evictions = [q \in EvictionQueues |-> IF q = EvictionQueueOf[0]
                        THEN <<[type |-> "Writeback", address |-> 4]>> ELSE <<>>]
Pinned == {P!C!Door(c): c \in held} \cup
          UNION {{invQ[q][i].address: i \in 1..Len(invQ[q])}: q \in Queues}
Need(c) == P!C!Door(c)
Prepare(c) == /\ phase[c] = "Submit" /\ Need(c) \notin resident /\ Need(c) \notin Pinned
    /\ (R!Fill(Need(c)) \/
         (~DisableReplacement /\ \E a \in resident \ Pinned: SetOf[a] = SetOf[Need(c)] /\ R!Evict(a)))
    /\ UNCHANGED transport
EvictStable(a) == /\ a \in {2,3} /\ a \notin Pinned /\ R!Evict(a) /\ UNCHANGED transport
Deliver(q) == /\ Len(evictions[q]) > 0
    /\ (~BlockDowngrade \/ reads[ColorOf[Head(evictions[q]).address]] = <<>>)
    /\ R!Deliver(q) /\ UNCHANGED transport
Admit(c) == /\ Need(c) \in resident /\ P!Admit(c) /\ UNCHANGED cache
Work(c) == /\ P!Work(c) /\ UNCHANGED cache
Inv(c) == /\ P!Inv(c) /\ UNCHANGED cache
Ack(c) == /\ P!Ack(c) /\ UNCHANGED cache
Unlock(c) == /\ P!Unlock(c) /\ UNCHANGED cache
Return(c) == /\ P!Return(c,0) /\ UNCHANGED cache
Process(q) == /\ P!Process(q) /\ R!Invalidate(Head(invQ[q]).address)
DeliverUnlock == /\ P!DeliverUnlock /\ R!ReleaseInvalidation(Head(unlockQ).address)
Next == DeliverUnlock \/ (\E q \in Queues: Process(q))
        \/ (\E q \in EvictionQueues: Deliver(q))
        \/ (\E a \in Lines: EvictStable(a))
        \/ (\E c \in Clients: Prepare(c) \/ Admit(c) \/ Work(c) \/ Inv(c) \/ Ack(c) \/ Unlock(c) \/ Return(c))
Spec == Init /\ [][Next]_v /\ WF_v(DeliverUnlock)
    /\ (\A c \in Clients \ Stalled: SF_v(Prepare(c)) /\ SF_v(Admit(c))
         /\ WF_v(Work(c)) /\ SF_v(Inv(c)) /\ SF_v(Ack(c)) /\ WF_v(Unlock(c)) /\ WF_v(Return(c)))
    /\ (\A q \in Queues: WF_v(Process(q)))
    /\ (\A q \in EvictionQueues: WF_v(Deliver(q)))
Safety == P!TypeOK /\ P!Tracking /\ P!Conservation /\ R!TypeOK /\ R!Conservation
Progress == P!Progress /\ P!Admission
=============================================================================
