------------------------------ MODULE KillExit ------------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS WorkerCount, Initial, BadGate, BadRelease, BadActivate, BadResume, BadFree
\* Physical workers of one closing domain; each may dispatch a successor.
\* Prefixes are never reused. Cached old lines deliberately remain forever.
\* The chosen implementation serializes successor activation after IRQ drain.
Workers == 1..WorkerCount
VARIABLE s
v == <<s>>
InitialState == [closing |-> FALSE, fenced |-> FALSE, callback |-> "Idle",
    freed |-> FALSE, cached |-> Workers,
    w |-> [w \in Workers |-> [cpu |-> Initial, rx |-> "Idle",
        latched |-> FALSE, gate |-> FALSE, irq |-> "None", sent |-> FALSE,
        certified |-> FALSE, reply |-> "None", successorDone |-> FALSE]]]
Init == s = InitialState
\* Exit may initiate domain close without any IRQ. Kill also uses Close, then
\* asynchronous per-worker latch. Neither requires cooperative application work.
Close == /\ ~s.closing /\ s' = [s EXCEPT !.closing = TRUE]
Fence == /\ s.closing /\ ~s.fenced /\ s' = [s EXCEPT !.fenced = TRUE]
BeginCallback == /\ ~s.closing /\ s.callback = "Idle"
                 /\ s' = [s EXCEPT !.callback = "Running"]
JoinCallback == /\ s.callback = "Running"
                /\ s' = [s EXCEPT !.callback = "Joined"]
Submit(w) == /\ s.w[w].cpu = "User" /\ s.w[w].rx = "Idle"
             /\ (~s.w[w].gate \/ BadGate)
             /\ s' = [s EXCEPT !.w[w].rx = "Queued"]
\* Accepted requests can already be in transit when the kill gate is latched.
Admit(w) == /\ s.w[w].rx = "Queued"
            /\ s' = [s EXCEPT !.w[w].rx = "Held"]
Latch(w) == /\ s.fenced /\ ~s.w[w].latched
            /\ s' = [s EXCEPT !.w[w].latched = TRUE,
                 !.w[w].gate = TRUE,
                 !.w[w].irq = IF s.w[w].cpu = "User" THEN "Needed" ELSE "Done"]
AssertIrq(w) == /\ s.w[w].irq = "Needed"
                /\ s' = [s EXCEPT !.w[w].irq = "Pending", !.w[w].sent = TRUE]
\* Fair eligible release means NIC can synthesize a kill RX result without
\* packet arrival, handler entry, userspace cooperation, or cache reclamation.
ReturnRx(w) == /\ s.w[w].rx = "Held"
               /\ s' = [s EXCEPT !.w[w].rx = "Returned"]
ReleaseRx(w) == /\ s.w[w].latched /\ (~BadRelease \/ s.w[w].irq = "Done")
                /\ ReturnRx(w)
ReceiveRx(w) == /\ s.w[w].rx = "Returned"
                /\ s' = [s EXCEPT !.w[w].rx = "Tail"]
\* Abstract outstanding transport tail; no physical UL message is specified.
CompleteTail(w) == /\ s.w[w].rx = "Tail"
             /\ s' = [s EXCEPT !.w[w].rx = "Idle"]
\* Voluntary exit or cooperative yield is optional, not fair. It cannot bypass
\* a stalled synchronous load. Parked includes sleeping/nonexecuting threads.
EnterKernel(w) == /\ s.w[w].cpu = "User"
                  /\ s.w[w].rx \in {"Idle","Tail"}
                  /\ s' = [s EXCEPT !.w[w].cpu = "Kernel"]
ServiceIrq(w) == /\ s.w[w].irq = "Pending"
                 /\ s.w[w].rx \in {"Idle","Tail"}
                 /\ s' = [s EXCEPT !.w[w].irq = "Done",
                      !.w[w].cpu = IF @ = "Successor" THEN "WronglyKilled" ELSE "Kernel"]
Certify(w) == /\ s.closing /\ s.w[w].cpu \in {"Kernel","Parked"}
              /\ ~s.w[w].certified
              /\ s' = [s EXCEPT !.w[w].certified = TRUE]
\* A response can commit before close fencing and remain offered through close.
Offer(w) == /\ s.w[w].cpu \in {"Kernel","Parked"}
            /\ s.w[w].reply = "None" /\ s.w[w].rx = "Idle"
            /\ s' = [s EXCEPT !.w[w].reply = IF s.fenced THEN "Closed" ELSE "Next"]
Consume(w) == /\ s.w[w].reply \in {"Next","Closed"}
              /\ s' = [s EXCEPT !.w[w].reply = "Consumed"]
