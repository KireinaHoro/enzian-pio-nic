-------------------------- MODULE DirectoryAdmission --------------------------
EXTENDS Naturals, FiniteSets, Sequences
CONSTANTS Ways, BadMapping, BadEarlyRequest, BadSilentEviction, LocalRequest
\* One full directory set. CPU ways include reserved in-flight fills.
\* This is the matched-cache contract, not an implementation of ThunderX.
Lines == 1..(Ways+2)
CpuSet(a) == IF BadMapping THEN a % 2 ELSE 0
Messages == [type : {"Downgrade","Writeback"}, address : Lines]
VARIABLES cpu, requests, directory, evictions, retiring, localPending, localDone
v == <<cpu,requests,directory,evictions,retiring,localPending,localDone>>
Queued == {evictions[i].address: i \in 1..Len(evictions)}
Init == /\ cpu = 1..Ways /\ directory = cpu /\ requests = {}
        /\ evictions = <<>> /\ retiring = {}
        /\ localPending = LocalRequest /\ localDone = FALSE
CpuEntries(a) == {b \in cpu: CpuSet(a) = CpuSet(b)}
Send(a) == /\ a \notin cpu \cup directory \cup requests
    /\ (BadEarlyRequest \/ Cardinality(CpuEntries(a)) < Ways)
    /\ cpu' = (IF BadEarlyRequest THEN cpu ELSE cpu \cup {a})
    /\ requests' = requests \cup {a}
    /\ UNCHANGED <<directory,evictions,retiring,localPending,localDone>>
\* A CPU victim can be generated before the new request is sent. Its directory
\* entry remains charged; this models request overtaking downgrade/writeback.
Evict(a) == /\ a \in cpu \cap directory /\ a \notin requests
    /\ Len(evictions) < Ways
    /\ cpu' = cpu \ {a}
    /\ evictions' = (IF BadSilentEviction THEN evictions ELSE
        Append(evictions, [type |-> IF a % 2 = 0 THEN "Writeback" ELSE "Downgrade", address |-> a]))
    /\ UNCHANGED <<requests,directory,retiring,localPending,localDone>>
Invalidate(a) == /\ a \in cpu \cap directory /\ a \notin requests
    /\ cpu' = cpu \ {a} /\ retiring' = retiring \cup {a}
    /\ UNCHANGED <<requests,directory,evictions,localPending,localDone>>
Unlock(a) == /\ a \in retiring
    /\ retiring' = retiring \ {a} /\ directory' = directory \ {a}
    /\ UNCHANGED <<cpu,requests,evictions,localPending,localDone>>
Deliver == /\ evictions # <<>>
    /\ directory' = directory \ {Head(evictions).address}
    /\ evictions' = Tail(evictions) /\ UNCHANGED <<cpu,requests,retiring,localPending,localDone>>
Admit(a) == /\ a \in requests \ directory /\ Cardinality(directory) < Ways
    /\ directory' = directory \cup {a}
    /\ UNCHANGED <<cpu,requests,evictions,retiring,localPending,localDone>>
Complete(a) == /\ a \in requests \cap directory
    /\ requests' = requests \ {a}
    /\ UNCHANGED <<cpu,directory,evictions,retiring,localPending,localDone>>
\* auto_lock LCI on an absent line allocates s1pUL__1 until final UL.
\* There is no CPU demand in this target set in the local-only counterexample.
LocalAdmit == /\ localPending /\ Cardinality(directory) < Ways
    /\ localPending' = FALSE /\ localDone' = TRUE
    /\ directory' = directory \cup {Ways+2} /\ retiring' = retiring \cup {Ways+2}
    /\ UNCHANGED <<cpu,requests,evictions>>
Next == LocalAdmit \/ Deliver \/ \E a \in Lines:
    (~LocalRequest /\ (Send(a) \/ Evict(a) \/ Invalidate(a))) \/ Unlock(a) \/ Admit(a) \/ Complete(a)
Spec == Init /\ [][Next]_v /\ WF_v(Deliver) /\ WF_v(LocalAdmit)
    /\ \A a \in Lines: WF_v(Unlock(a)) /\ SF_v(Admit(a)) /\ WF_v(Complete(a))
TypeOK == /\ cpu \subseteq Lines /\ requests \subseteq Lines
    /\ directory \subseteq Lines /\ retiring \subseteq Lines
    /\ localPending \in BOOLEAN /\ localDone \in BOOLEAN
    /\ evictions \in Seq(Messages) /\ Len(evictions) <= Ways
Accounting == /\ Cardinality(directory) <= Ways
    /\ (\A a \in Lines: Cardinality(CpuEntries(a)) <= Ways)
    /\ requests \subseteq cpu
    /\ directory = (cpu \cap directory) \cup Queued \cup retiring
    /\ cpu \cap (Queued \cup retiring) = {} /\ Queued \cap retiring = {}
    /\ Cardinality(Queued) = Len(evictions)
\* Pigeonhole lemma: a reserved new request plus Ways directory entries cannot
\* all fit in the matched CPU set. At least one directory charge awaits release.
BlockingHasRelease == (requests \ directory # {} /\ Cardinality(directory) = Ways)
    => Queued \cup retiring # {}
LocalProgress == localPending ~> localDone
Admission == \A a \in Lines: (a \in requests) ~> (a \notin requests)
=============================================================================
