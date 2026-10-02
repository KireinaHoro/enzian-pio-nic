------------------------- MODULE ExpandedScheduling -------------------------
EXTENDS Integers, Sequences, FiniteSets
CONSTANTS WithPeer, TailMode, WakeBudget, PoolSize, Contender, EnableClose,
          KillOnly, HoldContender,
          BadWakeAck, BadWakeDrop, BadWakeDelivery, BadReservation, BadClaim,
          BadDrain, BadSnapshot, BadWakeFree, BadHostDrain, BadClaimFairness, BadIrqDrain
CONSTANTS DcTable, ThxTable, WriteService
VARIABLES s, reads,invQ,held,phase,parity,direction,remaining,completed,
    resident,directory,dirty,evictions,retiring,responses,txInvQ,
    batchRemaining,batchWaiting,buffer,writes,rxStarted,
    epoch,routeStage,issued,wcmd,pending,target,runtime,commandDone,deliveredDomain,
    seen,accepted,resolved,outstanding,acmd,wakePending,
    claimStage,candidate,claim,infoPhase,info,endpoints
F == INSTANCE SchedulingComposition
E(a) == INSTANCE LineEndpoint WITH Line <- a, CpuLoad <- "LS",
    InitialPermission <- "Exclusive", BadEarlyAck <- FALSE, LinkDepth <- 2,
    ep <- endpoints[a]
base == <<s, reads,invQ,held,phase,parity,direction,remaining,completed, resident,directory,dirty,evictions,retiring,responses,txInvQ, batchRemaining,batchWaiting,buffer,writes,rxStarted, epoch,routeStage,issued,wcmd,pending,target,runtime,commandDone,deliveredDomain, seen,accepted,resolved,outstanding,acmd,wakePending, claimStage,candidate,claim,infoPhase,info>>
vars == <<base,endpoints>>
Addresses == F!T!Addresses
Keys == {0,1} \X F!T!Queues
Ports == [k \in Keys |-> IF k[1] = 0 THEN invQ[k[2]] ELSE txInvQ[k[2]]]
Completion(k) == /\ Ports[k] # <<>> /\ Ports'[k] # <<>>
    /\ Head(Ports[k]).type = "Invalidate" /\ Head(Ports'[k]).type = "Ack"
    /\ Head(Ports[k]).address = Head(Ports'[k]).address
    /\ Head(Ports[k]).txn = Head(Ports'[k]).txn
Completed == {a \in Addresses: \E k \in Keys: Completion(k) /\ Head(Ports[k]).address = a}
Init == F!Init /\ endpoints = [a \in Addresses |-> E(a)!Initial]
\* Run the unchanged scheduling action, gating only its abstract endpoint
\* completion certificate. No scheduling transition is restated here.
Upper == /\ F!Next
    /\ (\A a \in Completed: endpoints[a].app = "Ready")
    /\ endpoints' = [a \in Addresses |-> IF a \in Completed
           THEN [endpoints[a] EXCEPT !.app = "Done"] ELSE endpoints[a]]
\* Directory/residency stay in the shared-resource adapter. Establish the
\* admitted line's endpoint entry at the cross-layer boundary, not inside DC.
Entry(a) == IF a \in resident THEN E(a)!Initial
           ELSE [E(a)!Initial EXCEPT !.dc = "s1__1", !.thx = "I ()"]
ReadyToStart(a) == /\ endpoints[a].app \in {"Idle","Done"}
    /\ F!T!R!CanInvalidate(a)
    /\ reads[F!T!ColorOf[a]] = <<>>
    /\ \E k \in Keys: Ports[k] # <<>> /\ Head(Ports[k]).address = a /\ Head(Ports[k]).type = "Invalidate"
Start(a) == /\ ReadyToStart(a)
    /\ endpoints' = [endpoints EXCEPT ![a] = [Entry(a) EXCEPT
          !.app = "Pending", !.toDc = <<[type |-> "LCI",txn |-> 0,address |-> a]>>]]
    /\ UNCHANGED base
Internal(a) == /\ \E next \in E(a)!InternalStates: endpoints' = [endpoints EXCEPT ![a] = next]
               /\ UNCHANGED base
Next == Upper \/ (\E a \in Addresses: Start(a) \/ Internal(a))
\* Optional expensive product: checks safety/projection only. Do not silently
\* impose F!Spec fairness: it would assume abstract completion while the
\* detailed endpoint is blocked. Full-product progress obligations remain open.
Spec == Init /\ [][Next]_vars
Safety == F!Safety /\ \A a \in Addresses: E(a)!TypeOK /\ E(a)!NoProtocolError /\ E(a)!CompletionSound
AbstractBehavior == F!Init /\ [][F!Next]_base
=============================================================================
