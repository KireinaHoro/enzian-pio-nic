--------------------------- MODULE AxiReloadGate ---------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Entry, ResponseDepth, BadBypass, BadReleaseGate, BadResponseOrder,
          BadResponseService, AllowTargetBehindDoor, BadStopFence
\* One exchange, with an independent repeating peer. Values are generation ghosts,
\* not a payload-integrity model. Entry is Later, Queued, Buffered, or Stop.
Clients == 1..5
Addresses == 0..3
Lines == {0,1}
Colors == Addresses
ColorOf == [a \in Addresses |-> a]
Queues == Colors
QueueOf == [c \in Colors |-> c]
QueueDepth == 1
Slices == {0}
SliceOf == [c \in Colors |-> 0]
ReadSlots == 3
Values == {0,1}
VARIABLES reads, invQ, responses, queue, target, started, submitted, invalidated,
          acked, published, cache, earlyDone, lateIssued, lateDone, doorDone,
          peerStage, peerCompleted
D == INSTANCE DcsResources WITH reads <- reads, invQ <- invQ
R == INSTANCE ReadResponses WITH responses <- responses
V == INSTANCE Coherence
resources == <<reads,invQ,responses>>
control == <<queue,target,started,submitted,invalidated,acked,published,cache,
             earlyDone,lateIssued,lateDone,doorDone,peerStage,peerCompleted>>
vars == <<resources,control>>
Early == V!Read(1,target)
Door == V!Read(2,2)
Late == V!Read(3,target)
Peer == V!Read(4,3)
Data(m,value) == [type |-> "ReadData",txn |-> m.txn,address |-> m.address,value |-> value]
InitialQueue == CASE Entry = "Later" -> <<Door>>
                    [] Entry = "Queued" -> <<Early,Door>>
                    [] Entry = "Buffered" -> <<Door>>
                    [] Entry = "Stop" -> <<Early>>
Init == /\ target \in Lines /\ queue = InitialQueue
        /\ reads = [c \in Colors |-> SelectSeq(queue,LAMBDA m: m.address = c)]
        /\ invQ = [q \in Queues |-> <<>>]
        /\ responses = IF Entry = "Buffered" THEN <<Data(Early,0)>> ELSE <<>>
        /\ started = FALSE /\ submitted = {} /\ invalidated = {} /\ acked = {}
        /\ published = FALSE /\ cache = [l \in Lines |-> 0]
        /\ earlyDone = (Entry = "Later")
        /\ lateIssued = FALSE /\ lateDone = (Entry = "Stop")
        /\ doorDone = (Entry = "Stop")
        /\ peerStage = "Idle" /\ peerCompleted = FALSE
\* Router serialization: the worker sees the door only after earlier AXI RLAST.
\* Buffered means AXI completion occurred, but coherence data is still in flight.
Start == /\ ~started
         /\ (Entry = "Stop" \/ (Len(queue) > 0 /\ Head(queue) = Door))
         /\ started' = TRUE
         /\ UNCHANGED <<resources,queue,target,submitted,invalidated,acked,published,
                         cache,earlyDone,lateIssued,lateDone,doorDone,peerStage,peerCompleted>>
IssueLate ==
    /\ ~lateIssued /\ started
    /\ IF Entry = "Stop" THEN BadStopFence
        ELSE published \/ AllowTargetBehindDoor \/ BadBypass
    /\ ~R!Pending(target)
    /\ D!ReceiveRead(Late) /\ queue' = Append(queue,Late)
    /\ lateIssued' = TRUE /\ lateDone' = FALSE
    /\ UNCHANGED <<responses,target,started,submitted,invalidated,acked,published,
                   cache,earlyDone,doorDone,peerStage,peerCompleted>>
