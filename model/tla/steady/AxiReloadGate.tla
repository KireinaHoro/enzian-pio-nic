--------------------------- MODULE AxiReloadGate ---------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS BadBypass, BadReleaseGate
VARIABLES queue, invalidated, acked, published, cache, reads
vars == <<queue, invalidated, acked, published, cache, reads>>
Lines == {0, 1}
(* One transition: door read is already the serial AXI head. Later reloads
   can target either retired control or overflow. Generation values are ghosts.
   LCIA service must not require the blocked AXI read to complete. *)
Init == /\ queue \in {<<[type |-> "Door", line |-> 0],
                         [type |-> "Reload", line |-> l]>> : l \in Lines}
        /\ invalidated = {} /\ acked = {} /\ published = FALSE
        /\ cache = [l \in Lines |-> 0] /\ reads = 0
Invalidate(l) == /\ l \in Lines \ invalidated
                 /\ invalidated' = invalidated \cup {l}
                 /\ cache' = [cache EXCEPT ![l] = 2]
                 /\ UNCHANGED <<queue, acked, published, reads>>
Ack(l) == /\ l \in invalidated \ acked
          /\ (~BadReleaseGate \/ Len(queue) = 0)
          /\ acked' = acked \cup {l}
          /\ UNCHANGED <<queue, invalidated, published, cache, reads>>
Publish == /\ ~published /\ acked = Lines
           /\ published' = TRUE
           /\ UNCHANGED <<queue, invalidated, acked, cache, reads>>
Door == /\ Len(queue) > 0 /\ Head(queue).type = "Door" /\ published
        /\ queue' = Tail(queue)
        /\ reads' = reads + 1
        /\ UNCHANGED <<invalidated, acked, published, cache>>
Reload(i) == /\ i \in 1..Len(queue) /\ queue[i].type = "Reload"
             /\ (i = 1 \/ BadBypass)
             /\ cache' = [cache EXCEPT ![queue[i].line] = IF published THEN 1 ELSE 0]
             /\ queue' = SubSeq(queue, 1, i-1) \o SubSeq(queue, i+1, Len(queue))
             /\ reads' = reads + 1
             /\ UNCHANGED <<invalidated, acked, published>>
Next == \/ \E l \in Lines: Invalidate(l) \/ Ack(l)
        \/ Publish \/ Door \/ \E i \in 1..Len(queue): Reload(i)
Spec == Init /\ [][Next]_vars
        /\ (\A l \in Lines: WF_vars(Invalidate(l)) /\ WF_vars(Ack(l)))
        /\ WF_vars(Publish) /\ WF_vars(Door) /\ WF_vars(Reload(1))
TypeOK == /\ invalidated \subseteq Lines /\ acked \subseteq invalidated
          /\ cache \in [Lines -> 0..2] /\ published \in BOOLEAN
          /\ reads \in 0..2 /\ Len(queue) + reads = 2
NoStaleRefill == published => \A l \in Lines: cache[l] # 0
Completes == <> (reads = 2)
=============================================================================
