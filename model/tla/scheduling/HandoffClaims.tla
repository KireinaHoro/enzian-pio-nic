--------------------------- MODULE HandoffClaims ---------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS BadLateRelease, BadWakeReturn, CopyFails
Workers == {1,2}
Target(w) == 3-w
VARIABLES phase, claim, returned, failed, wake
v == <<phase,claim,returned,failed,wake>>
\* Two single-thread domains exchange workers. Each outgoing task remains
\* inside yield while the other worker can claim its next activation.
Init == /\ phase = [w \in Workers |-> "Running"]
        /\ claim = [w \in Workers |-> w]
        /\ returned = {} /\ failed = {} /\ wake = FALSE
ClaimStages == [w \in Workers |-> IF claim[w] = 0 THEN "Request" ELSE "Done"]
Claims == INSTANCE ThreadClaim WITH PoolSize <- 2, BadClaim <- FALSE,
          BadReservation <- FALSE, stage <- ClaimStages,
          candidate <- [w \in Workers |-> 0], claim <- claim
Yield(w) == /\ phase[w] = "Running"
            /\ phase' = [phase EXCEPT ![w] = "Read"]
            /\ UNCHANGED <<claim,returned,failed,wake>>
\* Read response certifies autonomous cleanup. Release must precede waiting
\* for a destination thread, even though this task has not yet slept.
Respond(w) == /\ phase[w] = "Read"
              /\ phase' = [phase EXCEPT ![w] = "Claim"]
              /\ claim' = IF BadLateRelease THEN claim ELSE [claim EXCEPT ![w] = 0]
              /\ UNCHANGED <<returned,failed,wake>>
Activate(w) == /\ phase[w] = "Claim"
               /\ Target(w) \notin {claim[x]: x \in Workers}
               /\ claim' = [claim EXCEPT ![w] = Target(w)]
               /\ phase' = [phase EXCEPT ![w] = "Activated"]
               /\ UNCHANGED <<returned,failed,wake>>
\* Return belongs to the calling task, which now runs on the other worker.
Deliver(t) == /\ phase[t] = "Activated"
              /\ phase[Target(t)] \in {"Activated","Done"}
              /\ claim[Target(t)] = t
              /\ phase' = [phase EXCEPT ![t] = "Done"]
              /\ returned' = IF CopyFails THEN returned ELSE returned \cup {t}
              /\ failed' = IF CopyFails THEN failed \cup {t} ELSE failed
              /\ UNCHANGED <<claim,wake>>
UnrelatedWake == /\ wake' = ~wake
                 /\ returned' = IF BadWakeReturn THEN returned \cup {1} ELSE returned
                 /\ UNCHANGED <<phase,claim,failed>>
Step(w) == Yield(w) \/ Respond(w) \/ Activate(w) \/ Deliver(w)
Next == (\E w \in Workers: Step(w)) \/ UnrelatedWake
Spec == Init /\ [][Next]_v /\ (\A w \in Workers: WF_v(Step(w)))
TypeOK == /\ phase \in [Workers -> {"Running","Read","Claim","Activated","Done"}]
          /\ claim \in [Workers -> 0..2] /\ returned \subseteq Workers
          /\ failed \subseteq Workers /\ wake \in BOOLEAN
UniqueClaim == Claims!UniqueClaim
CapacitySafe == Claims!CapacitySafe
FreshReturn == \A t \in returned: phase[t] = "Done" /\ claim[Target(t)] = t
FailClosed == failed \cap returned = {}
Completes == <>(\A w \in Workers: phase[w] = "Done")
=============================================================================
