-------------------------- MODULE ProgressSystem --------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Clients, Addresses, Colors, ColorOf, Directions, Stalled, Control,
          Dependencies, Queues, QueueOf, QueueDepth, Pools, PoolOf, Slots,
          SharedAck, Slices, SliceOf, ReadSlots
N == Cardinality(Clients)
VARIABLES reads, invQ, held, acknowledgments, phase, parity, direction, remaining, completed, unlockQ
v == <<reads, invQ, held, acknowledgments, phase, parity, direction, remaining, completed, unlockQ>>
D == INSTANCE DcsResources WITH reads <- reads, invQ <- invQ
T == INSTANCE ThxResources WITH held <- held, acknowledgments <- acknowledgments
C == INSTANCE TwoFClient WITH phase <- phase, parity <- parity, direction <- direction, remaining <- remaining, completed <- completed
V == INSTANCE Coherence
Init == D!Init /\ T!Init /\ C!Init /\ unlockQ = <<>>
Admit(c) == /\ D!ReceiveRead(V!Read(c,C!Door(c))) /\ T!ReceiveRead(c) /\ C!Accepted(c) /\ UNCHANGED unlockQ
Work(c) == /\ C!Work(c) /\ UNCHANGED <<reads,invQ,held,acknowledgments,unlockQ>>
Inv(c) == /\ C!SubmittedInv(c) /\ D!SendInvalidate(V!Invalidate(c,C!Target(c)))
          /\ UNCHANGED <<held,acknowledgments,unlockQ>>
Process(q) == /\ D!Process(q) /\ UNCHANGED <<held,acknowledgments,phase,parity,direction,remaining,completed,unlockQ>>
Ack(c) == /\ phase[c] = "WaitAck" /\ D!HasAck(c,C!Target(c))
          /\ T!ReceiveAck(c) /\ C!Acked(c) /\ UNCHANGED <<reads,invQ,unlockQ>>
Unlock(c) == /\ C!Unlocked(c) /\ Len(unlockQ) < N
             /\ unlockQ' = Append(unlockQ, V!Unlock(c, C!Target(c)))
             /\ UNCHANGED <<reads,invQ,held,acknowledgments>>
DeliverUnlock == /\ Len(unlockQ) > 0
                 /\ D!ReceiveUnlock(Head(unlockQ).txn, Head(unlockQ).address)
                 /\ T!SendUnlock(Head(unlockQ).txn)
                 /\ unlockQ' = Tail(unlockQ)
                 /\ UNCHANGED <<phase,parity,direction,remaining,completed>>
Return(c,d) == /\ C!Returned(c,d) /\ D!CompleteRead(V!Read(c,C!Door(c))) /\ T!CompleteRead(c) /\ UNCHANGED unlockQ
Next == \/ DeliverUnlock
        \/ \E c \in Clients: Admit(c) \/ Work(c) \/ Inv(c) \/ Ack(c) \/ Unlock(c)
        \/ \E c \in Clients, d \in Directions: Return(c,d)
        \/ \E q \in Queues: Process(q)
Spec == Init /\ [][Next]_v /\ WF_v(DeliverUnlock)
        /\ (\A c \in Clients \ Stalled: SF_v(Admit(c)) /\ WF_v(Work(c)) /\ SF_v(Inv(c)) /\ SF_v(Ack(c)) /\ WF_v(Unlock(c)))
        /\ (\A c \in Clients \ Stalled, d \in Directions: WF_v(Return(c,d)))
        /\ (\A q \in Queues: WF_v(Process(q)))
TypeOK == /\ D!TypeOK /\ T!TypeOK /\ C!TypeOK
          /\ unlockQ \in Seq([type : {"Unlock"}, txn : Clients, address : Addresses])
          /\ Len(unlockQ) <= N
Tracking == /\ held = {c \in Clients: phase[c] # "Submit"}
            /\ \A c \in held: reads[ColorOf[C!Door(c)]] = c
            /\ \A col \in Colors: reads[col] # 0 => reads[col] \in held
            /\ acknowledgments = {c \in Clients: phase[c] = "Unlock"} \cup {unlockQ[i].txn: i \in 1..Len(unlockQ)}
            /\ Cardinality(acknowledgments) = Cardinality({c \in Clients: phase[c] = "Unlock"}) + Len(unlockQ)
            /\ Cardinality(UNION {{<<q,i>>: i \in 1..Len(invQ[q])}: q \in Queues}) =
                Cardinality({c \in Clients: phase[c] \in {"WaitAck", "Unlock"}}) + Len(unlockQ)
            /\ \A q \in Queues: \A i \in 1..Len(invQ[q]):
                LET m == invQ[q][i] IN
                \/ /\ phase[m.txn] \in {"WaitAck", "Unlock"}
                   /\ m.address = C!Target(m.txn)
                \/ /\ m.type = "Ack"
                   /\ \E j \in 1..Len(unlockQ): unlockQ[j] = V!Unlock(m.txn, m.address)
Conservation == D!Conservation /\ T!Conservation /\ Cardinality(held) = Cardinality({col \in Colors: reads[col] # 0})
\* Fixed assignment/retired previous owners are constants and environmental
\* entry assumptions, not properties proved by this steady-state model.
Footprint(c,d) == {Control[c][d][p]: p \in 0..1} \cup
    UNION {{Dependencies[c][d][p][i]: i \in 1..Len(Dependencies[c][d][p])}: p \in 0..1}
CompatiblePlacement ==
    /\ \A c \in Clients, d \in Directions, p \in 0..1:
        /\ ColorOf[Control[c][d][p]] # ColorOf[Control[c][d][1-p]]
        /\ \A i \in 1..Len(Dependencies[c][d][p]):
            ColorOf[Control[c][d][p]] # ColorOf[Dependencies[c][d][p][i]]
    /\ \A c,e \in Clients, d,f \in Directions, p \in 0..1:
        c # e => (\A a \in Footprint(e,f): ColorOf[Control[c][d][p]] # ColorOf[a])
Settled == TypeOK /\ Tracking /\ Conservation /\ CompatiblePlacement
\* Diagnostic for cycles of retained doorbells blocking invalidations.
\* Shared-credit starvation and queue HoL need liveness, not this diagnostic.
ReadWaits == {<<c, reads[ColorOf[C!Target(c)]]>> :
              c \in {x \in Clients: phase[x] = "WaitAck"}}
Successors(S) == {e[2]: e \in {edge \in ReadWaits: edge[1] \in S /\ edge[2] # 0}}
RECURSIVE Reach(_, _)
Reach(S, n) == IF n = 0 THEN {} ELSE Successors(S) \cup Reach(Successors(S), n-1)
NoReadDependencyCycle == \A c \in Clients: c \notin Reach({c}, N)
Progress == \A c \in Clients \ Stalled: ([]<>completed[c]) /\ ([]<>~completed[c])
Admission == \A c \in Clients \ Stalled: []<>(phase[c] = "Held")
=============================================================================
