-------------------------- MODULE CombinedScheduling --------------------------
EXTENDS Integers, Sequences, FiniteSets
CONSTANTS Nonlocking, AllocateOnInvalidate, DestinationCapacity, EventBudget, RepeatRx, BadReserve, BadClaim, BadInfo, BadRetire, BadWake, BadSnapshot
Workers == {1,2}
Domains == {0,1}
Clients == 1..4
Client(w,d) == w+2*d
ThreadPool(d) == IF d = 0 THEN {1,2} ELSE 3..(2+DestinationCapacity)
Capacity(d) == Cardinality(ThreadPool(d))
Addresses == 0..11
Colors == 0..3
ColorOf == [a \in Addresses |-> a % 4]
Directions == {0}
Stalled == {}
AllowedDirections == [c \in Clients |-> Directions]
Control == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> 2*(c-1)+p]]]
Dependencies == [c \in Clients |-> [d \in Directions |-> [p \in 0..1 |-> <<2*(c-1)+1-p>>]]]
Queues == Colors
QueueOf == [q \in Colors |-> q]
QueueDepth == 1
Slices == {0,1}
SliceOf == [q \in Colors |-> q % 2]
ReadSlots == 3
Pools == {0}
PoolOf == [c \in Clients |-> 0]
Slots == 3
SharedAck == TRUE
Lines == Addresses
Sets == Colors
SetOf == ColorOf
Ways == 1
EvictionQueues == Sets
EvictionQueueOf == [s \in Sets |-> s]
EvictionDepth == 1
DomainOf(a) == IF a < 4 THEN 0 ELSE IF a < 8 THEN 1 ELSE 2
Events == Domains
Diagnostic == BadReserve \/ BadClaim \/ BadInfo
VARIABLES ctl,reads,invQ,held,acknowledgments,phase,parity,direction,remaining,completed,unlockQ,
          resident,directory,dirty,evictions,retiring
P == INSTANCE ProgressSystem
R == INSTANCE RetainedCache
transport == <<reads,invQ,held,acknowledgments,phase,parity,direction,remaining,completed,unlockQ>>
cache == <<resident,directory,dirty,evictions,retiring>>
v == <<ctl,transport,cache>>
Init ==
    /\ P!Init /\ parity = [c \in Clients |-> [d \in Directions |-> 0]]
    /\ resident = 8..11 /\ directory = resident /\ dirty = resident
    /\ retiring = {}
    /\ evictions = [q \in Sets |-> <<>>]
    /\ ctl = [w |-> [w \in Workers |-> [stage |-> "Run", owner |-> 0, reserved |-> -1,
                  claim |-> w, candidate |-> 0, reply |-> "None", snapshot |-> {}, runtime |-> {},
                  parity |-> 0]], closing |-> {}, fenced |-> {}, retired |-> {},
              callback |-> {}, issued |-> {}, kernel |-> {}, commands |-> {}, nic |-> {},
              served |-> {}, disposed |-> {}]
Used(d) == Cardinality({w \in Workers: ctl.w[w].owner = d})+
           Cardinality({w \in Workers: ctl.w[w].reserved = d})
