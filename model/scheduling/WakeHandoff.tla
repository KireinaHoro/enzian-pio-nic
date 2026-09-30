----------------------------- MODULE WakeHandoff -----------------------------
EXTENDS Integers, Sequences, FiniteSets
\* One host-driven handoff, with an independently progressing physical worker.
WorkerTx == FALSE
WithStall == FALSE
FailSelection == FALSE
BadDrain == FALSE
BadCache == FALSE
BadPark == FALSE
BadReuse == FALSE
BadParity == FALSE
BadClear == FALSE
BadReassign == FALSE
BadSubscriber == FALSE
SharedSubscription == FALSE
InitiallyParked == FALSE
VARIABLES assignment, reads, invQ, held, acknowledgments, phase, parity, direction,
          remaining, completed, unlockQ, oldCache, prefixFree, reused, mailbox, returnedParity,
          issued, kernel, nic, runtime, serviced, owner, reserved, rtOwner, sleeping
H == INSTANCE Handoff
W == INSTANCE HostWake
hvars == <<assignment,reads,invQ,held,acknowledgments,phase,parity,direction,
           remaining,completed,unlockQ,oldCache,prefixFree,reused,mailbox,returnedParity>>
wvars == <<issued,kernel,nic,runtime,serviced,owner,reserved,rtOwner,sleeping>>
v == <<hvars,wvars>>
Event == <<2,1>>
Init == H!Init /\ W!Init
Notify == /\ W!Notify(Event) /\ UNCHANGED hvars
Accept == /\ W!Accept(Event) /\ UNCHANGED hvars
Reserve == /\ W!Reserve(2) /\ UNCHANGED hvars
Request == /\ reserved = 2 /\ H!Request /\ UNCHANGED wvars
Yield == /\ H!Yield /\ UNCHANGED wvars
Ack == /\ H!Ack /\ UNCHANGED wvars
Drain == /\ H!Drain /\ UNCHANGED wvars
RetireCache == /\ H!RetireCache /\ UNCHANGED wvars
Park == H!Park /\ W!Release
Select == /\ H!Select /\ UNCHANGED wvars
Activate == H!Activate /\ W!Activate
Service == /\ W!Service(Event) /\ UNCHANGED hvars
FreePrefix == /\ H!FreePrefix /\ UNCHANGED wvars
Reuse == /\ H!Reuse /\ UNCHANGED wvars
\* Runtime services activation's host-work flag before entering RX again.
Admit(c) == /\ (c # 3 \/ runtime = {}) /\ H!Admit(c) /\ UNCHANGED wvars
Work(c) == /\ H!Work(c) /\ UNCHANGED wvars
Inv(c) == /\ H!Inv(c) /\ UNCHANGED wvars
ReceiveAck(c) == /\ H!ReceiveAck(c) /\ UNCHANGED wvars
Unlock(c) == /\ H!Unlock(c) /\ UNCHANGED wvars
Return(c,d) == /\ H!Return(c,d) /\ UNCHANGED wvars
Process(q) == /\ H!Process(q) /\ UNCHANGED wvars
DeliverUnlock == /\ H!DeliverUnlock /\ UNCHANGED wvars
Next == \/ Notify \/ Accept \/ Reserve \/ Request \/ Yield \/ Ack \/ Drain \/ RetireCache
        \/ Park \/ Select \/ Activate \/ Service \/ FreePrefix \/ Reuse \/ DeliverUnlock
        \/ \E c \in H!Clients: Admit(c) \/ Work(c) \/ Inv(c) \/ ReceiveAck(c) \/ Unlock(c)
        \/ \E c \in H!Clients, d \in H!Directions: Return(c,d)
        \/ \E q \in H!Queues: Process(q)
Spec == Init /\ [][Next]_v /\ WF_v(Accept) /\ WF_v(Reserve) /\ SF_v(Request)
        /\ WF_v(Yield) /\ WF_v(Ack) /\ WF_v(Drain) /\ WF_v(RetireCache)
        /\ WF_v(Park) /\ WF_v(Select) /\ WF_v(Activate) /\ WF_v(Service)
        /\ WF_v(FreePrefix) /\ WF_v(Reuse) /\ WF_v(DeliverUnlock)
        /\ (\A c \in H!Clients: SF_v(Admit(c)) /\ WF_v(Work(c)) /\ SF_v(Inv(c))
                                  /\ SF_v(ReceiveAck(c)) /\ WF_v(Unlock(c)))
        /\ (\A c \in H!Clients: WF_v(Return(c,0)))
        /\ (\A q \in H!UsedQueues: WF_v(Process(q)))
Safety == /\ H!Safety /\ H!SteadyEntry /\ H!DrainBeforeInstall /\ H!CacheBeforeInstall /\ H!PrefixSafe
          /\ W!TypeOK /\ W!Responsibility /\ W!EligibleDelivery /\ W!HotReservation
          /\ (assignment[1].stage \in {"Run","Preempt","Yield","Ack","Drained","Retired"} => owner = 1)
          /\ (assignment[1].stage \in {"Park","Installed"} => owner = 0)
          /\ (assignment[1].stage = "Active" => owner = 2)
ActivationSafe == H!ActivationSafe
WakeCompletes == (Event \in issued) ~> (Event \in serviced /\ assignment[1].stage = "Active")
PeerProgress == H!PeerProgress
=============================================================================
