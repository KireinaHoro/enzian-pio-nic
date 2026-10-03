-------------------------- MODULE DomainIsolation --------------------------
EXTENDS Naturals, FiniteSets, SelectedAddresses
CONSTANTS BadReadAdmission, BadNoFault, BadNoKill, BadTxFaultGate,
          BadScrub, BadRxMask, BadWriteAdmission, IdlePeer, BadIdleFence, BadFaultOwner
Workers == 1..2
Domains == 1..2
Kinds == {"RX","TX"}
PAs == Workers \X Domains \X Kinds
PA(w,d,k) == ControlTable[d][w][IF k = "RX" THEN 1 ELSE 2][1]
\* Two distinct lifetime PAs share a worker/color; they are NOT the same line.
DistinctPAs == \A p,q \in PAs: PA(p[1],p[2],p[3]) = PA(q[1],q[2],q[3]) => p = q
SharedColor == \A w \in Workers,k \in Kinds: ColorOf[PA(w,1,k)] = ColorOf[PA(w,2,k)]
VARIABLES enabled, owner, failed, killed, notified, rogue, read, tx, scrub, handoff,
          pending, pendingDomain, failedAtCommit, leaked
v == <<enabled,owner,failed,killed,notified,rogue,read,tx,scrub,handoff,
       pending,pendingDomain,failedAtCommit,leaked>>
\* Byte values are provenance labels: 0 is zero; d is an arbitrary secret of d.
\* Existing transport contracts supply drained AXI/LCIA before owner change.
Init == /\ enabled = IF IdlePeer THEN {1} ELSE Workers
        /\ owner = [w \in Workers |-> IF IdlePeer THEN 1 ELSE w]
        /\ failed = {} /\ killed = {} /\ notified = {} /\ rogue = {} /\ read = {}
        /\ tx = [w \in Workers |-> IF IdlePeer /\ w = 2 THEN 0 ELSE w] /\ scrub = {}
        /\ handoff = FALSE
        /\ pending = [w \in Workers |-> "Idle"]
        /\ pendingDomain = [w \in Workers |-> w]
        /\ failedAtCommit = [w \in Workers |-> FALSE] /\ leaked = FALSE
WorkerFailed(w) == owner[w] \in failed /\ (w \in enabled \/ BadIdleFence)
Live(w,d) == w \in enabled /\ owner[w] = d /\ ~WorkerFailed(w)
Read(w,d,k) ==
  /\ <<w,d,k>> \notin read /\ d \notin killed
  /\ (~Live(w,d) \/ k = "RX" \/ w \notin scrub)
  /\ LET allowed == Live(w,d) \/ BadReadAdmission
         violation == ~Live(w,d)
         \* Valid RX bytes belong to owner; padding can retain the other domain.
         rxTail == IF BadRxMask THEN 3-owner[w] ELSE 0
     IN /\ leaked' = (leaked \/ (allowed /\
               (IF k = "TX" THEN tx[w] \notin {0,d}
                ELSE owner[w] # d \/ rxTail \notin {0,d})))
        /\ rogue' = IF violation THEN rogue \cup {d} ELSE rogue
        /\ failed' = IF violation /\ ~BadNoFault THEN failed \cup {IF BadFaultOwner THEN owner[w] ELSE d} ELSE failed
  /\ read' = read \cup {<<w,d,k>>}
  /\ UNCHANGED <<enabled,owner,killed,notified,tx,scrub,handoff,pending,pendingDomain,failedAtCommit>>
\* Fault is sticky in hardware. IRQ/workqueue/SIGKILL is asynchronous; DECERR
\* alone cannot kill (the CPU/DCS may cache the rejected zero response).
Kill(d) == /\ d \in notified \ killed /\ ~BadNoKill
           /\ killed' = killed \cup {d}
           /\ UNCHANGED <<enabled,owner,failed,notified,rogue,read,tx,scrub,handoff,
                          pending,pendingDomain,failedAtCommit,leaked>>
\* A TX trigger read precedes invalidations and dirty-data collection. A fault
\* on another PA may occur between this read and the DMA metadata admission.
Trigger(w) == /\ pending[w] = "Idle" /\ Live(w,owner[w]) /\ w \notin scrub
              /\ pending' = [pending EXCEPT ![w] = "Collect"]
              /\ pendingDomain' = [pendingDomain EXCEPT ![w] = owner[w]]
              /\ UNCHANGED <<enabled,owner,failed,killed,notified,rogue,read,tx,scrub,handoff,leaked,failedAtCommit>>