Claims == {ctl.w[w].claim: w \in Workers}
Quiet(c) ==
    /\ phase[c] = "Submit" /\ c \notin held /\ c \notin acknowledgments
    /\ (\A q \in Queues: \A i \in 1..Len(invQ[q]): invQ[q][i].txn # c)
    /\ (\A i \in 1..Len(unlockQ): unlockQ[i].txn # c)
Pinned == {P!C!Door(c): c \in held} \cup
          UNION {{invQ[q][i].address: i \in 1..Len(invQ[q])}: q \in Queues}
Eligible(c) == \E w \in Workers, d \in Domains:
    c = Client(w,d) /\ ctl.w[w].owner = d /\ d \notin ctl.closing
    /\ ctl.w[w].stage \in {"Run","Active"} /\ ctl.w[w].runtime = {}
    /\ (RepeatRx \/ ~completed[c])
\* Both physical workers compete for the destination. Network work is available;
\* a pending host event uses the same reservation/accounting commit point.
Request(w) ==
    /\ ctl.w[w].stage = "Run" /\ phase[w] = "Held" /\ ctl.w[w].runtime = {}
    /\ 1 \notin ctl.fenced /\ (BadReserve \/ Used(1) < Capacity(1))
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Preempt", !.w[w].reserved = 1]
Yield(w) ==
    /\ ctl.w[w].stage = "Preempt" /\ phase[w] = "Submit"
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Yield"]
InfoAccept(w) ==
    /\ ctl.w[w].stage = "Yield"
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Pending", !.w[w].claim = 0]
InfoRespond(w) == LET closed == 1 \in ctl.fenced
                         delivered == IF closed THEN {} ELSE ctl.nic \cap {1} IN
    /\ ctl.w[w].stage = "Pending" /\ (BadInfo \/ Quiet(w))
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Offered", !.w[w].reserved = -1,
         !.w[w].owner = IF closed THEN -1 ELSE 1,
         !.w[w].reply = IF closed THEN "Closed" ELSE "Next",
         !.w[w].parity = parity[w][0], !.w[w].snapshot = delivered,
         !.nic = @ \ delivered]
    /\ parity' = [parity EXCEPT ![Client(w,1)] = parity[w], ![w] = [d \in Directions |-> 0]]
    /\ completed' = [completed EXCEPT ![w] = FALSE]
    /\ UNCHANGED <<reads,invQ,held,acknowledgments,phase,direction,remaining,unlockQ,cache>>
Consume(w) ==
    /\ ctl.w[w].stage = "Offered"
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Claim"]
Choose(w,t) ==
    /\ ctl.w[w].stage = "Claim" /\ ctl.w[w].reply = "Next"
    /\ 1 \notin ctl.closing /\ t \in ThreadPool(1) \ Claims
    /\ ctl' = [ctl EXCEPT !.w[w].candidate = t, !.w[w].stage = "Chosen"]
Claim(w) ==
    /\ ctl.w[w].stage = "Chosen" /\ 1 \notin ctl.closing
    /\ (BadClaim \/ ctl.w[w].candidate \notin Claims)
    /\ ctl' = [ctl EXCEPT !.w[w].claim = ctl.w[w].candidate, !.w[w].candidate = 0,
                         !.w[w].stage = "Ready"]
Retry(w) ==
    /\ ctl.w[w].stage = "Chosen" /\ ctl.w[w].candidate \in Claims
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Claim", !.w[w].candidate = 0]
Activate(w) ==
    /\ ctl.w[w].stage = "Ready" /\ 1 \notin ctl.closing
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Active",
         !.w[w].runtime = ctl.w[w].snapshot, !.w[w].snapshot = {},
         !.w[w].reply = "None", !.w[w].parity = 0]
BeginCallback(d) ==
    /\ ~Diagnostic /\ d \notin ctl.closing /\ d \notin ctl.callback \cup ctl.issued
    /\ Cardinality(ctl.callback \cup ctl.issued) < EventBudget
    /\ ctl' = [ctl EXCEPT !.callback = @ \cup {d}]
FinishCallback(d) ==
    /\ d \in ctl.callback
    /\ ctl' = [ctl EXCEPT !.callback = @ \ {d}, !.issued = @ \cup {d}, !.kernel = @ \cup {d}]
Publish(d) ==
    /\ d \in ctl.kernel \ ctl.commands
    /\ ctl' = [ctl EXCEPT !.commands = @ \cup {d}]
Accept(d) ==
    /\ d \in ctl.kernel \cap ctl.commands
    /\ ctl' = [ctl EXCEPT !.kernel = @ \ {d},
         !.nic = IF d \in ctl.fenced THEN @ ELSE @ \cup {d},
         !.disposed = IF d \in ctl.fenced THEN @ \cup {d} ELSE @]
