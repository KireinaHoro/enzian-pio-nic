---------------------------- MODULE WakeRetirement ----------------------------
EXTENDS Naturals, FiniteSets
CONSTANT BadRetirement
BadClear == FALSE
BadReassign == FALSE
BadSubscriber == FALSE
SharedSubscription == FALSE
InitiallyParked == FALSE
VARIABLES issued, kernel, nic, runtime, serviced, owner, reserved, rtOwner, sleeping,
          closing, callback, prefixFree
W == INSTANCE HostWake
wakeVars == <<issued,kernel,nic,runtime,serviced,owner,reserved,rtOwner,sleeping>>
control == <<closing,callback,prefixFree>>
v == <<wakeVars,control>>
Init == /\ W!Init /\ closing = FALSE /\ callback = {} /\ prefixFree = FALSE
PendingRefs == {e \in kernel \cup nic \cup runtime: e[1] = 2}
SourceAssigned == (owner # 0 /\ W!Subscription(owner) = 2)
                  \/ (reserved # 0 /\ W!Subscription(reserved) = 2)
BeginCallback(e) == /\ ~closing /\ callback = {} /\ e \in W!Events \ issued /\ e[1] = 2
                    /\ callback' = {e} /\ UNCHANGED <<wakeVars,closing,prefixFree>>
FinishCallback == /\ callback # {} /\ W!Notify(CHOOSE e \in callback: TRUE) /\ callback' = {}
                  /\ UNCHANGED <<closing,prefixFree>>
Close == /\ ~closing /\ closing' = TRUE /\ UNCHANGED <<wakeVars,callback,prefixFree>>
\* The transport/cache retirement acknowledgment is supplied by the Assignment contract.
Detach == /\ closing /\ PendingRefs = {} /\ callback = {} /\ owner = 2 /\ ~sleeping
          /\ owner' = 0 /\ UNCHANGED <<issued,kernel,nic,runtime,serviced,reserved,rtOwner,sleeping,control>>
Free == /\ closing /\ ~prefixFree /\ ~SourceAssigned
        /\ (BadRetirement \/ (PendingRefs = {} /\ callback = {}))
        /\ prefixFree' = TRUE /\ UNCHANGED <<wakeVars,closing,callback>>
Accept(e) == /\ W!Accept(e) /\ UNCHANGED control
Service(e) == /\ W!Service(e) /\ UNCHANGED control
Reserve(t) == /\ W!Reserve(t) /\ UNCHANGED control
Deliver(c) == /\ W!Deliver(c) /\ UNCHANGED control
Release == /\ W!Release /\ UNCHANGED control
Activate == /\ W!Activate /\ UNCHANGED control
Sleep == /\ W!Sleep /\ UNCHANGED control
Resume == /\ W!Resume /\ UNCHANGED control
Next == \/ FinishCallback \/ Close \/ Detach \/ Free \/ Release \/ Activate \/ Sleep \/ Resume
        \/ \E e \in W!Events: BeginCallback(e) \/ Accept(e) \/ Service(e)
        \/ \E t \in W!Threads: Reserve(t)
        \/ \E c \in W!Cookies: Deliver(c)
Spec == Init /\ [][Next]_v /\ WF_v(FinishCallback) /\ SF_v(Detach) /\ WF_v(Free)
        /\ SF_v(Release) /\ WF_v(Activate) /\ WF_v(Resume)
        /\ (\A e \in W!Events: WF_v(Accept(e)) /\ SF_v(Service(e)))
        /\ (\A t \in W!Threads: SF_v(Reserve(t))) /\ (\A c \in W!Cookies: SF_v(Deliver(c)))
Safety == W!TypeOK /\ W!Responsibility /\ W!EligibleDelivery /\ W!HotReservation
PrefixSafe == prefixFree => (closing /\ callback = {} /\ PendingRefs = {} /\ ~SourceAssigned)
Retires == closing ~> prefixFree
=============================================================================
