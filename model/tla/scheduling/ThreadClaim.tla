----------------------------- MODULE ThreadClaim -----------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS BadClaim, BadReservation, PoolSize
Workers == {1,2}
Threads == 1..PoolSize
VARIABLES stage, candidate, claim
v == <<stage,candidate,claim>>
\* All threads exist before publication of NIC capacity. No individual withdrawal.
Init == /\ stage = [w \in Workers |-> "Request"]
        /\ candidate = [w \in Workers |-> 0] /\ claim = [w \in Workers |-> 0]
Reserved == {w \in Workers: stage[w] \in {"Select","Claim"}}
Assigned == {w \in Workers: stage[w] = "Done"}
Free == Threads \ {claim[w]: w \in Workers}
\* NIC arbitration commits the reservation before dispatch to a kernel CPU.
Reserve(w) == /\ stage[w] = "Request"
              /\ (BadReservation \/ Cardinality(Reserved \cup Assigned) < PoolSize)
              /\ stage' = [stage EXCEPT ![w] = "Select"]
              /\ UNCHANGED <<candidate,claim>>
Choose(w,t) == /\ stage[w] = "Select" /\ t \in Free
               /\ candidate' = [candidate EXCEPT ![w] = t]
               /\ stage' = [stage EXCEPT ![w] = "Claim"]
               /\ UNCHANGED claim
Claim(w) == /\ stage[w] = "Claim"
            /\ (BadClaim \/ candidate[w] \in Free)
            /\ claim' = [claim EXCEPT ![w] = candidate[w]]
            /\ stage' = [stage EXCEPT ![w] = "Done"]
            /\ UNCHANGED candidate
Retry(w) == /\ stage[w] = "Claim" /\ candidate[w] \notin Free
            /\ stage' = [stage EXCEPT ![w] = "Select"]
            /\ candidate' = [candidate EXCEPT ![w] = 0]
            /\ UNCHANGED claim
\* Composition ports; standalone Next retains its original one-shot scope.
Release(w) == /\ stage[w] = "Done"
              /\ stage' = [stage EXCEPT ![w] = "Request"]
              /\ candidate' = [candidate EXCEPT ![w] = 0]
              /\ claim' = [claim EXCEPT ![w] = 0]
Cancel(w) == /\ stage[w] \in {"Select","Claim"}
             /\ stage' = [stage EXCEPT ![w] = "Request"]
             /\ candidate' = [candidate EXCEPT ![w] = 0]
             /\ claim' = [claim EXCEPT ![w] = 0]
Step(w) == Reserve(w) \/ (\E t \in Threads: Choose(w,t)) \/ Claim(w) \/ Retry(w)
Next == \E w \in Workers: Step(w)
Spec == Init /\ [][Next]_v /\ (\A w \in Workers: WF_v(Step(w)))
TypeOK == /\ stage \in [Workers -> {"Request","Select","Claim","Done"}]
          /\ candidate \in [Workers -> Threads \cup {0}]
          /\ claim \in [Workers -> Threads \cup {0}]
UniqueClaim == \A w,x \in Workers: w # x /\ claim[w] # 0 => claim[w] # claim[x]
CapacitySafe == Cardinality(Reserved \cup Assigned) <= PoolSize
ReservationBacked == Reserved # {} => Cardinality(Free) >= Cardinality(Reserved)
SelectionFinishes == \A w \in Workers: (w \in Reserved) ~> (stage[w] = "Done")
=============================================================================