Ack(d) ==
    /\ d \in ctl.commands \ ctl.kernel
    /\ ctl' = [ctl EXCEPT !.commands = @ \ {d}]
Deliver(w) == LET d == ctl.w[w].owner IN
    /\ ctl.w[w].stage \in {"Run","Active"} /\ d \in ctl.nic /\ d \notin ctl.closing
    /\ ctl' = [ctl EXCEPT !.nic = @ \ {d}, !.w[w].runtime = @ \cup {d}]
Service(w) ==
    /\ ctl.w[w].stage \in {"Run","Active"} /\ ctl.w[w].runtime # {}
    /\ ctl' = [ctl EXCEPT !.served = @ \cup ctl.w[w].runtime, !.w[w].runtime = {}]
CloseKernel(d) ==
    /\ ~Diagnostic /\ d \notin ctl.closing
    /\ ctl' = [ctl EXCEPT !.closing = @ \cup {d}]
CloseNic(d) ==
    /\ d \in ctl.closing \ ctl.fenced
    /\ ctl' = [ctl EXCEPT !.fenced = @ \cup {d}, !.nic = @ \ {d},
         !.disposed = IF BadWake THEN @ ELSE @ \cup (ctl.nic \cap {d}),
         !.w = [w \in Workers |-> IF BadSnapshot /\ d = 1 /\ ctl.w[w].stage = "Offered"
                    THEN [ctl.w[w] EXCEPT !.reply = "Closed"] ELSE ctl.w[w]]]
Stop(w) ==
    /\ ctl.w[w].stage \in {"Run","Active","Claim","Chosen","Ready"}
    /\ (ctl.w[w].owner \in ctl.closing \/ ctl.w[w].reply = "Closed")
    /\ \A d \in Domains: Quiet(Client(w,d))
    /\ ctl' = [ctl EXCEPT !.w[w].stage = "Idle", !.w[w].owner = -1, !.w[w].claim = 0,
         !.disposed = @ \cup ctl.w[w].runtime \cup ctl.w[w].snapshot,
         !.w[w].runtime = {}, !.w[w].snapshot = {}, !.w[w].candidate = 0,
         !.w[w].reply = "None", !.w[w].parity = 0]
    /\ parity' = [c \in Clients |-> IF c \in {Client(w,d): d \in Domains}
                                     THEN [d \in Directions |-> 0] ELSE parity[c]]
    /\ completed' = [c \in Clients |-> IF c \in {Client(w,d): d \in Domains}
                                        THEN FALSE ELSE completed[c]]
    /\ UNCHANGED <<reads,invQ,held,acknowledgments,phase,direction,remaining,unlockQ,cache>>
KernelQuiet(d) ==
    /\ d \in ctl.fenced /\ d \notin ctl.callback \cup ctl.commands \cup ctl.kernel
    /\ \A w \in Workers:
        /\ ctl.w[w].owner # d /\ ctl.w[w].reserved # d
        /\ ~(d = 1 /\ ctl.w[w].stage \in {"Offered","Claim","Chosen","Ready"})
        /\ d \notin ctl.w[w].snapshot \cup ctl.w[w].runtime
