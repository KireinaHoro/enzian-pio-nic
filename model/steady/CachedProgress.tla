----------------------------- MODULE CachedProgress -----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS SharedEvictions, BlockDowngrade, DisableReplacement, AllocateOnInvalidate, ReachableCache
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
          resident,directory,dirty,evictions,retiring,warmup
P == INSTANCE ProgressSystem
R == INSTANCE RetainedCache
transport == <<reads,invQ,held,acknowledgments,phase,parity,direction,remaining,completed,unlockQ>>
cache == <<resident,directory,dirty,evictions,retiring>>
v == <<transport,cache,warmup>>
\* Every set is initially full; a pre-existing dirty eviction shares the stalled
\* client's color. All live control lines miss. No clean-cache/empty-set premise.
LegacyInit == /\ P!Init /\ parity[1][0] = 0
        /\ resident = ({0} \cup (4..11)) \ {4,8}
        /\ directory = ({0} \cup (4..11)) \ {8} /\ dirty = resident
        /\ retiring = {}
        /\ evictions = [q \in EvictionQueues |-> IF q = EvictionQueueOf[0]
                        THEN <<[type |-> "Writeback", address |-> 4]>> ELSE <<>>]
\* Reach the pressure state from an empty directory, through the same cache
\* component used below. Target 3 is filled, then evicted and replaced by 11.
\* No read/LCI traffic is enabled until this finite prior history finishes.
WarmupLength == 12
Init == /\ warmup = (IF ReachableCache THEN 0 ELSE WarmupLength)
        /\ IF ReachableCache
            THEN /\ P!Init /\ parity[1][0] = 0
                 /\ resident = {} /\ directory = {} /\ dirty = {}
                 /\ retiring = {} /\ evictions = [q \in EvictionQueues |-> <<>>]
            ELSE LegacyInit
Warmup == /\ warmup < WarmupLength
          /\ CASE warmup = 0 -> R!Fill(3)
                [] warmup = 1 -> R!Fill(7)
                [] warmup = 2 -> R!Evict(3)
                [] warmup = 3 -> R!Deliver(EvictionQueueOf[3])
                [] warmup = 4 -> R!Fill(11)
                [] warmup = 5 -> R!Fill(0)
                [] warmup = 6 -> R!Fill(4)
                [] warmup = 7 -> R!Evict(4)
                [] warmup = 8 -> R!Fill(5)
                [] warmup = 9 -> R!Fill(9)
                [] warmup = 10 -> R!Fill(6)
                [] warmup = 11 -> R!Fill(10)
          /\ warmup' = warmup + 1 /\ UNCHANGED transport
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
LiveNext == DeliverUnlock \/ (\E q \in Queues: Process(q))
        \/ (\E q \in EvictionQueues: Deliver(q))
        \/ (\E a \in Lines: EvictStable(a))
        \/ (\E c \in Clients: Prepare(c) \/ Admit(c) \/ Work(c) \/ Inv(c) \/ Ack(c) \/ Unlock(c) \/ Return(c))
Next == Warmup \/ (warmup = WarmupLength /\ LiveNext /\ UNCHANGED warmup)
Live(a) == warmup = WarmupLength /\ a /\ UNCHANGED warmup
Spec == Init /\ [][Next]_v /\ WF_v(Warmup) /\ WF_v(Live(DeliverUnlock))
    /\ (\A c \in Clients \ Stalled: SF_v(Live(Prepare(c))) /\ SF_v(Live(Admit(c)))
         /\ WF_v(Live(Work(c))) /\ SF_v(Live(Inv(c))) /\ SF_v(Live(Ack(c))) /\ WF_v(Live(Unlock(c))) /\ WF_v(Live(Return(c))))
    /\ (\A q \in Queues: WF_v(Live(Process(q))))
    /\ (\A q \in EvictionQueues: WF_v(Live(Deliver(q))))
Safety == warmup \in 0..WarmupLength /\ P!TypeOK /\ P!Tracking /\ P!Conservation /\ R!TypeOK /\ R!Conservation
\* This witness is a resource wait, not itself a temporal-failure assertion.
NoAbsentLockWait == ~\E q \in Queues:
    /\ Len(invQ[q]) > 0 /\ Head(invQ[q]).type = "Invalidate"
    /\ R!AbsentLockWait(Head(invQ[q]).address)
Progress == P!Progress /\ P!Admission
=============================================================================
