---------------------------- MODULE KillTransport ----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS TxMode, BadFinish, BadAbandon, BadWriteAck, BlockWriteService
\* One closing worker, an indefinitely held independent RX, and a live peer.
\* RX and TX software cases are separate; an earlier RX buffer may survive TX.
Clients == 1..3
Directions == {0}
Stalled == {2}
AllowedDirections == [c \in Clients |-> Directions]
Addresses == 0..14
Colors == 0..11
ColorOf == [a \in Addresses |-> a % 12]
Control == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> 3*(c-1)+p]]]
Dependencies == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |->
    IF c = 1 /\ TxMode = "Committed" THEN <<>> ELSE <<3*(c-1)+1-p,3*(c-1)+2>>]]]
Queues == Colors
QueueOf == [q \in Colors |-> q]
QueueDepth == 1
Slices == {0}
SliceOf == [q \in Colors |-> 0]
ReadSlots == 3
Pools == {0}
PoolOf == [c \in Clients |-> 0]
Slots == 4
SharedAck == TRUE
Lines == Addresses
Sets == Colors
SetOf == ColorOf
Ways == 2
EvictionQueues == Sets
EvictionQueueOf == [q \in Sets |-> q]
EvictionDepth == 1
TargetLines == IF TxMode = "None" THEN <<>> ELSE <<9,10,11>>
Buffers == {"rx","tx"}
WriteLines == {9,10,11}
BufferOf == [a \in WriteLines |-> "tx"]
Values == {0}
ResponseDepth == 1
VARIABLES s, reads,invQ,held,phase,parity,direction,remaining,completed,
    resident,directory,dirty,evictions,retiring,responses,txInvQ,
    batchRemaining,batchWaiting,buffer,writes,rxStarted
K == INSTANCE KillExit WITH WorkerCount <- 1, Initial <- "User",
    BadGate <- FALSE, BadRelease <- FALSE, BadActivate <- FALSE,
    BadResume <- FALSE, BadFree <- FALSE
P == INSTANCE ProgressSystem
CT == INSTANCE CachedTransport
R == INSTANCE RetainedCache
D == INSTANCE DcsResources WITH Clients <- 1..4, invQ <- txInvQ
V == INSTANCE Coherence WITH Clients <- 1..4
Q == INSTANCE ReadResponses
B == INSTANCE LciBatch
F == INSTANCE PacketResources
transport == <<reads,invQ,held,phase,parity,direction,remaining,completed>>
cache == <<resident,directory,dirty,evictions,retiring>>
tail == <<responses,txInvQ,batchRemaining,batchWaiting,buffer,writes,rxStarted>>
vars == <<s,transport,cache,tail>>
Init == /\ (IF TxMode = "Committed"
             THEN s = [K!InitialState EXCEPT !.w[1].rx = "Queued"] ELSE K!Init)
    /\ P!Init
    /\ parity = [c \in Clients |-> [d \in Directions |-> 0]]
    /\ resident = Addresses \ {13} /\ directory = Addresses
    /\ dirty = ({12,14} \cup (IF TxMode = "None" THEN {} ELSE WriteLines)) /\ retiring = {}
    /\ evictions = [q \in Sets |-> IF q = 1
          THEN <<[type |-> "Writeback",address |-> 13]>> ELSE <<>>]
    /\ Q!Init /\ txInvQ = [q \in Queues |-> <<>>]
    /\ B!Init /\ F!Init /\ rxStarted = (TxMode = "Committed")
Response == [type |-> "ReadData",txn |-> 1,address |-> 0,value |-> 0]
TailQuiet == /\ P!Quiet(1) /\ responses = <<>> /\ B!Done
             /\ \A b \in Buffers: F!Done(b)
             /\ \A q \in Queues: txInvQ[q] = <<>>
ControlStep(a) == a /\ UNCHANGED <<transport,cache,tail>>
SubmitRx == /\ TxMode = "None" /\ ~rxStarted /\ K!Submit(1) /\ rxStarted' = TRUE
    /\ UNCHANGED <<transport,cache,responses,txInvQ,batchRemaining,batchWaiting,buffer,writes>>
