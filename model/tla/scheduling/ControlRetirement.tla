-------------------------- MODULE ControlRetirement --------------------------
EXTENDS Naturals
CONSTANTS BadRetire, BadSnapshot, BadResume
\* One pre-reserved dispatch from a fixed pool, one wake callback, one close.
\* NIC internal readiness is abstract here; Handoff checks its resource guard.
VARIABLE s
v == <<s>>
Init == s = [stage |-> "Yield", closing |-> FALSE, fenced |-> FALSE,
    callback |-> "Unused", wake |-> "None", ready |-> FALSE,
    reply |-> "None", flag |-> FALSE, quiet |-> FALSE, endpoint |-> FALSE,
    retired |-> FALSE]
InfoAccept == /\ s.stage = "Yield"
              /\ s' = [s EXCEPT !.stage = "Pending"]
InternalReady == /\ s.stage = "Pending" /\ ~s.ready
                 /\ s' = [s EXCEPT !.ready = TRUE]
InfoRespond == /\ s.stage = "Pending" /\ s.ready
    /\ s' = [s EXCEPT !.stage = "Offered",
        !.reply = IF s.fenced THEN "Closed" ELSE "Next",
        !.flag = ~s.fenced /\ s.wake = "Nic",
        !.wake = IF s.wake = "Nic" THEN
                    IF s.fenced THEN "Disposed" ELSE "Snapshot" ELSE @]
Consume == /\ s.stage = "Offered" /\ s' = [s EXCEPT !.stage = "Consumed"]
Activate == /\ s.stage = "Consumed" /\ s.reply = "Next"
            /\ (BadResume \/ ~s.closing)
            /\ s' = [s EXCEPT !.stage = "Active"]
BeginCallback == /\ ~s.closing /\ s.callback = "Unused"
                 /\ s' = [s EXCEPT !.callback = "Running"]
FinishCallback == /\ s.callback = "Running"
                  /\ s' = [s EXCEPT !.callback = "Joined",
                      !.wake = IF s.fenced THEN "Disposed" ELSE "Nic"]
Deliver == /\ s.stage = "Active" /\ s.wake = "Nic" /\ ~s.closing
           /\ s' = [s EXCEPT !.wake = "Snapshot"]
Service == /\ s.stage = "Active" /\ s.wake = "Snapshot"
           /\ s' = [s EXCEPT !.wake = "Serviced"]
CloseKernel == /\ ~s.closing /\ s' = [s EXCEPT !.closing = TRUE]
CloseNic == /\ s.closing /\ ~s.fenced
            /\ s' = [s EXCEPT !.fenced = TRUE,
                !.reply = IF BadSnapshot /\ s.stage = "Offered" THEN "Closed" ELSE @,
                !.wake = IF s.wake = "Nic" THEN "Disposed" ELSE @]
\* Reconcile both a response already committed and a CLOSED response.
Stop == /\ s.closing /\ s.stage \in {"Consumed","Active"}
        /\ s' = [s EXCEPT !.stage = "Done",
                 !.wake = IF s.wake = "Snapshot" THEN "Disposed" ELSE @]
KernelQuiescent == /\ s.fenced /\ s.stage = "Done"
                   /\ s.callback # "Running" /\ s.wake \notin {"Nic","Snapshot"}
                   /\ ~s.quiet /\ s' = [s EXCEPT !.quiet = TRUE]
\* Undischarged coherence endpoint certificate, not a kernel CL access.
EndpointRetires == /\ s.quiet /\ ~s.endpoint /\ s' = [s EXCEPT !.endpoint = TRUE]
RetireResponse == /\ s.fenced /\ ~s.retired
                  /\ (BadRetire \/ (s.quiet /\ s.endpoint))
                  /\ s' = [s EXCEPT !.retired = TRUE]
Next == InfoAccept \/ InternalReady \/ InfoRespond \/ Consume \/ Activate
        \/ BeginCallback \/ FinishCallback \/ Deliver \/ Service \/ CloseKernel
        \/ CloseNic \/ Stop \/ KernelQuiescent \/ EndpointRetires \/ RetireResponse
Spec == Init /\ [][Next]_v
    /\ WF_v(InfoAccept) /\ WF_v(InternalReady) /\ WF_v(InfoRespond) /\ WF_v(Consume)
    /\ WF_v(Activate) /\ WF_v(FinishCallback) /\ WF_v(Deliver) /\ WF_v(Service)
    /\ WF_v(CloseNic) /\ WF_v(Stop) /\ WF_v(KernelQuiescent)
    /\ WF_v(EndpointRetires) /\ WF_v(RetireResponse)
TypeOK == s \in [stage : {"Yield","Pending","Offered","Consumed","Active","Done"},
    closing : BOOLEAN, fenced : BOOLEAN, callback : {"Unused","Running","Joined"},
    wake : {"None","Nic","Snapshot","Serviced","Disposed"}, ready : BOOLEAN,
    reply : {"None","Next","Closed"}, flag : BOOLEAN, quiet : BOOLEAN,
    endpoint : BOOLEAN, retired : BOOLEAN]
RetirementSafe == s.retired => (s.quiet /\ s.endpoint /\ s.stage = "Done"
                    /\ s.callback # "Running" /\ s.wake \notin {"Nic","Snapshot"})
SnapshotStable == [][(s.stage = "Offered" /\ s'.stage = "Offered") =>
                     (s'.reply = s.reply /\ s'.flag = s.flag)]_v
NoResumeAfterClose == [][Activate => ~s.closing]_v
ReadCompletes == (s.stage = "Pending") ~> (s.stage \in {"Consumed","Active","Done"})
Retires == s.closing ~> s.retired
=============================================================================
