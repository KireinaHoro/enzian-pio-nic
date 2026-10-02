-------------------------- MODULE InvalidationPort --------------------------
EXTENDS Naturals, Sequences
CONSTANTS Clients, Addresses, Queues, QueueDepth
VARIABLE queues
V == INSTANCE Coherence
vars == <<queues>>
Init == queues = [q \in Queues |-> <<>>]
TypeOK == /\ queues \in [Queues -> Seq(V!InvalidationMessages)]
          /\ \A q \in Queues: Len(queues[q]) <= QueueDepth
Submit(q,m) == /\ m \in V!InvalidationMessages /\ m.type = "Invalidate"
               /\ Len(queues[q]) < QueueDepth
               /\ queues' = [queues EXCEPT ![q] = Append(@,m)]
\* Abstract completion certificate: all required lower-level response/data
\* service has finished. This action is NOT an implementation of that service.
Complete(q) == /\ Len(queues[q]) > 0 /\ Head(queues[q]).type = "Invalidate"
               /\ queues' = [queues EXCEPT ![q][1].type = "Ack"]
Accept(q,m) == /\ Len(queues[q]) > 0 /\ Head(queues[q]) = m /\ m.type = "Ack"
               /\ queues' = [queues EXCEPT ![q] = Tail(@)]
Next == \E q \in Queues: Complete(q) \/ (\E m \in V!InvalidationMessages: Submit(q,m) \/ Accept(q,m))
Behavior == Init /\ [][Next]_vars
\* Eligibility (read/color conflicts, downstream acceptance) belongs to the
\* caller. It must apply fairness to its guarded Complete, never to this action
\* before those guards are added. No fairness is embedded in this component.
=============================================================================
