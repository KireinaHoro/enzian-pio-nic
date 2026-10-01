----------------------------- MODULE RetainedCache -----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Lines, Sets, SetOf, Ways, EvictionQueues, EvictionQueueOf, EvictionDepth
VARIABLES resident, directory, dirty, evictions, retiring
vars == <<resident,directory,dirty,evictions,retiring>>
Entries(s) == {a \in Lines: SetOf[a] = s}
Queued == UNION {{evictions[q][i].address: i \in 1..Len(evictions[q])}: q \in EvictionQueues}
Messages == [type : {"Downgrade","Writeback"}, address : Lines]
\* CPU replacement removes residency first. The directory entry is retained
\* until the independent downgrade/writeback has actually been delivered.
Init == /\ resident \in SUBSET Lines
        /\ \A s \in Sets: Cardinality(resident \cap Entries(s)) <= Ways
        /\ directory = resident /\ dirty \in SUBSET resident /\ retiring = {}
        /\ evictions = [q \in EvictionQueues |-> <<>>]
CanFill(a) == /\ a \notin directory
              /\ Cardinality(directory \cap Entries(SetOf[a])) < Ways
Fill(a) == /\ CanFill(a)
           /\ resident' = resident \cup {a} /\ directory' = directory \cup {a}
           /\ UNCHANGED <<dirty,evictions,retiring>>
Evict(a) == LET q == EvictionQueueOf[SetOf[a]] IN
    /\ a \in resident /\ Len(evictions[q]) < EvictionDepth
    /\ resident' = resident \ {a} /\ dirty' = dirty \ {a}
    /\ evictions' = [evictions EXCEPT ![q] = Append(@,
        [type |-> IF a \in dirty THEN "Writeback" ELSE "Downgrade", address |-> a])]
    /\ UNCHANGED <<directory,retiring>>
Deliver(q) == /\ Len(evictions[q]) > 0
              /\ directory' = directory \ {Head(evictions[q]).address}
              /\ evictions' = [evictions EXCEPT ![q] = Tail(@)]
              /\ UNCHANGED <<resident,dirty,retiring>>
\* Resident hits remain charged until LCIA acceptance. An absent LCI target
\* never allocates an entry. Queued writebacks and earlier invalidations exclude
\* competing invalidation processing until their completion has been delivered.
CanInvalidate(a) == a \notin Queued \cup retiring
Invalidate(a) == /\ CanInvalidate(a)
                 /\ resident' = resident \ {a} /\ dirty' = dirty \ {a}
                 /\ retiring' = IF a \in directory THEN retiring \cup {a} ELSE retiring
                 /\ UNCHANGED <<directory,evictions>>
ReleaseInvalidation(a) ==
    /\ retiring' = retiring \ {a}
    /\ directory' = (IF a \in retiring THEN directory \ {a} ELSE directory)
    /\ UNCHANGED <<resident,dirty,evictions>>
MarkDirty(a) == /\ a \in resident /\ a \notin dirty
                /\ dirty' = dirty \cup {a} /\ UNCHANGED <<resident,directory,evictions,retiring>>
TypeOK == /\ resident \subseteq Lines /\ directory \subseteq Lines /\ dirty \subseteq resident
          /\ retiring \subseteq directory
          /\ evictions \in [EvictionQueues -> Seq(Messages)]
          /\ \A q \in EvictionQueues: Len(evictions[q]) <= EvictionDepth
Conservation == /\ directory = resident \cup Queued \cup retiring /\ resident \cap Queued = {}
                /\ retiring \cap (resident \cup Queued) = {}
                /\ \A s \in Sets: Cardinality(directory \cap Entries(s)) <= Ways
                /\ Cardinality(Queued) = Cardinality(UNION
                     {{<<q,i>>: i \in 1..Len(evictions[q])}: q \in EvictionQueues})
=============================================================================
