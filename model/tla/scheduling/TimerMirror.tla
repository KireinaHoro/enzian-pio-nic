----------------------------- MODULE TimerMirror -----------------------------
EXTENDS Naturals, Sequences
CONSTANT BadOrder
\* One arm, replacement and shutdown; commands are ordered records, not a set.
Commands == <<[type |-> "Arm", generation |-> 1],
              [type |-> "Arm", generation |-> 2],
              [type |-> "Disable", generation |-> 3]>>
VARIABLES submitted, applied, queue, osGeneration, nicGeneration, enabled
v == <<submitted,applied,queue,osGeneration,nicGeneration,enabled>>
Init == /\ submitted = 0 /\ applied = 0 /\ queue = <<>>
        /\ osGeneration = 0 /\ nicGeneration = 0 /\ enabled = FALSE
Submit == /\ submitted < Len(Commands)
          /\ submitted' = submitted+1 /\ osGeneration' = submitted+1
          /\ queue' = Append(queue, Commands[submitted+1])
          /\ UNCHANGED <<applied,nicGeneration,enabled>>
Apply == LET i == IF BadOrder THEN Len(queue) ELSE 1 IN
    /\ Len(queue) > 0 /\ nicGeneration' = queue[i].generation
    /\ enabled' = (queue[i].type = "Arm") /\ applied' = applied+1
    /\ queue' = IF BadOrder THEN SubSeq(queue,1,Len(queue)-1) ELSE Tail(queue)
    /\ UNCHANGED <<submitted,osGeneration>>
Next == Submit \/ Apply
Spec == Init /\ [][Next]_v /\ WF_v(Apply)
TypeOK == /\ submitted \in 0..3 /\ applied \in 0..submitted
          /\ osGeneration = submitted /\ nicGeneration \in 0..3 /\ enabled \in BOOLEAN
          /\ Len(queue) = submitted-applied
MirrorOrder == nicGeneration = applied
MirrorCatchesUp == []<>(Len(queue) = 0 /\ nicGeneration = osGeneration)
Shutdown == submitted = 3 /\ Len(queue) = 0 => ~enabled
=============================================================================
