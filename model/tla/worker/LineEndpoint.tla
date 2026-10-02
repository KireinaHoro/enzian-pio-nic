---------------------------- MODULE LineEndpoint ----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS DcTable, ThxTable, Line, CpuLoad, InitialPermission,
          WriteService, BadEarlyAck, LinkDepth
VARIABLE ep
DC == INSTANCE DcMachine
TH == INSTANCE ThxMachine
vars == <<ep>>
Msg(t) == [type |-> t, txn |-> 0, address |-> Line]
DcStart == CASE InitialPermission \in {"Invalid","Absent"} -> "s1__1"
             [] InitialPermission = "Shared" -> "s1__2"
             [] OTHER -> "s1__3"
ThxStart == CASE InitialPermission \in {"Invalid","Absent"} -> "I ()"
              [] InitialPermission = "Shared" -> "S ()"
              [] OTHER -> "E ()"
Initial == [dc |-> DcStart, thx |-> ThxStart, toDc |-> <<>>, toCpu |-> <<>>,
            read |-> "Idle", write |-> "Idle", app |-> "Idle", loaded |-> (InitialPermission # "Invalid"),
            started |-> FALSE, returnedData |-> FALSE, writeDone |-> FALSE, error |-> FALSE]
Init == ep = Initial
\* Abstract requests/responses retain address and transaction identity. Per-link
\* FIFO is a checked configuration, not a claim that all ECI VCs form one FIFO.
Decode(m) == CASE m = "IS_d" -> "R12" [] m = "IE_d" -> "R13"
               [] m = "SE_d" -> "R23" [] m = "FA_d" -> "A31d"
               [] m = "FA" -> IF ep.dc = "s1pCI__1_A31d" THEN "A31" ELSE "A21"
               [] m = "FAI" -> "A11" [] m = "FAS" -> "A22"
               [] m = "SI" -> "V21" [] m = "EI" -> "V31"
               [] OTHER -> "Unsupported"
Wire(a) == CASE a = "SEND_RA3" -> "AE_d" [] a = "SEND_RA2" -> "AS_d"
             [] a = "SEND_F31" -> "FE_d" [] a = "SEND_F21" -> "IV"
             [] OTHER -> "Unsupported"
ToCpuActions == {"SEND_RA3","SEND_RA2","SEND_F31","SEND_F21"}
ThxStates(p,m) ==
    IF TH!Enabled(ep.thx,p,m) = {} THEN {} ELSE
    LET t == TH!Step(ep.thx,p,m) IN
    IF Len(ep.toDc) + Len(t.outputs) > LinkDepth THEN {} ELSE
    {[ep EXCEPT !.thx = t.next,
       !.toDc = @ \o [i \in 1..Len(t.outputs) |-> Msg(Decode(t.outputs[i].msg))],
       !.toCpu = IF p = "SW" THEN @ ELSE Tail(@),
       !.started = TRUE, !.loaded = @ \/ m \in {"AE_d","AS_d"},
       !.returnedData = @ \/ (\E i \in 1..Len(t.outputs): t.outputs[i].msg = "FA_d")]}
LoadStates == IF ~ep.started /\ InitialPermission = "Invalid" THEN ThxStates("SW",CpuLoad) ELSE {}
ReceiveStates == IF ep.toCpu = <<>> THEN {} ELSE
    LET m == Head(ep.toCpu).type IN ThxStates(IF m \in {"AE_d","AS_d"} THEN "InResp" ELSE "InReq",m)
SubmitStates == IF ep.loaded /\ ep.app = "Idle" /\ Len(ep.toDc) < LinkDepth
    THEN {[ep EXCEPT !.app = "Pending", !.toDc = Append(@,Msg("LCI"))]} ELSE {}
ProcessStates == IF ep.toDc = <<>> THEN {} ELSE
    LET m == Head(ep.toDc).type IN
    IF DC!Enabled(ep.dc,m) = {} THEN {} ELSE
    LET t == DC!Step(ep.dc,m) IN
    IF t.action \in ToCpuActions /\ Len(ep.toCpu) >= LinkDepth THEN {} ELSE
    {[ep EXCEPT !.dc = t.next, !.toDc = Tail(@),
       !.toCpu = IF t.action \in ToCpuActions THEN Append(@,Msg(Wire(t.action))) ELSE @,
       !.read = IF t.action = "RDD" THEN "Data" ELSE @,
       !.write = IF t.action = "WDD" THEN "Data" ELSE @,
       !.app = IF t.action = "SEND_LCIA" \/ (BadEarlyAck /\ t.action = "WDD") THEN "Ready" ELSE @,
       !.error = @ \/ t.action \notin (ToCpuActions \cup {"RDD","WDD","SEND_LCIA","NO_ACTION"})]}
ReadStates == IF ep.read = "Data" /\ Len(ep.toDc) < LinkDepth
    THEN {[ep EXCEPT !.read = "Idle", !.toDc = Append(@,Msg("RDDA"))]} ELSE {}
\* No fairness can enable WriteStates when the actual RX endpoint lacks service.
WriteStates == IF ep.write = "Data" /\ WriteService
    THEN {[ep EXCEPT !.write = "Response"]} ELSE {}
ResponseStates == IF ep.write = "Response" /\ Len(ep.toDc) < LinkDepth
    THEN {[ep EXCEPT !.write = "Idle", !.writeDone = TRUE, !.toDc = Append(@,Msg("WDDA"))]} ELSE {}
AcceptStates == IF ep.app = "Ready" THEN {[ep EXCEPT !.app = "Done"]} ELSE {}
Load == ep' \in LoadStates
ReceiveCpu == ep' \in ReceiveStates
Submit == ep' \in SubmitStates
Process == ep' \in ProcessStates
ReadData == ep' \in ReadStates
WriteData == ep' \in WriteStates
WriteResponse == ep' \in ResponseStates
Accept == ep' \in AcceptStates
InternalStates == ReceiveStates \cup ProcessStates \cup ReadStates \cup WriteStates \cup ResponseStates
Internal == ep' \in InternalStates
Next == Load \/ Submit \/ Internal \/ Accept
Spec == Init /\ [][Next]_vars /\ WF_vars(Load) /\ WF_vars(Submit)
    /\ WF_vars(ReceiveCpu) /\ WF_vars(Process) /\ WF_vars(ReadData)
    /\ WF_vars(WriteData) /\ WF_vars(WriteResponse) /\ WF_vars(Accept)
TypeOK == /\ ep.dc \in {t.state: t \in DcTable}
    /\ ep.thx \in {t.state: t \in ThxTable}
    /\ ep.app \in {"Idle","Pending","Ready","Done"}
    /\ ep.read \in {"Idle","Data"} /\ ep.write \in {"Idle","Data","Response"}
    /\ ep.toDc \in Seq([type : STRING,txn : {0},address : {Line}])
    /\ ep.toCpu \in Seq([type : STRING,txn : {0},address : {Line}])
    /\ Len(ep.toDc) <= LinkDepth /\ Len(ep.toCpu) <= LinkDepth
NoProtocolError == ~ep.error
CompletionSound == ep.app \in {"Ready","Done"} =>
    /\ ep.dc = "s1__1" /\ ep.thx = "I ()" /\ ep.write = "Idle"
    /\ (ep.returnedData => ep.writeDone)
Completes == <> (ep.app = "Done")
NoReturnedData == ~ep.returnedData
\* State mapping to the exact port used by the resource layer. CPU/DC/AXI
\* internals stutter in this projection; no injected port-completion transition.
PortQueues == [q \in {0} |-> CASE ep.app = "Pending" -> <<[type |-> "Invalidate",txn |-> 0,address |-> Line]>>
                       [] ep.app = "Ready" -> <<[type |-> "Ack",txn |-> 0,address |-> Line]>>
                       [] OTHER -> <<>>]
Port == INSTANCE InvalidationPort WITH Clients <- {0}, Addresses <- {Line},
    Queues <- {0}, QueueDepth <- 1, queues <- PortQueues
PortRefinement == Port!Behavior
=============================================================================
