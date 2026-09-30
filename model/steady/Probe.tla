------------------------------- MODULE Probe -------------------------------
\* A second, non-2F2F client: an unrelated invalidation probe must complete
\* while an older probe to a permanently occupied read resource cannot run.
EXTENDS Naturals, Sequences
CONSTANT SharedQueue
Clients == {1,2}
Addresses == {0,1}
Colors == {0,1}
ColorOf == [a \in Addresses |-> a]
Queues == IF SharedQueue THEN {0} ELSE Colors
QueueOf == [c \in Colors |-> IF SharedQueue THEN 0 ELSE c]
QueueDepth == 2
Slices == {0}
SliceOf == [c \in Colors |-> 0]
ReadSlots == 2
VARIABLES reads, invQ, stage, done
D == INSTANCE DcsResources WITH reads <- reads, invQ <- invQ
vars == <<reads, invQ, stage, done>>
Init == /\ D!Init /\ stage = 0 /\ done = FALSE
Occupy == /\ stage = 0
          /\ D!ReceiveRead([type |-> "Read",txn |-> 1,address |-> 0])
          /\ stage' = 1 /\ UNCHANGED done
BlockedProbe == /\ stage = 1
                /\ D!SendInvalidate([type |-> "Invalidate",txn |-> 1,address |-> 0])
                /\ stage' = 2 /\ UNCHANGED done
Submit == /\ stage = 2
          /\ D!SendInvalidate([type |-> "Invalidate",txn |-> 2,address |-> 1])
          /\ stage' = 3 /\ UNCHANGED done
Service(q) == /\ D!Process(q) /\ UNCHANGED <<stage,done>>
Finish == /\ stage = 3 /\ D!ReceiveUnlock(2,1)
          /\ stage' = 2 /\ done' = ~done
Next == Occupy \/ BlockedProbe \/ Submit \/ Finish \/ (\E q \in Queues: Service(q))
Spec == Init /\ [][Next]_vars /\ WF_vars(Occupy) /\ WF_vars(BlockedProbe)
        /\ WF_vars(Submit) /\ WF_vars(Finish) /\ (\A q \in Queues: WF_vars(Service(q)))
TypeOK == D!TypeOK /\ stage \in 0..3 /\ done \in BOOLEAN
Progress == ([]<>done) /\ ([]<>~done)
=============================================================================