AdmitRx == /\ K!Admit(1) /\ CT!Admit(1) /\ UNCHANGED tail
WorkRx == /\ (IF TxMode = "Committed" THEN B!Done /\ F!Done("tx") ELSE s.w[1].latched)
    /\ CT!Work(1) /\ UNCHANGED <<s,tail>>
ReleaseRx == /\ K!ReleaseRx(1) /\ CT!Return(1,0) /\ Q!Send(Response)
    /\ UNCHANGED <<txInvQ,batchRemaining,batchWaiting,buffer,writes,rxStarted>>
ReceiveRx == /\ K!ReceiveRx(1) /\ Q!Receive(Response)
    /\ UNCHANGED <<transport,cache,txInvQ,batchRemaining,batchWaiting,buffer,writes,rxStarted>>
CompleteTail == /\ (BadFinish \/ TailQuiet) /\ ControlStep(K!CompleteTail(1))
Offer == /\ (BadFinish \/ TailQuiet) /\ ControlStep(K!Offer(1))
Free == /\ (BadFinish \/ TailQuiet) /\ ControlStep(K!FreeMetadata)
Activate == /\ (BadFinish \/ TailQuiet) /\ ControlStep(K!ActivateSuccessor(1))
RxBuffer == /\ s.w[1].latched /\ F!Release("rx",FALSE)
    /\ UNCHANGED <<s,transport,cache,responses,txInvQ,batchRemaining,batchWaiting,rxStarted>>
TxSubmit == /\ (IF TxMode = "Committed" THEN 1 \in held ELSE s.w[1].certified) /\ B!Submit
    /\ D!SendInvalidate(V!Invalidate(4,B!Target))
    /\ UNCHANGED <<s,invQ,held,phase,parity,direction,remaining,completed,cache,
                   responses,buffer,writes,rxStarted>>
TxProcess(q) == /\ D!Process(q) /\ R!Invalidate(Head(txInvQ[q]).address)
    /\ F!BeginWrite(Head(txInvQ[q]).address)
    /\ UNCHANGED <<s,invQ,held,phase,parity,direction,remaining,completed,
                   responses,batchRemaining,batchWaiting,rxStarted>>
TxAck == /\ batchWaiting /\ (BadWriteAck \/ writes[B!Target] = 0)
    /\ D!ReceiveAck(4,B!Target) /\ B!Ack /\ R!ReleaseInvalidation(B!Target)
    /\ UNCHANGED <<s,invQ,held,phase,parity,direction,remaining,completed,
                   responses,buffer,writes,rxStarted>>
WriteService(a) == /\ (~BlockWriteService \/ 2 \notin held) /\ F!ServiceWrite(a)
    /\ UNCHANGED <<s,transport,cache,responses,txInvQ,batchRemaining,batchWaiting,rxStarted>>
TxRelease == /\ B!Done /\ F!Release("tx",TxMode = "Committed" \/ BadAbandon)
    /\ UNCHANGED <<s,transport,cache,responses,txInvQ,batchRemaining,batchWaiting,rxStarted>>
Output == /\ F!Deliver("tx")
    /\ UNCHANGED <<s,transport,cache,responses,txInvQ,batchRemaining,batchWaiting,rxStarted>>
PeerAdmit(c) == /\ c \in {2,3} /\ CT!Admit(c) /\ UNCHANGED <<s,tail>>
PeerWork == CT!Work(3) /\ UNCHANGED <<s,tail>>
PeerReturn == CT!Return(3,0) /\ UNCHANGED <<s,tail>>
Prepare(c) == /\ c \in {2,3} /\ CT!Prepare(c,TRUE) /\ UNCHANGED <<s,tail>>
Inv(c) == CT!Inv(c) /\ UNCHANGED <<s,tail>>
Ack(c) == CT!Ack(c) /\ UNCHANGED <<s,tail>>
Process(q) == CT!Process(q) /\ UNCHANGED <<s,tail>>
Eviction(q) == CT!Deliver(q) /\ UNCHANGED <<s,tail>>
ControlNext == ControlStep(K!Close \/ K!Fence \/ K!BeginCallback \/ K!JoinCallback
    \/ K!Latch(1) \/ K!AssertIrq(1) \/ K!EnterKernel(1) \/ K!ServiceIrq(1)
    \/ K!Certify(1) \/ K!Consume(1) \/ K!ResumeOld(1) \/ K!SuccessorWork(1))
