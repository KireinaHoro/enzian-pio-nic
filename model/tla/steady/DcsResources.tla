-------------------------- MODULE DcsResources --------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Clients, Addresses, Colors, ColorOf, Queues, QueueOf, QueueDepth, Slices, SliceOf, ReadSlots
VARIABLES reads, invQ
vars == <<reads, invQ>>
V == INSTANCE Coherence
Messages == V!InvalidationMessages
ReadMessages == V!ReadMessages
Init == /\ reads = [c \in Colors |-> <<>>]
        /\ invQ = [q \in Queues |-> <<>>]
TypeOK == /\ reads \in [Colors -> Seq(ReadMessages)]
          /\ \A c \in Colors: Len(reads[c]) <= 1
          /\ invQ \in [Queues -> Seq(Messages)]
          /\ \A q \in Queues: Len(invQ[q]) <= QueueDepth
ReadUse(s) == Cardinality({c \in Colors: SliceOf[c] = s /\ Len(reads[c]) > 0})
Conservation == \A s \in Slices: ReadUse(s) <= ReadSlots
CanRead(m) == /\ Len(reads[ColorOf[m.address]]) = 0
              /\ ReadUse(SliceOf[ColorOf[m.address]]) < ReadSlots
              /\ \A q \in Queues: \A i \in 1..Len(invQ[q]):
                  ~(invQ[q][i].type = "Ack" /\ invQ[q][i].address = m.address)
ReceiveRead(m) == /\ CanRead(m)
                  /\ reads' = [reads EXCEPT ![ColorOf[m.address]] = <<m>>]
                  /\ UNCHANGED invQ
CompleteRead(m) == /\ reads[ColorOf[m.address]] = <<m>>
                   /\ reads' = [reads EXCEPT ![ColorOf[m.address]] = <<>>]
                   /\ UNCHANGED invQ
SendInvalidate(m) ==
    LET q == QueueOf[ColorOf[m.address]] IN
    /\ Len(invQ[q]) < QueueDepth
    /\ invQ' = [invQ EXCEPT ![q] = Append(@, m)]
    /\ UNCHANGED reads
Process(q) ==
    /\ Len(invQ[q]) > 0
    /\ Head(invQ[q]).type = "Invalidate"
    /\ Len(reads[ColorOf[Head(invQ[q]).address]]) = 0
    /\ invQ' = [invQ EXCEPT ![q][1].type = "Ack"]
    /\ UNCHANGED reads
HasAck(c, a) == LET q == QueueOf[ColorOf[a]] IN
    /\ Len(invQ[q]) > 0
    /\ Head(invQ[q]) = [type |-> "Ack", txn |-> c, address |-> a]
ReceiveAck(c, a) == LET q == QueueOf[ColorOf[a]] IN
    /\ HasAck(c, a)
    /\ invQ' = [invQ EXCEPT ![q] = Tail(@)]
    /\ UNCHANGED reads
=============================================================================