\* Models a stale local dispatch of the closing domain, not the successor.
ResumeOld(w) == /\ s.w[w].cpu \in {"Kernel","Parked"}
                /\ (~s.closing \/ BadResume)
                /\ s' = [s EXCEPT !.w[w].cpu = "User"]
ActivateSuccessor(w) == /\ s.w[w].certified /\ s.w[w].reply = "Consumed"
    /\ s.w[w].cpu \in {"Kernel","Parked"} /\ s.w[w].rx = "Idle"
    /\ (BadActivate \/ (s.w[w].latched /\ s.w[w].irq = "Done"))
    /\ s' = [s EXCEPT !.w[w].cpu = "Successor"]
SuccessorWork(w) == /\ s.w[w].cpu = "Successor" /\ ~s.w[w].successorDone
                    /\ s' = [s EXCEPT !.w[w].successorDone = TRUE]
ReferencesGone == /\ s.fenced /\ s.callback # "Running"
    /\ \A w \in Workers: s.w[w].certified /\ s.w[w].latched
        /\ s.w[w].irq = "Done" /\ s.w[w].rx = "Idle"
        /\ s.w[w].reply = "Consumed"
FreeMetadata == /\ s.closing /\ ~s.freed /\ (BadFree \/ ReferencesGone)
                /\ s' = [s EXCEPT !.freed = TRUE]
Step(w) == Submit(w) \/ Admit(w) \/ Latch(w) \/ AssertIrq(w) \/ ReleaseRx(w)
    \/ ReceiveRx(w) \/ CompleteTail(w) \/ EnterKernel(w) \/ ServiceIrq(w) \/ Certify(w)
    \/ Offer(w) \/ Consume(w) \/ ResumeOld(w) \/ ActivateSuccessor(w) \/ SuccessorWork(w)
Next == Close \/ Fence \/ BeginCallback \/ JoinCallback \/ FreeMetadata
        \/ \E w \in Workers: Step(w)
Spec == Init /\ [][Next]_v /\ WF_v(Fence) /\ WF_v(JoinCallback) /\ WF_v(FreeMetadata)
    /\ \A w \in Workers:
        /\ WF_v(Admit(w)) /\ WF_v(Latch(w)) /\ WF_v(AssertIrq(w))
        /\ WF_v(ReleaseRx(w)) /\ WF_v(ReceiveRx(w)) /\ WF_v(CompleteTail(w))
        /\ WF_v(ServiceIrq(w)) /\ WF_v(Certify(w)) /\ WF_v(Offer(w))
        /\ WF_v(Consume(w)) /\ WF_v(ActivateSuccessor(w)) /\ WF_v(SuccessorWork(w))
TypeOK == s \in [closing : BOOLEAN, fenced : BOOLEAN,
    callback : {"Idle","Running","Joined"}, freed : BOOLEAN, cached : SUBSET Workers,
    w : [Workers -> [cpu : {"User","Kernel","Parked","Successor","WronglyKilled"},
        rx : {"Idle","Queued","Held","Returned","Tail"}, latched : BOOLEAN,
        gate : BOOLEAN, sent : BOOLEAN, irq : {"None","Needed","Pending","Done"},
        certified : BOOLEAN, reply : {"None","Next","Closed","Consumed"}, successorDone : BOOLEAN]]]
NoWrongKill == \A w \in Workers: s.w[w].cpu # "WronglyKilled"
NoResume == \A w \in Workers: s.w[w].certified => s.w[w].cpu # "User"
MetadataSafe == s.freed => ReferencesGone
CacheRetained == s.cached = Workers
NoReblock == [][\A w \in Workers: Submit(w) => ~s.w[w].gate]_v
NoResumeAfterClose == [][\A w \in Workers: ResumeOld(w) => ~s.closing]_v
SnapshotStable == [][\A w \in Workers:
    (s.w[w].reply \in {"Next","Closed"} /\ s'.w[w].reply # "Consumed")
    => s'.w[w].reply = s.w[w].reply]_v
\* Negated reachability predicates: expected counterexamples witness each order.
IrqFirstUnreachable == ~\E w \in Workers:
    s.w[w].irq = "Pending" /\ s.w[w].rx = "Held"
ReleaseFirstUnreachable == ~\E w \in Workers:
    s.w[w].irq = "Needed" /\ s.w[w].rx = "Returned"
NoIrqExitUnreachable == ~(s.freed /\ \A w \in Workers: ~s.w[w].sent)
StopCompletes == s.closing ~> (\A w \in Workers: s.w[w].certified)
ExitCompletes == s.closing ~> s.freed
SuccessorsProgress == s.closing ~> (\A w \in Workers: s.w[w].successorDone)
=============================================================================
