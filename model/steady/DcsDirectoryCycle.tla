-------------------------- MODULE DcsDirectoryCycle --------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Ways, TargetPresent, ReleaseVictim
Clients == {0}
Addresses == 0..Ways
Colors == {0}
ColorOf == [a \in Addresses |-> 0]
Queues == {0}
QueueOf == [s \in Colors |-> 0]
QueueDepth == 1
Slices == {0}
SliceOf == [s \in Colors |-> 0]
ReadSlots == 1
Lines == Addresses
Sets == Colors
SetOf == ColorOf
EvictionQueues == {0}
EvictionQueueOf == QueueOf
EvictionDepth == 1
AllocateOnInvalidate == TRUE
VARIABLES resident,directory,dirty,evictions,retiring,reads,invQ,stage,fill
R == INSTANCE RetainedCache
D == INSTANCE DcsResources
cache == <<resident,directory,dirty,evictions,retiring>>
transport == <<reads,invQ>>
v == <<cache,transport,stage,fill>>
Init == /\ resident = {} /\ R!Init /\ D!Init /\ stage = "Fill" /\ fill = 0
Fill == /\ stage = "Fill" /\ R!Fill(fill) /\ fill' = fill + 1
        /\ stage' = IF fill = Ways-1 THEN (IF TargetPresent THEN "Submit" ELSE "Evict") ELSE "Fill"
        /\ UNCHANGED transport
Evict == /\ stage = "Evict" /\ R!Evict(0) /\ stage' = "Deliver"
         /\ UNCHANGED <<transport,fill>>
Deliver == /\ stage = "Deliver" /\ R!Deliver(0) /\ stage' = "Replace"
           /\ UNCHANGED <<transport,fill>>
Replace == /\ stage = "Replace" /\ R!Fill(Ways) /\ stage' = "Submit"
           /\ UNCHANGED <<transport,fill>>
Submit == /\ stage = "Submit"
          /\ D!SendInvalidate([type |-> "Invalidate", txn |-> 0, address |-> 0])
          /\ stage' = "Wait" /\ UNCHANGED <<cache,fill>>
Process == /\ stage = "Wait" /\ D!Process(0) /\ R!Invalidate(0)
           /\ stage' = "Unlock" /\ UNCHANGED fill
Unlock == /\ stage = "Unlock" /\ D!ReceiveUnlock(0,0) /\ R!ReleaseInvalidation(0)
          /\ stage' = "Done" /\ UNCHANGED fill
\* Recovery configuration explicitly supplies an independent CPU victim;
\* no such event is assumed in the full-miss counterexample.
Victim == /\ ReleaseVictim /\ stage = "Wait" /\ R!Evict(1)
          /\ UNCHANGED <<transport,stage,fill>>
VictimDelivery == /\ stage = "Wait" /\ R!Deliver(0)
                  /\ UNCHANGED <<transport,stage,fill>>
Next == Fill \/ Evict \/ Deliver \/ Replace \/ Submit \/ Process \/ Unlock \/ Victim \/ VictimDelivery
Spec == Init /\ [][Next]_v
        /\ WF_v(Fill) /\ WF_v(Evict) /\ WF_v(Deliver) /\ WF_v(Replace)
        /\ WF_v(Submit) /\ WF_v(Process) /\ WF_v(Unlock)
        /\ WF_v(Victim) /\ WF_v(VictimDelivery)
Safety == /\ R!TypeOK /\ R!Conservation /\ D!TypeOK /\ D!Conservation
          /\ fill \in 0..Ways
          /\ stage \in {"Fill","Evict","Deliver","Replace","Submit","Wait","Unlock","Done"}
          /\ (stage = "Unlock" => (0 \in retiring /\ 0 \notin resident /\ ~R!CanInvalidate(0)))
          /\ (stage = "Done" => 0 \notin directory)
          /\ (TargetPresent /\ stage = "Unlock" /\ ~ReleaseVictim => Cardinality(directory) = Ways)
Completes == <> (stage = "Done")
=============================================================================
