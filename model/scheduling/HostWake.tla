------------------------------ MODULE HostWake ------------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS BadClear, BadReassign, BadSubscriber, SharedSubscription, InitiallyParked
Cookies == {1,2}
Threads == IF SharedSubscription THEN {1,2,3} ELSE {1,2}
Subscription(t) == IF t = 3 THEN 1 ELSE t
\* Each cookie is sealed to a domain/source before any notification is published.
Events == Cookies \X {1,2}
VARIABLES issued, kernel, nic, runtime, serviced, owner, reserved, rtOwner, sleeping
v == <<issued,kernel,nic,runtime,serviced,owner,reserved,rtOwner,sleeping>>
Init == /\ issued = {} /\ kernel = {} /\ nic = {} /\ runtime = {} /\ serviced = {}
        /\ owner = (IF InitiallyParked THEN 0 ELSE 1) /\ reserved = 0 /\ rtOwner = [e \in Events |-> 0] /\ sleeping = FALSE
Notify(e) == /\ e \notin issued
             /\ issued' = issued \cup {e} /\ kernel' = kernel \cup {e}
             /\ UNCHANGED <<nic,runtime,serviced,owner,reserved,rtOwner,sleeping>>
Accept(e) == /\ e \in kernel /\ kernel' = kernel \ {e} /\ nic' = nic \cup {e}
             /\ UNCHANGED <<issued,runtime,serviced,owner,reserved,rtOwner,sleeping>>
Pending(c) == {e \in nic: e[1] = c}
Hot(c) == owner # 0 /\ Subscription(owner) = c
Reserve(t) == /\ reserved = 0 /\ Pending(Subscription(t)) # {} /\ ~Hot(Subscription(t))
              /\ reserved' = t /\ UNCHANGED <<issued,kernel,nic,runtime,serviced,owner,rtOwner,sleeping>>
Release == /\ owner # 0 /\ reserved # 0 /\ ~sleeping
           /\ {e \in runtime: rtOwner[e] = owner} = {}
           /\ owner' = 0
           /\ nic' = IF BadReassign THEN {} ELSE nic
           /\ UNCHANGED <<issued,kernel,runtime,serviced,reserved,rtOwner,sleeping>>
Activate == LET delivered == Pending(Subscription(reserved)) IN
    /\ owner = 0 /\ reserved # 0 /\ owner' = reserved /\ reserved' = 0
    /\ nic' = nic \ delivered /\ runtime' = runtime \cup delivered
    /\ rtOwner' = [e \in Events |-> IF e \in delivered THEN reserved ELSE rtOwner[e]]
    /\ UNCHANGED <<issued,kernel,serviced,sleeping>>
Deliver(c) == LET delivered == Pending(c) IN
    /\ delivered # {} /\ owner # 0 /\ ~sleeping
    /\ BadSubscriber \/ Subscription(owner) = c
    /\ nic' = nic \ delivered /\ runtime' = runtime \cup delivered
    /\ rtOwner' = [e \in Events |-> IF e \in delivered THEN owner ELSE rtOwner[e]]
    /\ UNCHANGED <<issued,kernel,serviced,owner,reserved,sleeping>>
\* Explicit same-boundary notification: a new event must win over clearing old delivery.
DeliverAndNotify(c,e) == LET delivered == Pending(c) IN
    /\ delivered # {} /\ e \notin issued /\ e[1] = c
    /\ Hot(c) /\ ~sleeping
    /\ nic' = nic \ delivered /\ runtime' = runtime \cup delivered
    /\ rtOwner' = [x \in Events |-> IF x \in delivered THEN owner ELSE rtOwner[x]]
    /\ issued' = issued \cup {e}
    /\ kernel' = IF BadClear THEN kernel ELSE kernel \cup {e}
    /\ UNCHANGED <<serviced,owner,reserved,sleeping>>
Service(e) == /\ e \in runtime /\ rtOwner[e] = owner /\ ~sleeping
              /\ runtime' = runtime \ {e} /\ serviced' = serviced \cup {e}
              /\ rtOwner' = [rtOwner EXCEPT ![e] = 0]
              /\ UNCHANGED <<issued,kernel,nic,owner,reserved,sleeping>>
Sleep == /\ owner # 0 /\ ~sleeping /\ sleeping' = TRUE
         /\ UNCHANGED <<issued,kernel,nic,runtime,serviced,owner,reserved,rtOwner>>
Resume == /\ sleeping /\ sleeping' = FALSE
          /\ UNCHANGED <<issued,kernel,nic,runtime,serviced,owner,reserved,rtOwner>>
Next == \/ Release \/ Activate \/ Sleep \/ Resume
        \/ \E e \in Events: Notify(e) \/ Accept(e) \/ Service(e)
        \/ \E t \in Threads: Reserve(t)
        \/ \E c \in Cookies: Deliver(c)
        \/ \E c \in Cookies, e \in Events: DeliverAndNotify(c,e)
Spec == Init /\ [][Next]_v /\ SF_v(Release) /\ WF_v(Activate) /\ WF_v(Resume)
        /\ (\A e \in Events: WF_v(Accept(e)) /\ SF_v(Service(e)))
        /\ (\A t \in Threads: SF_v(Reserve(t))) /\ (\A c \in Cookies: SF_v(Deliver(c)))
TypeOK == /\ issued \subseteq Events /\ kernel \subseteq Events /\ nic \subseteq Events
          /\ runtime \subseteq Events /\ serviced \subseteq Events
          /\ owner \in Threads \cup {0} /\ reserved \in Threads \cup {0}
          /\ rtOwner \in [Events -> Threads \cup {0}] /\ sleeping \in BOOLEAN
Responsibility == /\ issued = kernel \cup nic \cup runtime \cup serviced
                  /\ Cardinality(issued) = Cardinality(kernel)+Cardinality(nic)+Cardinality(runtime)+Cardinality(serviced)
EligibleDelivery == \A e \in runtime: rtOwner[e] # 0 /\ Subscription(rtOwner[e]) = e[1]
HotReservation == reserved # 0 => ~Hot(Subscription(reserved))
WakeProgress == \A e \in Events: (e \in issued) ~> (e \in serviced)
SleepRetainsOwner == [][(Sleep \/ Resume) => owner' = owner]_v
=============================================================================
