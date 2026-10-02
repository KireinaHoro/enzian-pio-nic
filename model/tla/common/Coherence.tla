---------------------------- MODULE Coherence ----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Clients, Addresses
Read(c, a) == [type |-> "Read", txn |-> c, address |-> a]
Invalidate(c, a) == [type |-> "Invalidate", txn |-> c, address |-> a]
ReadMessages == [type : {"Read"}, txn : Clients, address : Addresses]
InvalidationMessages == [type : {"Invalidate", "Ack"}, txn : Clients, address : Addresses]
Messages == ReadMessages \cup InvalidationMessages
=============================================================================
