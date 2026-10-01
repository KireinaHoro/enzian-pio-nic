-------------------------- MODULE ProgressSystem --------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Clients, Addresses, Colors, ColorOf, Directions, Stalled, Control,
          Dependencies, AllowedDirections, Queues, QueueOf, QueueDepth, Pools, PoolOf, Slots,
          SharedAck, Slices, SliceOf, ReadSlots
N == Cardinality(Clients)
VARIABLES reads, invQ, held, phase, parity, direction, remaining, completed
v == <<reads, invQ, held, phase, parity, direction, remaining, completed>>
D == INSTANCE DcsResources WITH reads <- reads, invQ <- invQ
T == INSTANCE ThxResources WITH held <- held
C == INSTANCE TwoFClient WITH phase <- phase, parity <- parity, direction <- direction, remaining <- remaining, completed <- completed
V == INSTANCE Coherence
Init == D!Init /\ T!Init /\ C!Init
Admit(c) == /\ D!ReceiveRead(V!Read(c,C!Door(c))) /\ T!ReceiveRead(c) /\ C!Accepted(c)
Work(c) == /\ C!Work(c) /\ UNCHANGED <<reads,invQ,held>>
Inv(c) == /\ C!SubmittedInv(c) /\ D!SendInvalidate(V!Invalidate(c,C!Target(c)))
          /\ UNCHANGED held
Process(q) == /\ D!Process(q) /\ UNCHANGED <<held,phase,parity,direction,remaining,completed>>
\* LCIA acceptance completes the dependency and releases its response resource.
Ack(c) == /\ phase[c] = "WaitAck" /\ D!ReceiveAck(c,C!Target(c))
          /\ T!CompleteAck(c) /\ C!InvalidationCompleted(c)
Return(c,d) == /\ C!Returned(c,d) /\ D!CompleteRead(V!Read(c,C!Door(c))) /\ T!CompleteRead(c)
Next == \/ \E c \in Clients: Admit(c) \/ Work(c) \/ Inv(c) \/ Ack(c)
        \/ \E c \in Clients, d \in Directions: Return(c,d)
        \/ \E q \in Queues: Process(q)
\* Only non-stalled clients enqueue invalidations at their dependency addresses.
ServiceQueues == UNION {UNION {UNION {
    {QueueOf[ColorOf[Dependencies[c][d][p][i]]]: i \in 1..Len(Dependencies[c][d][p])}
    : p \in 0..1}: d \in AllowedDirections[c]}: c \in Clients \ Stalled}
UnusedQueuesEmpty == \A q \in Queues \ ServiceQueues: invQ[q] = <<>>
Spec == Init /\ [][Next]_v
        /\ (\A c \in Clients \ Stalled: SF_v(Admit(c)) /\ WF_v(Work(c)) /\ SF_v(Inv(c)) /\ SF_v(Ack(c)))
        /\ (\A c \in Clients \ Stalled: \A d \in AllowedDirections[c]: WF_v(Return(c,d)))
        /\ (\A q \in ServiceQueues: WF_v(Process(q)))
TypeOK == D!TypeOK /\ T!TypeOK /\ C!TypeOK
\* Transport quiescence does not imply clean/absent cached lines.
Quiet(c) == /\ phase[c] = "Submit" /\ c \notin held
            /\ \A q \in Queues: \A i \in 1..Len(invQ[q]): invQ[q][i].txn # c
Tracking == /\ held = {c \in Clients: phase[c] # "Submit"}
            /\ \A c \in held: reads[ColorOf[C!Door(c)]] = <<V!Read(c, C!Door(c))>>
            /\ \A col \in Colors: Len(reads[col]) > 0 => Head(reads[col]).txn \in held
            /\ Cardinality(UNION {{<<q,i>>: i \in 1..Len(invQ[q])}: q \in Queues}) =
                Cardinality({c \in Clients: phase[c] = "WaitAck"})
            /\ \A q \in Queues: \A i \in 1..Len(invQ[q]):
                LET m == invQ[q][i] IN phase[m.txn] = "WaitAck" /\ m.address = C!Target(m.txn)
Conservation == D!Conservation /\ T!Conservation /\ Cardinality(held) = Cardinality({col \in Colors: Len(reads[col]) > 0})
\* Fixed assignment/retired previous owners are constants and environmental
\* entry assumptions, not properties proved by this steady-state model.
Footprint(c,d) == {Control[c][d][p]: p \in 0..1} \cup
    UNION {{Dependencies[c][d][p][i]: i \in 1..Len(Dependencies[c][d][p])}: p \in 0..1}
CompatiblePlacementFor(cs) ==
    /\ \A c \in cs, d \in Directions, p \in 0..1:
        /\ ColorOf[Control[c][d][p]] # ColorOf[Control[c][d][1-p]]
        /\ \A i \in 1..Len(Dependencies[c][d][p]):
            ColorOf[Control[c][d][p]] # ColorOf[Dependencies[c][d][p][i]]
    /\ \A c,e \in cs, d,f \in Directions, p \in 0..1:
        c # e => (\A a \in Footprint(e,f): ColorOf[Control[c][d][p]] # ColorOf[a])
CompatiblePlacement == CompatiblePlacementFor(Clients)
SettledFor(cs) == /\ TypeOK /\ Tracking /\ Conservation /\ CompatiblePlacementFor(cs)
    /\ UnusedQueuesEmpty /\ held \subseteq cs
    /\ \A c \in Clients \ cs: Quiet(c)
Settled == TypeOK /\ Tracking /\ Conservation /\ CompatiblePlacement /\ UnusedQueuesEmpty
\* Diagnostic for cycles of retained doorbells blocking invalidations.
\* Shared-credit starvation and queue HoL need liveness, not this diagnostic.
ReadHolders(c) == LET r == reads[ColorOf[C!Target(c)]] IN {r[i].txn: i \in 1..Len(r)}
ReadWaits == UNION {{<<c,h>>: h \in ReadHolders(c)}:
                    c \in {x \in Clients: phase[x] = "WaitAck"}}
Successors(S) == {e[2]: e \in {edge \in ReadWaits: edge[1] \in S}}
RECURSIVE Reach(_, _)
Reach(S, n) == IF n = 0 THEN {} ELSE Successors(S) \cup Reach(Successors(S), n-1)
NoReadDependencyCycle == \A c \in Clients: c \notin Reach({c}, N)
ProgressFor(cs) == \A c \in cs: ([]<>completed[c]) /\ ([]<>~completed[c])
AdmissionFor(cs) == \A c \in cs: []<>(phase[c] = "Held")
Progress == ProgressFor(Clients \ Stalled)
Admission == AdmissionFor(Clients \ Stalled)
=============================================================================