Commit(w) == /\ pending[w] = "Collect"
             /\ LET admitted == pendingDomain[w] \notin failed \/ BadTxFaultGate
                 IN /\ pending' = [pending EXCEPT ![w] = IF admitted THEN "Sent" ELSE "Rejected"]
                    /\ leaked' = (leaked \/ (admitted /\ tx[w] \notin {0,pendingDomain[w]}))
             /\ failedAtCommit' = [failedAtCommit EXCEPT ![w] = pendingDomain[w] \in failed]
             /\ UNCHANGED <<enabled,owner,failed,killed,notified,rogue,read,tx,scrub,handoff,pendingDomain>>
Notify == /\ failed \ notified # {}
          /\ notified' = failed
          /\ UNCHANGED <<enabled,owner,failed,killed,rogue,read,tx,scrub,handoff,
                         pending,pendingDomain,failedAtCommit,leaked>>
\* Cleanup AW is not evidence of a new rogue read. Old RX AW is discarded;
\* TX AW may update the physical buffer only for the installed lifetime.
Writeback(w,d) == /\ w \notin scrub
                  /\ ((w \in enabled /\ owner[w] = d) \/ BadWriteAdmission)
                  /\ tx' = [tx EXCEPT ![w] = d]
                  /\ UNCHANGED <<enabled,owner,failed,killed,notified,rogue,read,scrub,handoff,
                                 pending,pendingDomain,failedAtCommit,leaked>>
Install == /\ ~handoff /\ pending[1] # "Collect"
           /\ owner' = [owner EXCEPT ![1] = 2]
           /\ handoff' = TRUE
           /\ scrub' = IF BadScrub THEN scrub ELSE scrub \cup {1}
           /\ UNCHANGED <<enabled,failed,killed,notified,rogue,read,tx,pending,pendingDomain,failedAtCommit,leaked>>
\* A never-installed worker's owner register may equal a real domain code.
\* That value has no authority until its enabled bit is set.
ActivatePeer == /\ 2 \notin enabled /\ 2 \notin failed /\ ~WorkerFailed(2)
                /\ enabled' = enabled \cup {2}
                /\ owner' = [owner EXCEPT ![2] = 2]
                /\ scrub' = scrub \cup {2}
                /\ UNCHANGED <<failed,killed,notified,rogue,read,tx,handoff,
                               pending,pendingDomain,failedAtCommit,leaked>>
\* Completion represents the LAST scrub B response, not AW/W acceptance.
Scrub(w) == /\ w \in scrub /\ tx' = [tx EXCEPT ![w] = 0]
            /\ scrub' = scrub \ {w}
            /\ UNCHANGED <<enabled,owner,failed,killed,notified,rogue,read,handoff,pending,pendingDomain,failedAtCommit,leaked>>
Next == (\E w \in Workers,d \in Domains,k \in Kinds: Read(w,d,k))
        \/ (\E w \in Workers,d \in Domains: Writeback(w,d))
        \/ (\E d \in Domains: Kill(d))
        \/ (\E w \in Workers: Trigger(w) \/ Commit(w) \/ Scrub(w)) \/ Install \/ Notify \/ ActivatePeer
Spec == Init /\ [][Next]_v /\ WF_v(Notify) /\ (\A d \in Domains: WF_v(Kill(d)))
TypeOK == /\ enabled \subseteq Workers /\ owner \in [Workers -> Domains] /\ failed \subseteq Domains
          /\ notified \subseteq failed /\ killed \subseteq Domains /\ rogue \subseteq Domains /\ read \subseteq PAs
          /\ tx \in [Workers -> Domains \cup {0}] /\ scrub \subseteq Workers
          /\ handoff \in BOOLEAN /\ pendingDomain \in [Workers -> Domains]
          /\ pending \in [Workers -> {"Idle","Collect","Sent","Rejected"}]
          /\ leaked \in BOOLEAN /\ failedAtCommit \in [Workers -> BOOLEAN]
FaultRecorded == rogue \subseteq failed
NoIdleFence == \A w \in Workers: w \notin enabled => ~WorkerFailed(w)
NoWrongKill == killed \subseteq rogue
NoLeak == ~leaked
NoNewTxAfterFault == \A w \in Workers: pending[w] = "Sent" => ~failedAtCommit[w]
InactivePaKilled == \A d \in Domains: (d \in rogue) ~> (d \in killed)
=============================================================================
