--------------------------- MODULE PoolBootstrap ---------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS InitFails, BadEarlyPublish
Threads == {1,2}
Workers == {1,2}
VARIABLES initialized, registered, waiting, published, failed, stage, candidate, claim
v == <<initialized,registered,waiting,published,failed,stage,candidate,claim>>
C == INSTANCE ThreadClaim WITH PoolSize <- 2, BadClaim <- FALSE, BadReservation <- FALSE
pool == <<initialized,registered,waiting,published,failed>>
Init == /\ C!Init /\ initialized = {} /\ registered = {} /\ waiting = {}
        /\ published = FALSE /\ failed = FALSE
Initialize(t) == /\ ~failed /\ t \notin initialized
                 /\ ~(InitFails /\ t = 2)
                 /\ initialized' = initialized \cup {t}
                 /\ UNCHANGED <<registered,waiting,published,failed,stage,candidate,claim>>
FailInitialization == /\ InitFails /\ ~failed /\ 2 \notin initialized
                      /\ failed' = TRUE
                      /\ UNCHANGED <<initialized,registered,waiting,published,stage,candidate,claim>>
Register(t) == /\ ~failed /\ t \in initialized \ registered
               /\ registered' = registered \cup {t}
               /\ UNCHANGED <<initialized,waiting,published,failed,stage,candidate,claim>>
Park(t) == /\ ~failed /\ t \in registered \ waiting
           /\ t \notin {claim[w]: w \in Workers}
           /\ waiting' = waiting \cup {t}
           /\ UNCHANGED <<initialized,registered,published,failed,stage,candidate,claim>>
Publish == /\ ~failed /\ ~published
           /\ (registered = Threads \/ (BadEarlyPublish /\ registered # {}))
           /\ published' = TRUE
           /\ UNCHANGED <<initialized,registered,waiting,failed,stage,candidate,claim>>
Reserve(w) == /\ published /\ ~failed /\ C!Reserve(w) /\ UNCHANGED pool
Choose(w,t) == /\ ~failed /\ t \in waiting /\ C!Choose(w,t) /\ UNCHANGED pool
Claim(w) == /\ ~failed /\ candidate[w] \in waiting /\ C!Claim(w)
            /\ waiting' = waiting \ {candidate[w]}
            /\ UNCHANGED <<initialized,registered,published,failed>>
Retry(w) == /\ C!Retry(w) /\ UNCHANGED pool
Task(t) == Initialize(t) \/ Register(t) \/ Park(t)
Dispatch(w) == Reserve(w) \/ (\E t \in Threads: Choose(w,t)) \/ Claim(w) \/ Retry(w)
Next == (\E t \in Threads: Task(t)) \/ (\E w \in Workers: Dispatch(w)) \/ Publish \/ FailInitialization
Spec == Init /\ [][Next]_v /\ WF_v(Publish) /\ WF_v(FailInitialization)
        /\ (\A t \in Threads: WF_v(Task(t))) /\ (\A w \in Workers: WF_v(Dispatch(w)))
TypeOK == /\ C!TypeOK /\ initialized \subseteq Threads /\ registered \subseteq Threads
          /\ waiting \subseteq Threads /\ published \in BOOLEAN /\ failed \in BOOLEAN
PublicationReady == published => registered = Threads /\ initialized = Threads /\ ~failed
UniqueClaim == C!UniqueClaim
CapacitySafe == C!CapacitySafe
InitializedClaim == \A w \in Workers: claim[w] # 0 => claim[w] \in registered \cap initialized
FailClosed == failed => ~published /\ (\A w \in Workers: claim[w] = 0)
Completes == IF InitFails THEN <>failed ELSE <>(\A w \in Workers: stage[w] = "Done")
=============================================================================