EndpointQuiet(d) ==
    /\ (\A a \in directory: DomainOf(a) # d)
    /\ (\A w \in Workers: Quiet(Client(w,d)))
Retire(d) ==
    /\ d \in ctl.fenced \ ctl.retired
    /\ (BadRetire \/ (KernelQuiet(d) /\ EndpointQuiet(d)))
    /\ ctl' = [ctl EXCEPT !.retired = @ \cup {d}]
Local(w) == Yield(w) \/ InfoAccept(w) \/ Consume(w) \/ (\E t \in ThreadPool(1): Choose(w,t))
            \/ Claim(w) \/ Retry(w) \/ Activate(w) \/ Service(w)
ControlStep ==
    /\ ((\E w \in Workers: Request(w) \/ Local(w) \/ Deliver(w))
                  \/ (\E d \in Domains: BeginCallback(d) \/ FinishCallback(d) \/ Publish(d)
                      \/ Accept(d) \/ Ack(d) \/ CloseKernel(d) \/ CloseNic(d) \/ Retire(d)))
    /\ UNCHANGED <<transport,cache>>
Prepare(c) ==
    /\ Eligible(c) /\ phase[c] = "Submit" /\ P!C!Door(c) \notin resident /\ P!C!Door(c) \notin Pinned
    /\ (R!Fill(P!C!Door(c)) \/ (\E a \in resident \ Pinned:
           SetOf[a] = SetOf[P!C!Door(c)] /\ R!Evict(a)))
    /\ UNCHANGED <<ctl,transport>>
\* Whole-domain endpoint retirement uses the same queued replacement path.
\* Its ability to initiate these autonomous evictions remains an endpoint contract.
EvictClosed(a) ==
    /\ a \in resident \ Pinned /\ DomainOf(a) \in Domains
    /\ KernelQuiet(DomainOf(a)) /\ R!Evict(a) /\ UNCHANGED <<ctl,transport>>
Conflict(a) ==
    /\ a \in resident \ Pinned /\ a < 8
    /\ R!Evict(a) /\ UNCHANGED <<ctl,transport>>
EvictionDelivery(q) ==
    /\ Len(evictions[q]) > 0
    /\ reads[ColorOf[Head(evictions[q]).address]] = <<>>
    /\ R!Deliver(q) /\ UNCHANGED <<ctl,transport>>
Admit(c) ==
    /\ Eligible(c) /\ P!C!Door(c) \in resident /\ P!Admit(c) /\ UNCHANGED <<ctl,cache>>
ClientStep(c) ==
    /\ (P!Work(c) \/ P!Inv(c) \/ P!Unlock(c) \/ P!Return(c,0))
                 /\ UNCHANGED <<ctl,cache>>
Acknowledge(c) == /\ P!Ack(c) /\ UNCHANGED ctl
    /\ IF Nonlocking THEN R!ReleaseInvalidation(P!C!Target(c)) ELSE UNCHANGED cache
Process(q) ==
    /\ P!Process(q) /\ R!Invalidate(Head(invQ[q]).address) /\ UNCHANGED ctl
DeliverUnlock ==
    /\ P!DeliverUnlock /\ R!ReleaseInvalidation(Head(unlockQ).address) /\ UNCHANGED ctl
Next == ControlStep \/ DeliverUnlock \/ (\E w \in Workers: InfoRespond(w) \/ Stop(w))
    \/ (\E c \in Clients: Prepare(c) \/ Admit(c) \/ ClientStep(c) \/ Acknowledge(c))
    \/ (\E q \in Queues: Process(q) \/ EvictionDelivery(q))
    \/ (\E a \in Lines: Conflict(a) \/ EvictClosed(a))
CPU(a) == a /\ UNCHANGED <<transport,cache>>
Spec == Init /\ [][Next]_v /\ WF_v(DeliverUnlock)
    /\ (\A w \in Workers: WF_v(CPU(Local(w))) /\ WF_v(InfoRespond(w)) /\ WF_v(Stop(w)) /\ SF_v(CPU(Deliver(w))))
    /\ (\A d \in Domains: WF_v(CPU(FinishCallback(d))) /\ WF_v(CPU(Publish(d))) /\ WF_v(CPU(Accept(d)))
        /\ WF_v(CPU(Ack(d))) /\ WF_v(CPU(CloseNic(d))) /\ WF_v(CPU(Retire(d))))
    /\ (\A c \in Clients: SF_v(Prepare(c)) /\ SF_v(Admit(c)) /\ SF_v(ClientStep(c)) /\ SF_v(Acknowledge(c)))
    /\ (\A q \in Queues: WF_v(Process(q)) /\ WF_v(EvictionDelivery(q)))
    /\ (\A a \in Lines: WF_v(EvictClosed(a)))
CapacitySafe == \A d \in Domains: Used(d) <= Capacity(d)
UniqueClaim == \A w,x \in Workers: w # x /\ ctl.w[w].claim # 0 => ctl.w[w].claim # ctl.w[x].claim
Refs == ctl.kernel \cup ctl.nic \cup ctl.served \cup ctl.disposed \cup
        UNION {ctl.w[w].snapshot \cup ctl.w[w].runtime: w \in Workers}
Responsibility ==
    /\ ctl.issued = Refs
    /\ Cardinality(ctl.issued) = Cardinality(ctl.kernel)+Cardinality(ctl.nic)+Cardinality(ctl.served)+
        Cardinality(ctl.disposed)+Cardinality(ctl.w[1].snapshot)+Cardinality(ctl.w[2].snapshot)+
        Cardinality(ctl.w[1].runtime)+Cardinality(ctl.w[2].runtime)
Routing == \A w \in Workers: ctl.w[w].runtime \cup ctl.w[w].snapshot \subseteq {ctl.w[w].owner}
InfoReady == \A w \in Workers: ctl.w[w].stage \in {"Offered","Claim","Chosen","Ready","Active"} => Quiet(w)
\* Kernel close fences future activation. InfoAccept is the old-task no-return
\* boundary; pending NIC responses, callbacks and cache tags may outlive it.
\* Stop abstracts a kernel stop certificate, not an implemented kill IRQ path.
ApplicationStopped(d) ==
    /\ d \in ctl.closing
    /\ \A w \in Workers: ~(ctl.w[w].owner = d /\
          ctl.w[w].stage \in {"Run","Active","Preempt","Yield"})
\* This is a PA-reuse certificate, not the application's kill completion.
PrefixReusable(d) == d \in ctl.retired
StopBeforeReuse == \A d \in Domains: PrefixReusable(d) => ApplicationStopped(d)
RetirementSafe == \A d \in ctl.retired: KernelQuiet(d) /\ EndpointQuiet(d)
NoAbsentLockWait == ~\E q \in Queues:
    /\ Len(invQ[q]) > 0 /\ Head(invQ[q]).type = "Invalidate"
    /\ R!AbsentLockWait(Head(invQ[q]).address)
Safety == (~Nonlocking \/ ~AllocateOnInvalidate) /\ P!TypeOK /\ P!Tracking /\ P!Conservation /\ R!TypeOK /\ R!Conservation
          /\ CapacitySafe /\ UniqueClaim /\ Responsibility /\ Routing /\ InfoReady /\ RetirementSafe /\ StopBeforeReuse
          /\ P!NoReadDependencyCycle
SnapshotStable == [][\A w \in Workers: (ctl.w[w].stage = "Offered" /\ ctl'.w[w].stage = "Offered")
                    => ctl'.w[w] = ctl.w[w]]_v
HandoffFinishes == \A w \in Workers: (ctl.w[w].stage = "Preempt") ~> (ctl.w[w].stage \in {"Active","Idle"})
\* Conditional on the fair endpoint reclamation assumption in Spec; this says
\* nothing about the ordering of kill IRQ assertion versus RX release.
Retires == \A d \in Domains: (d \in ctl.closing) ~> (d \in ctl.retired)
StableProgress == \A w \in Workers, d \in Domains:
    (<>[](ctl.w[w].owner = d /\ ctl.w[w].stage \in {"Run","Active"} /\ d \notin ctl.closing))
    => (IF RepeatRx THEN (([]<>completed[Client(w,d)]) /\ ([]<>~completed[Client(w,d)]))
        ELSE <>[]completed[Client(w,d)])
=============================================================================
