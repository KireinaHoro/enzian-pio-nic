---------------------------- MODULE Coherence ----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Clients, Addresses
Read(c, a) == [type |-> "Read", txn |-> c, address |-> a]
Invalidate(c, a) == [type |-> "Invalidate", txn |-> c, address |-> a]
Unlock(c, a) == [type |-> "Unlock", txn |-> c, address |-> a]
Messages == [type : {"Read", "Invalidate", "Ack", "Unlock"}, txn : Clients, address : Addresses]
=============================================================================
