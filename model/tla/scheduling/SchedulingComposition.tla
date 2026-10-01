------------------------- MODULE SchedulingComposition -------------------------
EXTENDS Integers, Sequences, FiniteSets
CONSTANTS WithPeer, TailMode, WakeBudget, PoolSize, Contender, EnableClose,
          KillOnly, HoldContender,
          BadWakeAck, BadWakeDrop, BadWakeDelivery, BadReservation, BadClaim,
          BadDrain, BadSnapshot, BadWakeFree, BadHostDrain, BadClaimFairness, BadIrqDrain
VARIABLES s, reads,invQ,held,phase,parity,direction,remaining,completed,
    resident,directory,dirty,evictions,retiring,responses,txInvQ,
    batchRemaining,batchWaiting,buffer,writes,rxStarted,
    epoch,routeStage,issued,wcmd,pending,target,runtime,commandDone,deliveredDomain,
    seen,accepted,resolved,outstanding,acmd,wakePending,
    claimStage,candidate,claim,infoPhase,info
T == INSTANCE KillTransport WITH Cooperative <- TRUE, WithSuccessor <- TRUE,
    TxMode <- TailMode, BadFinish <- FALSE, BadAbandon <- FALSE,
    BadWriteAck <- FALSE, BlockWriteService <- FALSE
W == INSTANCE WakeReassignment WITH stage <- routeStage, command <- wcmd,
    serviced <- commandDone, BadDrop <- BadWakeDrop, BadDelivery <- BadWakeDelivery
A == INSTANCE WakeAckRace WITH serviced <- resolved, command <- acmd,
    BadClear <- BadWakeAck, NotificationLimit <- WakeBudget
C == INSTANCE ThreadClaim WITH stage <- claimStage
SnapshotView == [stage |-> IF infoPhase = "Offered" THEN "Offered" ELSE "Consumed",
                 reply |-> info, flag |-> info.wake]
I == INSTANCE ControlRetirement WITH s <- SnapshotView,
    BadRetire <- FALSE, BadSnapshot <- FALSE, BadResume <- FALSE
transport == <<reads,invQ,held,phase,parity,direction,remaining,completed>>
cache == <<resident,directory,dirty,evictions,retiring>>
tail == <<responses,txInvQ,batchRemaining,batchWaiting,buffer,writes,rxStarted>>
tvars == <<s,transport,cache,tail>>
wvars == <<epoch,routeStage,issued,wcmd,pending,target,runtime,commandDone,deliveredDomain>>
avars == <<seen,accepted,resolved,outstanding,acmd,wakePending>>
cvars == <<claimStage,candidate,claim>>
meta == <<infoPhase,info>>
control == <<wvars,avars,cvars,meta>>
vars == <<tvars,control>>
Init == /\ T!Init /\ W!Init /\ A!Init /\ C!Init
    /\ infoPhase = "None" /\ info = [owner |-> 0, parity |-> 0, wake |-> FALSE]
Client == IF epoch = 1 THEN 4 ELSE 1
ActiveClients == {Client,2,3}
WakeQuiet == resolved = seen /\ acmd = <<>> /\ ~runtime /\ ~pending
Run(a) == a /\ UNCHANGED control
Notify == /\ ~s.closing /\ A!Notify /\ UNCHANGED <<tvars,wvars,cvars,meta>>
Publish == /\ A!Publish /\ W!Notify /\ UNCHANGED <<tvars,cvars,meta>>
Accept == /\ A!Accept /\ W!Accept /\ UNCHANGED <<tvars,cvars,meta>>
AckWake == /\ A!Ack /\ W!Ack /\ UNCHANGED <<tvars,cvars,meta>>
ResetCommand == /\ W!ResetCommand /\ acmd = <<>>
    /\ UNCHANGED <<tvars,avars,cvars,meta>>
Resolve == W!Resolve /\ UNCHANGED <<tvars,avars,cvars,meta>>
Deliver == /\ ~s.closing /\ W!Deliver /\ UNCHANGED <<tvars,avars,cvars,meta>>
Service == /\ (routeStage = "Run" \/ (~BadHostDrain /\ routeStage = "Draining"))
    /\ s.w[1].cpu = "User" /\ s.w[1].rx \in {"Idle","Tail"}
    /\ ~s.closing /\ W!Service /\ A!Service
    /\ UNCHANGED <<tvars,cvars,meta>>
Dispose == /\ s.closing /\ W!Dispose /\ A!Service
    /\ UNCHANGED <<tvars,cvars,meta>>
