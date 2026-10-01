---------------------------- MODULE ReadResponses ----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Clients, Addresses, Values, ResponseDepth
VARIABLE responses
vars == <<responses>>
Messages == [type : {"ReadData"}, txn : Clients, address : Addresses, value : Values]
Init == responses = <<>>
Send(m) == /\ m \in Messages /\ Len(responses) < ResponseDepth
           /\ responses' = Append(responses,m)
Receive(m) == /\ Len(responses) > 0 /\ Head(responses) = m
              /\ responses' = Tail(responses)
Pending(a) == \E i \in 1..Len(responses): responses[i].address = a
TypeOK == responses \in Seq(Messages)
Conservation == Len(responses) <= ResponseDepth
\* The caller must not reuse a transaction identity before its response retires.
UniqueTransactions == Cardinality({responses[i].txn: i \in 1..Len(responses)}) = Len(responses)
=============================================================================
