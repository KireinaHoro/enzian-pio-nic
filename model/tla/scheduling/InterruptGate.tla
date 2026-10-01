--------------------------- MODULE InterruptGate ---------------------------
EXTENDS Naturals, FiniteSets
CONSTANTS BadRearm, BadRelease
Events == {"timer", "ipi"}
VARIABLES requested, released, pending, serviced, held, gate, rearmed, timer
v == <<requested,released,pending,serviced,held,gate,rearmed,timer>>
Init == /\ requested = {} /\ released = {} /\ pending = {} /\ serviced = {}
        /\ held = TRUE /\ gate = FALSE /\ rearmed = FALSE /\ timer = "armed"
Request(e) == /\ e \notin requested /\ (e # "timer" \/ timer = "expired")
              /\ requested' = requested \cup {e} /\ gate' = TRUE
              /\ UNCHANGED <<released,pending,serviced,held,rearmed,timer>>
Release == /\ gate /\ requested \ released # {}
           /\ released' = requested /\ held' = FALSE
           /\ gate' = IF BadRelease THEN FALSE ELSE gate
           /\ UNCHANGED <<requested,pending,serviced,rearmed,timer>>
\* IPI send/architectural pending occurs after the target release request is acknowledged.
Deliver(e) == /\ e \in released \ (pending \cup serviced)
              /\ pending' = pending \cup {e}
              /\ UNCHANGED <<requested,released,serviced,held,gate,rearmed,timer>>
Service(e) == /\ e \in pending /\ ~held /\ pending' = pending \ {e}
              /\ serviced' = serviced \cup {e}
              /\ UNCHANGED <<requested,released,held,gate,rearmed,timer>>
Rearm == /\ gate /\ requested # {}
         /\ (BadRearm \/ requested = serviced)
         /\ gate' = FALSE /\ rearmed' = TRUE
         /\ UNCHANGED <<requested,released,pending,serviced,held,timer>>
Block == /\ ~gate /\ ~held /\ held' = TRUE
         /\ UNCHANGED <<requested,released,pending,serviced,gate,rearmed,timer>>
Expire == /\ timer = "armed" /\ timer' = "expired"
          /\ UNCHANGED <<requested,released,pending,serviced,held,gate,rearmed>>
Disable == /\ timer = "armed" /\ timer' = "disabled"
           /\ UNCHANGED <<requested,released,pending,serviced,held,gate,rearmed>>
Replace == /\ timer = "disabled" /\ timer' = "armed"
           /\ UNCHANGED <<requested,released,pending,serviced,held,gate,rearmed>>
Next == \/ Release \/ Rearm \/ Block \/ Expire \/ Disable \/ Replace
        \/ \E e \in Events: Request(e) \/ Deliver(e) \/ Service(e)
Spec == Init /\ [][Next]_v /\ WF_v(Release) /\ WF_v(Rearm)
        /\ (\A e \in Events: WF_v(Deliver(e)) /\ WF_v(Service(e)))
TypeOK == /\ requested \subseteq Events /\ released \subseteq requested
          /\ pending \subseteq released /\ serviced \subseteq released
          /\ pending \cap serviced = {} /\ held \in BOOLEAN /\ gate \in BOOLEAN
          /\ rearmed \in BOOLEAN /\ timer \in {"armed","expired","disabled"}
NoPrematureReblock == released \ serviced # {} => gate
InterruptProgress == \A e \in Events: (e \in requested) ~> (e \in serviced)
=============================================================================