Begin == /\ (~KillOnly \/ s.closing) /\ (epoch = 0 \/ ~s.closing) /\ (TailMode # "Abandon" \/ s.closing)
    /\ W!Begin
    /\ (IF epoch = 0 THEN C!Reserve(1) ELSE UNCHANGED cvars)
    /\ UNCHANGED <<tvars,avars,meta>>
\* Running kill never assumes a voluntary yield from the closing application.
EnterKernel == /\ Client = 1 /\ routeStage = "Draining" /\ ~s.closing
    /\ ~runtime
    /\ Run(T!ControlStep(T!K!EnterKernel(1)))
\* An exit may race kill, but is deliberately not a fair service action.
OptionalExit == /\ Client = 1 /\ s.closing
    /\ Run(T!ControlStep(T!K!EnterKernel(1)))
Park == /\ (BadDrain \/ (IF Client = 1 THEN T!TailQuiet /\ s.w[1].cpu = "Kernel"
                             ELSE T!P!Quiet(4)))
    /\ W!Park /\ UNCHANGED <<tvars,avars,cvars,meta>>
Install == /\ epoch < 2 /\ (epoch = 0 \/ ~s.closing)
    /\ (epoch # 0 \/ claimStage[1] = "Select")
    /\ (BadIrqDrain \/ ~s.closing \/ (s.w[1].latched /\ s.w[1].irq = "Done"))
    /\ W!Install
    /\ parity' = [parity EXCEPT ![IF epoch = 0 THEN 4 ELSE 1] = parity[Client]]
    /\ (IF epoch = 1 THEN C!Release(1) ELSE UNCHANGED cvars)
    /\ UNCHANGED <<s,reads,invQ,held,phase,direction,remaining,completed,cache,tail,avars,meta>>
OfferInfo == /\ routeStage = "Installed" /\ infoPhase = "None"
    /\ infoPhase' = "Offered"
    /\ info' = [owner |-> W!Owner,parity |-> parity[Client][0],
                wake |-> W!Owner = 0 /\ pending /\ ~s.closing]
    /\ (IF W!Owner = 0 /\ pending /\ ~s.closing THEN W!Capture ELSE UNCHANGED wvars)
    /\ UNCHANGED <<tvars,avars,cvars>>
ConsumeInfo == /\ infoPhase = "Offered" /\ infoPhase' = "Consumed"
    /\ UNCHANGED <<tvars,wvars,avars,cvars,info>>
Choose(w,t) == /\ (w = 2 \/ (epoch = 1 /\ routeStage = "Installed"))
    /\ C!Choose(w,t) /\ UNCHANGED <<tvars,wvars,avars,meta>>
Claim(w) == C!Claim(w) /\ UNCHANGED <<tvars,wvars,avars,meta>>
Retry(w) == C!Retry(w) /\ UNCHANGED <<tvars,wvars,avars,meta>>
Compete == /\ Contender /\ C!Reserve(2) /\ UNCHANGED <<tvars,wvars,avars,meta>>
ReleaseContender == /\ C!Release(2) /\ UNCHANGED <<tvars,wvars,avars,meta>>
Activate == /\ infoPhase = "Consumed" /\ (epoch = 1 \/ ~s.closing)
    /\ (epoch # 1 \/ claimStage[1] = "Done") /\ W!Activate
    /\ (IF epoch = 2 THEN T!K!ResumeOld(1) ELSE UNCHANGED s)
    /\ infoPhase' = "None"
    /\ UNCHANGED <<transport,cache,responses,txInvQ,batchRemaining,batchWaiting,buffer,writes,rxStarted,
                   avars,cvars,info>>
\* Close of A cancels a return to A; B's existing claim remains held.
AbortReturn == /\ s.closing /\ epoch = 1 /\ routeStage \in {"Draining","Parked"}
    /\ T!P!Quiet(4) /\ W!SetStage("Installed") /\ infoPhase' = "None"
    /\ UNCHANGED <<tvars,avars,cvars,info>>
\* Killing A can park the worker without waiting for any destination capacity.
Stop == /\ s.closing /\ epoch # 1 /\ routeStage # "Parked"
    /\ s.w[1].certified /\ T!TailQuiet /\ ~runtime /\ infoPhase # "Offered"
    /\ W!SetStage("Parked") /\ infoPhase' = "None"
    /\ (IF claimStage[1] \in {"Select","Claim"} THEN C!Cancel(1) ELSE UNCHANGED cvars)
    /\ UNCHANGED <<tvars,avars,info>>
Close == /\ EnableClose /\ T!K!Close
    /\ info' = (IF BadSnapshot /\ infoPhase = "Offered"
                 THEN [info EXCEPT !.parity = 1-@] ELSE info)
    /\ UNCHANGED <<transport,cache,tail,wvars,avars,cvars,infoPhase>>
\* The imported reply is only the close completion; normal dispatch uses info.
KillOffer == /\ s.closing /\ Run(T!Offer)
BeginCallback == /\ EnableClose /\ Run(T!ControlStep(T!K!BeginCallback))
JoinCallback == /\ T!ControlStep(T!K!JoinCallback)
    /\ (A!Notify \/ UNCHANGED avars) /\ UNCHANGED <<wvars,cvars,meta>>
Free == /\ (BadWakeFree \/ (WakeQuiet /\ W!Owner # 0 /\ ~(infoPhase = "Offered" /\ info.owner = 0)))
    /\ Run(T!Free)
\* One initial-owner RX; returning A discharges the steady-state entry contract.
SubmitRx == /\ routeStage = "Run" /\ epoch = 0 /\ ~runtime /\ Run(T!SubmitRx)
PrepareOld == /\ s.w[1].rx = "Queued" /\ T!CT!Prepare(1,TRUE)
    /\ UNCHANGED <<s,tail,control>>
OtherAdmit == /\ ~completed[4] /\ routeStage = "Run" /\ Client = 4 /\ T!CT!Admit(4)
    /\ UNCHANGED <<s,tail,control>>
OtherPrepare == /\ ~completed[4] /\ routeStage = "Run" /\ Client = 4 /\ T!CT!Prepare(4,TRUE)
    /\ UNCHANGED <<s,tail,control>>
PeerAdmit(c) == /\ (c = 2 \/ WithPeer) /\ Run(T!PeerAdmit(c))
PeerPrepare(c) == /\ (c = 2 \/ WithPeer) /\ Run(T!Prepare(c))
OtherWork == T!CT!Work(4) /\ UNCHANGED <<s,tail,control>>
OtherReturn == T!CT!Return(4,0) /\ UNCHANGED <<s,tail,control>>
Next == Notify \/ Publish \/ Accept \/ AckWake \/ ResetCommand \/ Resolve \/ Deliver
    \/ Service \/ Dispose \/ Begin \/ EnterKernel \/ OptionalExit \/ Park \/ Install \/ OfferInfo
    \/ ConsumeInfo \/ Activate \/ AbortReturn \/ Stop \/ Close \/ BeginCallback
    \/ JoinCallback \/ KillOffer \/ Free
    \/ SubmitRx \/ PrepareOld \/ OtherAdmit \/ OtherPrepare \/ OtherWork \/ OtherReturn
    \/ Compete \/ ReleaseContender
    \/ (\E w \in {1,2}: Claim(w) \/ Retry(w) \/ (\E t \in C!Threads: Choose(w,t)))
    \/ Run(T!AdmitRx \/ T!WorkRx \/ T!ReleaseRx \/ T!ReceiveRx \/ T!CompleteTail
           \/ T!RxBuffer \/ T!TxSubmit \/ T!TxAck \/ T!TxRelease \/ T!Output
           \/ T!PeerWork \/ T!PeerReturn
           \/ T!ControlStep(T!K!Fence \/ T!K!Latch(1)
               \/ T!K!AssertIrq(1) \/ T!K!ServiceIrq(1) \/ T!K!Certify(1) \/ T!K!Consume(1)))
    \/ (\E c \in {2,3}: (PeerAdmit(c) \/ PeerPrepare(c)))
    \/ (\E c \in {1,3,4}: Run(T!Inv(c) \/ T!Ack(c)))
    \/ (\E q \in T!Queues: Run(T!Process(q) \/ T!TxProcess(q) \/ T!Eviction(q)))
    \/ (\E a \in T!WriteLines: Run(T!WriteService(a)))
\* This bounded control workflow has finitely many generations/handoffs.
\* Service its eligible steps fairly as one actor; repeating contenders and
\* datapaths have separate fairness, so their activity cannot mask this actor.
ControlService == Publish \/ Accept \/ AckWake \/ ResetCommand \/ Resolve \/ Deliver
    \/ Service \/ Dispose \/ EnterKernel \/ Park \/ Install \/ OfferInfo
    \/ ConsumeInfo \/ Activate \/ AbortReturn \/ Stop \/ JoinCallback \/ Free
    \/ KillOffer \/ Run(T!CompleteTail \/ T!RxBuffer
        \/ T!ControlStep(T!K!Fence \/ T!K!Latch(1) \/ T!K!AssertIrq(1)
            \/ T!K!ServiceIrq(1) \/ T!K!Certify(1) \/ T!K!Consume(1)))
Spec == Init /\ [][Next]_vars
    /\ WF_vars(ControlService)
    /\ (IF Contender /\ PoolSize = 1 THEN SF_vars(Begin) ELSE WF_vars(Begin))
    /\ WF_vars(PrepareOld) /\ WF_vars(OtherAdmit) /\ WF_vars(OtherPrepare)
    /\ WF_vars(OtherWork) /\ WF_vars(OtherReturn)
    /\ (~HoldContender => WF_vars(ReleaseContender))
    /\ (\A w \in {1,2}: (IF BadClaimFairness \/ ~Contender \/ PoolSize = 1 THEN WF_vars(Claim(w)) ELSE SF_vars(Claim(w)))
         /\ WF_vars(Retry(w)) /\ WF_vars(\E t \in C!Threads: Choose(w,t)))
    /\ WF_vars(Run(T!AdmitRx)) /\ WF_vars(Run(T!WorkRx)) /\ WF_vars(Run(T!ReleaseRx))
    /\ WF_vars(Run(T!ReceiveRx)) /\ WF_vars(Run(T!TxSubmit)) /\ WF_vars(Run(T!TxAck))
    /\ WF_vars(Run(T!TxRelease)) /\ WF_vars(Run(T!Output))
    /\ WF_vars(Run(T!PeerWork)) /\ WF_vars(Run(T!PeerReturn))
    /\ (\A c \in {2,3}: WF_vars(PeerAdmit(c)) /\ WF_vars(PeerPrepare(c)))
    /\ (\A c \in {1,3,4}: WF_vars(Run(T!Inv(c))) /\ WF_vars(Run(T!Ack(c))))
    /\ (\A q \in T!Queues: WF_vars(Run(T!Process(q))) /\ WF_vars(Run(T!TxProcess(q)))
         /\ WF_vars(Run(T!Eviction(q))))
    /\ (\A a \in T!WriteLines: WF_vars(Run(T!WriteService(a))))
ComponentSafety == T!Safety /\ W!TypeOK /\ W!Responsibility /\ W!RightSubscriber
    /\ A!TypeOK /\ A!NoLostNotification /\ C!TypeOK /\ C!UniqueClaim
    /\ C!CapacitySafe /\ C!ReservationBacked
DrainBeforeInstall == routeStage \in {"Parked","Installed"} =>
    T!TailQuiet /\ T!P!Quiet(4)
    /\ s.w[1].rx \notin {"Queued","Held","Returned"}
MetadataWakeSafe == s.freed => WakeQuiet /\ W!Owner # 0 /\ ~(infoPhase = "Offered" /\ info.owner = 0)
NoLateIrq == epoch = 1 /\ routeStage = "Run" => s.w[1].irq \notin {"Needed","Pending"}
NewOwnerReady == epoch = 1 /\ routeStage = "Run" => T!TailQuiet /\ claimStage[1] = "Done"
WakeLink == /\ (acmd = <<>>) = (wcmd = <<>>)
    /\ resolved <= accepted /\ accepted <= seen
    /\ runtime => accepted > resolved
Safety == ComponentSafety /\ DrainBeforeInstall /\ MetadataWakeSafe /\ WakeLink
    /\ NoLateIrq /\ NewOwnerReady
    /\ infoPhase \in {"None","Offered","Consumed"}
    /\ info \in [owner : {0,1},parity : 0..1,wake : BOOLEAN]
ControlProperties == T!ControlProperties
SnapshotStable == I!SnapshotStable
EntrySafe == [][Activate => (info.owner = W!Owner /\ info.parity = parity[Client][0]
                            /\ T!P!SettledFor(ActiveClients))]_vars
KillCompletes == EnableClose => (T!K!StopCompletes /\ T!K!ExitCompletes)
WakeCompletes == A!WakeProgress
PeerProgress == WithPeer => T!PeerProgress
\* Equivalent to ([]~closing => <>RoundTrip): avoid an unnecessary tableau.
MovesFinish == (~KillOnly /\ TailMode # "Abandon") => <>(s.closing \/ (epoch = 2 /\ routeStage = "Run"))
SuccessorProgress == KillOnly => ((epoch = 1 /\ routeStage = "Run") ~> completed[4])
NoLostNotification == A!NoLostNotification
WakeResponsibility == W!Responsibility
RightSubscriber == W!RightSubscriber
CapacitySafe == C!CapacitySafe
UniqueClaim == C!UniqueClaim
ResourceSafety == T!ComponentSafety
NoRoundTrip == ~(epoch = 2 /\ routeStage = "Run" /\ completed[4])
NoKilledSuccessor == ~(s.freed /\ completed[4] /\ 2 \in held)
NoClosedWake == ~(s.freed /\ seen > 0 /\ resolved = seen /\ 2 \in held)
=============================================================================
