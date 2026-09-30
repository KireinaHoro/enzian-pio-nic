----------------------------- MODULE ThreadClaim -----------------------------
EXTENDS Naturals, FiniteSets
CONSTANT BadClaim
Workers == {1,2}
Threads == {1,2}
VARIABLES stage, candidate, claim, available, result
v == <<stage,candidate,claim,available,result>>
Init == /\ stage = [w \in Workers |-> "Select"] /\ candidate = [w \in Workers |-> 0]
        /\ claim = [w \in Workers |-> 0] /\ available \in SUBSET Threads
        /\ result = [w \in Workers |-> "Pending"]
Choose(w,t) == /\ stage[w] = "Select" /\ t \in available
               /\ t \notin {claim[x]: x \in Workers}
               /\ candidate' = [candidate EXCEPT ![w] = t]
               /\ stage' = [stage EXCEPT ![w] = "Claim"]
               /\ UNCHANGED <<claim,available,result>>
Claim(w) == /\ stage[w] = "Claim" /\ candidate[w] \in available
            /\ BadClaim \/ candidate[w] \notin {claim[x]: x \in Workers}
            /\ claim' = [claim EXCEPT ![w] = candidate[w]]
            /\ stage' = [stage EXCEPT ![w] = "Done"]
            /\ result' = [result EXCEPT ![w] = "Installed"]
            /\ UNCHANGED <<candidate,available>>
Retry(w) == /\ stage[w] = "Claim"
            /\ (candidate[w] \notin available \/ candidate[w] \in {claim[x]: x \in Workers})
            /\ stage' = [stage EXCEPT ![w] = "Select"]
            /\ candidate' = [candidate EXCEPT ![w] = 0]
            /\ UNCHANGED <<claim,available,result>>
Unavailable(w) == /\ stage[w] = "Select" /\ available \ {claim[x]: x \in Workers} = {}
                  /\ stage' = [stage EXCEPT ![w] = "Done"]
                  /\ result' = [result EXCEPT ![w] = "Unavailable"]
                  /\ UNCHANGED <<candidate,claim,available>>
Withdraw(t) == /\ t \in available /\ t \notin {claim[x]: x \in Workers}
               /\ available' = available \ {t} /\ UNCHANGED <<stage,candidate,claim,result>>
Step(w) == (\E t \in Threads: Choose(w,t)) \/ Claim(w) \/ Retry(w) \/ Unavailable(w)
Next == (\E w \in Workers: Step(w)) \/ (\E t \in Threads: Withdraw(t))
Spec == Init /\ [][Next]_v /\ (\A w \in Workers: WF_v(Step(w)))
TypeOK == /\ stage \in [Workers -> {"Select","Claim","Done"}]
          /\ candidate \in [Workers -> Threads \cup {0}] /\ claim \in [Workers -> Threads \cup {0}]
          /\ available \subseteq Threads /\ result \in [Workers -> {"Pending","Installed","Unavailable"}]
UniqueClaim == \A w,x \in Workers: w # x /\ claim[w] # 0 => claim[w] # claim[x]
ClaimAvailable == \A w \in Workers: claim[w] # 0 => claim[w] \in available
SelectionFinishes == \A w \in Workers: <> (stage[w] = "Done")
=============================================================================
