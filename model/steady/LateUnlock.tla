---------------------------- MODULE LateUnlock ----------------------------
\* Component boundary check: a completed TX leaves an unlock queued before
\* the same worker admits an indefinitely held RX. The peer is a minimal
\* read client, not a full 2F2F transport. No stalled-worker action is fair.
EXTENDS Naturals, Sequences, FiniteSets
Clients == {0,1}
OldTX == "0x201300"
StalledRX == "0x200300"
PeerRX == "0x4300"
Addresses == {OldTX, StalledRX, PeerRX}
Colors == 0..63
ColorOf == [a \in Addresses |-> CASE a = OldTX -> 45
                                  [] a = StalledRX -> 4
                                  [] a = PeerRX -> 2]
Queues == Colors
QueueOf == [c \in Colors |-> c]
QueueDepth == 1
Slices == {0,1}
SliceOf == [c \in Colors |-> c % 2]
ReadSlots == 2
Pools == {0}
PoolOf == [c \in Clients |-> 0]
Slots == 2
SharedAck == TRUE
VARIABLES reads, invQ, held, acknowledgments, oldPending, peerHeld, completed
vars == <<reads, invQ, held, acknowledgments, oldPending, peerHeld, completed>>
D == INSTANCE DcsResources WITH reads <- reads, invQ <- invQ
T == INSTANCE ThxResources WITH held <- held, acknowledgments <- acknowledgments
V == INSTANCE Coherence
OldAck == [type |-> "Ack", txn |-> 0, address |-> OldTX]
Init == /\ reads = [c \in Colors |-> IF c = ColorOf[StalledRX] THEN <<V!Read(0,StalledRX)>> ELSE <<>>]
        /\ invQ = [q \in Queues |-> IF q = ColorOf[OldTX] THEN <<OldAck>> ELSE <<>>]
        /\ held = {0} /\ acknowledgments = {0}
        /\ oldPending = TRUE /\ peerHeld = FALSE /\ completed = FALSE
\* oldPending represents the one retained Unlock(0, OldTX) at the queue head.
DeliverOldUnlock == /\ oldPending
                    /\ D!ReceiveUnlock(0,OldTX) /\ T!SendUnlock(0)
                    /\ oldPending' = FALSE /\ UNCHANGED <<peerHeld,completed>>
AdmitPeer == /\ ~peerHeld /\ D!ReceiveRead(V!Read(1,PeerRX)) /\ T!ReceiveRead(1)
             /\ peerHeld' = TRUE /\ UNCHANGED <<oldPending,completed>>
ReturnPeer == /\ peerHeld /\ D!CompleteRead(V!Read(1,PeerRX)) /\ T!CompleteRead(1)
              /\ peerHeld' = FALSE /\ completed' = ~completed /\ UNCHANGED oldPending
Next == DeliverOldUnlock \/ AdmitPeer \/ ReturnPeer
Spec == Init /\ [][Next]_vars /\ WF_vars(DeliverOldUnlock)
        /\ WF_vars(AdmitPeer) /\ WF_vars(ReturnPeer)
Safety == /\ D!TypeOK /\ D!Conservation /\ T!TypeOK /\ T!Conservation
          /\ oldPending \in BOOLEAN /\ peerHeld \in BOOLEAN /\ completed \in BOOLEAN
          /\ reads[ColorOf[StalledRX]] = <<V!Read(0,StalledRX)>>
          /\ held = IF peerHeld THEN {0,1} ELSE {0}
          /\ acknowledgments = IF oldPending THEN {0} ELSE {}
          /\ invQ[ColorOf[OldTX]] = IF oldPending THEN <<OldAck>> ELSE <<>>
OldUnlockRetired == <>~oldPending
Progress == ([]<>completed) /\ ([]<>~completed)
Admission == []<>peerHeld
=============================================================================
