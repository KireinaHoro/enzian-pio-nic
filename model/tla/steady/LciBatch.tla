------------------------------ MODULE LciBatch ------------------------------
EXTENDS Naturals, Sequences
CONSTANT TargetLines
VARIABLES batchRemaining, batchWaiting
vars == <<batchRemaining,batchWaiting>>
Init == /\ batchRemaining = TargetLines /\ batchWaiting = FALSE
Done == batchRemaining = <<>>
Target == Head(batchRemaining)
Submit == /\ ~Done /\ ~batchWaiting /\ batchWaiting' = TRUE
          /\ UNCHANGED batchRemaining
Ack == /\ batchWaiting /\ batchRemaining' = Tail(batchRemaining)
       /\ batchWaiting' = FALSE
TypeOK == /\ batchWaiting \in BOOLEAN /\ (batchWaiting => ~Done)
          /\ \E i \in 1..(Len(TargetLines)+1):
                batchRemaining = SubSeq(TargetLines,i,Len(TargetLines))
=============================================================================