\* AXI response acceptance and CPU-side completion are separate actions.
Respond(i) == LET m == queue[i] IN
    /\ i \in 1..Len(queue) /\ (i = 1 \/ BadBypass)
    /\ (m # Door \/ (started /\ published))
    /\ D!CompleteRead(m) /\ R!Send(Data(m,IF published THEN 1 ELSE 0))
    /\ queue' = SubSeq(queue,1,i-1) \o SubSeq(queue,i+1,Len(queue))
    /\ UNCHANGED <<target,started,submitted,invalidated,acked,published,cache,
                   earlyDone,lateIssued,lateDone,doorDone,peerStage,peerCompleted>>
SubmitInv(l) == /\ started /\ l \notin submitted
    /\ D!SendInvalidate(V!Invalidate(5,l)) /\ submitted' = submitted \cup {l}
    /\ UNCHANGED <<responses,queue,target,started,invalidated,acked,published,
                   cache,earlyDone,lateIssued,lateDone,doorDone,peerStage,peerCompleted>>
\* Endpoint assumption: an older same-line refill cannot become usable after
\* invalidation completes. This harness realizes it by retiring that response
\* first. A real endpoint may instead cancel/retry; that correspondence is open.
Invalidate(l) == /\ (BadResponseOrder \/ ~R!Pending(l)) /\ D!Process(l)
    /\ invalidated' = invalidated \cup {l} /\ cache' = [cache EXCEPT ![l] = 2]
    /\ UNCHANGED <<responses,queue,target,started,submitted,acked,published,
                   earlyDone,lateIssued,lateDone,doorDone,peerStage,peerCompleted>>
Ack(l) == /\ (~BadReleaseGate \/ queue = <<>>) /\ D!ReceiveAck(5,l)
    /\ acked' = acked \cup {l}
    /\ UNCHANGED <<responses,queue,target,started,submitted,invalidated,published,
                   cache,earlyDone,lateIssued,lateDone,doorDone,peerStage,peerCompleted>>
Publish == /\ ~published /\ acked = Lines /\ published' = TRUE
    /\ UNCHANGED <<resources,queue,target,started,submitted,invalidated,acked,
                   cache,earlyDone,lateIssued,lateDone,doorDone,peerStage,peerCompleted>>
PeerAdmit == /\ peerStage = "Idle" /\ D!ReceiveRead(Peer) /\ peerStage' = "Read"
    /\ UNCHANGED <<responses,queue,target,started,submitted,invalidated,acked,published,
                   cache,earlyDone,lateIssued,lateDone,doorDone,peerCompleted>>
PeerRespond == /\ peerStage = "Read" /\ D!CompleteRead(Peer) /\ R!Send(Data(Peer,1))
    /\ peerStage' = "Response"
    /\ UNCHANGED <<queue,target,started,submitted,invalidated,acked,published,
                   cache,earlyDone,lateIssued,lateDone,doorDone,peerCompleted>>
Deliver == LET m == Head(responses) IN
    /\ responses # <<>> /\ (~BadResponseService \/ ~started \/ published)
    /\ R!Receive(m)
    /\ cache' = IF m.address \in Lines THEN [cache EXCEPT ![m.address] = m.value] ELSE cache
    /\ earlyDone' = (earlyDone \/ m.txn = 1)
    /\ doorDone' = (doorDone \/ m.txn = 2)
    /\ lateDone' = (lateDone \/ m.txn = 3)
    /\ peerStage' = IF m.txn = 4 THEN "Idle" ELSE peerStage
    /\ peerCompleted' = IF m.txn = 4 THEN ~peerCompleted ELSE peerCompleted
    /\ UNCHANGED <<reads,invQ,queue,target,started,submitted,invalidated,acked,
                   published,lateIssued>>
Next == Start \/ IssueLate \/ Publish \/ PeerAdmit \/ PeerRespond \/ Deliver
        \/ (\E i \in 1..Len(queue): Respond(i))
        \/ (\E l \in Lines: SubmitInv(l) \/ Invalidate(l) \/ Ack(l))
Spec == Init /\ [][Next]_vars /\ WF_vars(Start) /\ SF_vars(IssueLate)
        /\ SF_vars(Respond(1)) /\ WF_vars(Publish) /\ WF_vars(Deliver)
        /\ SF_vars(PeerAdmit) /\ SF_vars(PeerRespond)
        /\ (\A l \in Lines: WF_vars(SubmitInv(l)) /\ WF_vars(Invalidate(l)) /\ WF_vars(Ack(l)))
TypeOK == /\ D!TypeOK /\ R!TypeOK
    /\ queue \in Seq(V!ReadMessages) /\ Len(queue) <= 3 /\ target \in Lines
    /\ started \in BOOLEAN /\ submitted \subseteq Lines /\ invalidated \subseteq submitted
    /\ acked \subseteq invalidated /\ published \in BOOLEAN /\ cache \in [Lines -> 0..2]
    /\ earlyDone \in BOOLEAN /\ lateIssued \in BOOLEAN /\ lateDone \in BOOLEAN /\ doorDone \in BOOLEAN
    /\ peerStage \in {"Idle","Read","Response"} /\ peerCompleted \in BOOLEAN
Tracking ==
    /\ UNION {{reads[c][i]: i \in 1..Len(reads[c])}: c \in Colors} =
       {queue[i]: i \in 1..Len(queue)} \cup (IF peerStage = "Read" THEN {Peer} ELSE {})
    /\ Cardinality({queue[i].txn: i \in 1..Len(queue)}) = Len(queue)
    /\ {l \in Lines: Len(invQ[l]) > 0} = submitted \ acked
    /\ (peerStage = "Response") = (\E i \in 1..Len(responses): responses[i].txn = 4)
Safety == TypeOK /\ Tracking /\ D!Conservation /\ R!Conservation /\ R!UniqueTransactions
NoStaleRefill == published => \A l \in Lines: cache[l] # 0
Completes == <> (published /\ earlyDone /\ doorDone /\ lateDone)
PeerProgress == ([]<>peerCompleted) /\ ([]<>~peerCompleted)
\* Sensitivity witnesses, not assertions that these executions occur on THX.
NoBufferedAtDoor == ~(started /\ ~published /\ R!Pending(target))
NoTargetReadBehindDoor == ~(started /\ ~published /\ Entry # "Stop" /\ lateIssued)
=============================================================================