Next == ControlNext \/ SubmitRx \/ AdmitRx \/ WorkRx \/ ReleaseRx \/ ReceiveRx
    \/ CompleteTail \/ Offer \/ Free \/ Activate \/ RxBuffer
    \/ TxSubmit \/ TxAck \/ TxRelease \/ Output \/ PeerWork \/ PeerReturn
    \/ (\E c \in {2,3}: PeerAdmit(c) \/ Prepare(c))
    \/ (\E c \in {1,3}: Inv(c) \/ Ack(c))
    \/ (\E q \in Queues: Process(q) \/ TxProcess(q) \/ Eviction(q))
    \/ (\E a \in WriteLines: WriteService(a))
Spec == Init /\ [][Next]_vars
    /\ WF_vars(ControlStep(K!Fence)) /\ WF_vars(ControlStep(K!JoinCallback))
    /\ WF_vars(ControlStep(K!Latch(1))) /\ WF_vars(ControlStep(K!AssertIrq(1)))
    /\ WF_vars(ControlStep(K!ServiceIrq(1))) /\ WF_vars(ControlStep(K!Certify(1)))
    /\ WF_vars(ControlStep(K!Consume(1))) /\ WF_vars(ControlStep(K!SuccessorWork(1)))
    /\ WF_vars(AdmitRx) /\ WF_vars(WorkRx) /\ WF_vars(ReleaseRx) /\ WF_vars(ReceiveRx)
    /\ WF_vars(CompleteTail) /\ WF_vars(Offer) /\ WF_vars(Free) /\ WF_vars(Activate)
    /\ WF_vars(RxBuffer) /\ WF_vars(TxSubmit) /\ WF_vars(TxAck)
    /\ WF_vars(TxRelease) /\ WF_vars(Output) /\ WF_vars(PeerWork) /\ WF_vars(PeerReturn)
    /\ (\A c \in {2,3}: SF_vars(PeerAdmit(c)) /\ SF_vars(Prepare(c)))
    /\ (\A c \in {1,3}: WF_vars(Inv(c)) /\ WF_vars(Ack(c)))
    /\ (\A q \in Queues: WF_vars(Process(q)) /\ WF_vars(TxProcess(q)) /\ WF_vars(Eviction(q)))
    /\ (\A a \in WriteLines: WF_vars(WriteService(a)))
ComponentSafety == CT!Safety /\ K!TypeOK /\ K!NoWrongKill /\ K!NoResume
    /\ K!MetadataSafe /\ K!CacheRetained /\ Q!TypeOK /\ Q!Conservation
    /\ Q!UniqueTransactions /\ B!TypeOK /\ F!Safety /\ D!TypeOK /\ D!Conservation
TailTracking == /\ rxStarted \in BOOLEAN
    /\ (\A q \in Queues: invQ[q] = <<>> \/ txInvQ[q] = <<>>)
    /\ (s.w[1].rx = "Returned") = (responses # <<>>)
    /\ (s.w[1].rx = "Held") = (1 \in held)
    /\ (batchWaiting = (\E q \in Queues: txInvQ[q] # <<>>))
    /\ \A q \in Queues: txInvQ[q] # <<>> =>
         Head(txInvQ[q]).txn = 4 /\ Head(txInvQ[q]).address = B!Target
NoEarlyReuse == (s.freed \/ s.w[1].cpu = "Successor") => TailQuiet
NoAbandonedSend == TxMode # "Committed" => buffer["tx"] \notin {"Output","Sent"}
WriteBeforeAck == \A a \in WriteLines:
    writes[a] > 0 => \E q \in Queues: txInvQ[q] # <<>> /\ Head(txInvQ[q]).address = a
Safety == ComponentSafety /\ TailTracking /\ NoEarlyReuse /\ NoAbandonedSend /\ WriteBeforeAck
ControlProperties == K!NoReblock /\ K!NoResumeAfterClose /\ K!SnapshotStable
KillCompletes == K!StopCompletes /\ K!ExitCompletes /\ K!SuccessorsProgress
PeerProgress == P!ProgressFor({3}) /\ P!AdmissionFor({3})
NoStalledAtExit == ~(s.freed /\ 2 \in held)
=============================================================================
