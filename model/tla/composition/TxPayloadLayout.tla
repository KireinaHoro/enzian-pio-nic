--------------------------- MODULE TxPayloadLayout ---------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS BadRestore, BadLateWrite, BadPreempt
\* One 64-byte inline beat aliases overflow beat 1. Two 128-byte overflow
\* lines model the boundary: lengths are 64-byte units, capacity selects placement.
\* Writeback actions are completed AXI writes, not AW acceptance. Required-line
\* invalidation waits for those writes; unused dirty lines may write back later.
VARIABLES phase, capacity, length, cancel, packet, saved, dirty, committed, sent
vars == <<phase, capacity, length, cancel, packet, saved, dirty, committed, sent>>
Beats == 1..4
Lines == 1..2
Line(b) == ((b - 1) \div 2) + 1
Inline == capacity <= 1
Required == IF cancel \/ Inline THEN {} ELSE {Line(b): b \in 1..length}
Expected(b) == IF Inline THEN 9 ELSE 10 + b
Init == /\ phase = "Host"
        /\ capacity \in 0..4 /\ length \in 0..capacity /\ cancel \in BOOLEAN
        /\ packet = [b \in Beats |-> 0] /\ saved = 0
        /\ dirty = Lines /\ committed = FALSE /\ sent = FALSE
\* Control can be evicted before the doorbell, then overflow can overwrite its
\* physical alias. Saved control payload must survive both writeback and reload.
Control == /\ phase = "Host" /\ saved = 0
           /\ saved' = 9
           /\ packet' = IF Inline THEN [packet EXCEPT ![1] = 9] ELSE packet
           /\ UNCHANGED <<phase, capacity, length, cancel, dirty, committed, sent>>
Writeback(l) ==
    /\ phase \in {"Host", "Invalidate", "Restore", "Send", "Done"}
    /\ l \in dirty
    /\ packet' = IF committed /\ ~BadLateWrite THEN packet ELSE
         [b \in Beats |-> IF Line(b) = l THEN 10 + b ELSE packet[b]]
    /\ dirty' = dirty \ {l}
    /\ UNCHANGED <<phase, capacity, length, cancel, saved, committed, sent>>
Doorbell == /\ phase = "Host" /\ saved = 9 /\ phase' = "Invalidate"
            /\ UNCHANGED <<capacity, length, cancel, packet, saved, dirty, committed, sent>>
Invalidated == /\ phase = "Invalidate" /\ dirty \cap Required = {}
               /\ phase' = "Restore" /\ committed' = Inline
               /\ UNCHANGED <<capacity, length, cancel, packet, saved, dirty, sent>>
Restore == /\ phase = "Restore"
           /\ packet' = IF Inline /\ ~BadRestore THEN [packet EXCEPT ![1] = saved] ELSE packet
           /\ phase' = "Send"
           /\ UNCHANGED <<capacity, length, cancel, saved, dirty, committed, sent>>
Complete == /\ phase = "Send" /\ phase' = "Done" /\ sent' = ~cancel
            /\ committed' = FALSE
            /\ UNCHANGED <<capacity, length, cancel, packet, saved, dirty>>
\* Full cleanup before another owner: normal transaction completion deliberately
\* leaves unused cache lines resident. Abstract drain+scrub as one atomic action.
Preempt == /\ phase = "Done" /\ phase' = "NewOwner"
           /\ packet' = IF BadPreempt THEN packet ELSE [b \in Beats |-> 0]
           /\ dirty' = IF BadPreempt THEN dirty ELSE {}
           /\ saved' = IF BadPreempt THEN saved ELSE 0
           /\ UNCHANGED <<capacity, length, cancel, committed, sent>>
Next == Control \/ Doorbell \/ Invalidated \/ Restore \/ Complete \/ Preempt
        \/ (\E l \in Lines: Writeback(l))
TypeOK == /\ phase \in {"Host", "Invalidate", "Restore", "Send", "Done", "NewOwner"}
          /\ capacity \in 0..4 /\ length \in 0..capacity /\ cancel \in BOOLEAN
          /\ packet \in [Beats -> 0..14] /\ saved \in {0,9}
          /\ dirty \subseteq Lines /\ committed \in BOOLEAN /\ sent \in BOOLEAN
PayloadCorrect == phase = "Send" /\ ~cancel =>
                     \A b \in 1..length: packet[b] = Expected(b)
CancelNoPacket == cancel => ~sent
OwnerClean == phase = "NewOwner" =>
                (dirty = {} /\ saved = 0 /\ \A b \in Beats: packet[b] = 0)
Spec == Init /\ [][Next]_vars
=============================================================================
