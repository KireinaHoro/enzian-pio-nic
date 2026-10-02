---------------------------- MODULE IdleDispatch ----------------------------
EXTENDS Naturals
CONSTANTS BadDuplicate, BadLostKick, BadOrdinaryIrq, BadCleanupDependency
VARIABLES phase, pending, wake, reader, accepted, completed, cleanup,
          irq, bus, otherDone, fenced
v == <<phase,pending,wake,reader,accepted,completed,cleanup,
       irq,bus,otherDone,fenced>>
\* One idle activation followed by one ordinary cooperative handoff.
\* IRQs may be delivered repeatedly, including after notification consumption.
Init == /\ phase = "Idle" /\ pending = FALSE /\ wake = FALSE
        /\ reader = FALSE /\ accepted = 0 /\ completed = 0
        /\ cleanup = FALSE /\ irq = FALSE /\ bus = "Free"
        /\ otherDone = FALSE /\ fenced = FALSE
ReserveIdle == /\ phase = "Idle"
               /\ phase' = "IdleReserved" /\ pending' = TRUE /\ irq' = TRUE
               /\ UNCHANGED <<wake,reader,accepted,completed,cleanup,bus,otherDone,fenced>>
\* Hard IRQ only wakes dispatch; it never reads PreemptInfo.
Kick == /\ irq /\ ~wake /\ wake' = TRUE
        /\ pending' = IF BadLostKick THEN FALSE ELSE pending
        /\ UNCHANGED <<phase,reader,accepted,completed,cleanup,irq,bus,otherDone,fenced>>
\* Per-worker reader serialization and sticky-status recheck before the
\* side-effecting read. A duplicate local wake alone is not authorization.
Dispatch == /\ wake /\ ~reader
            /\ (pending \/ BadDuplicate)
            /\ reader' = TRUE /\ wake' = FALSE
            /\ UNCHANGED <<phase,pending,accepted,completed,cleanup,irq,bus,otherDone,fenced>>
DropDuplicate == /\ wake /\ ~pending /\ ~reader
                 /\ wake' = FALSE
                 /\ UNCHANGED <<phase,pending,reader,accepted,completed,cleanup,irq,bus,otherDone,fenced>>
AcceptIdle == /\ reader /\ bus = "Free" /\ accepted = 0
              /\ pending /\ pending' = FALSE /\ accepted' = 1
              /\ bus' = "Info" /\ phase' = "IdleRead"
              /\ UNCHANGED <<wake,reader,completed,cleanup,irq,otherDone,fenced>>
RespondIdle == /\ phase = "IdleRead" /\ bus = "Info"
               /\ completed' = 1 /\ bus' = "Free" /\ phase' = "Running"
               /\ reader' = FALSE
               /\ UNCHANGED <<pending,wake,accepted,cleanup,irq,otherDone,fenced>>
\* Closing the old kick can race dispatch and its response. No new kick
\* is issued for the ordinary handoff; all pending duplicates drain first.
DrainKick == /\ phase = "Running" /\ irq /\ ~reader
             /\ irq' = FALSE /\ wake' = FALSE
             /\ UNCHANGED <<phase,pending,reader,accepted,completed,cleanup,bus,otherDone,fenced>>
Preempt == /\ phase = "Running" /\ ~irq /\ ~wake /\ ~reader
           /\ phase' = "Preempt" /\ irq' = BadOrdinaryIrq
           /\ UNCHANGED <<pending,wake,reader,accepted,completed,cleanup,bus,otherDone,fenced>>
Yield == /\ phase = "Preempt" /\ ~reader
         /\ phase' = "Yield" /\ fenced' = TRUE /\ reader' = TRUE
         /\ UNCHANGED <<pending,wake,accepted,completed,cleanup,irq,bus,otherDone>>
AcceptHandoff == /\ phase = "Yield" /\ bus = "Free"
                 /\ phase' = "HandoffRead" /\ accepted' = 2 /\ bus' = "Info"
                 /\ UNCHANGED <<pending,wake,reader,completed,cleanup,irq,otherDone,fenced>>
\* Independent coherence cleanup can finish while the shared AXI-Lite bus
\* is occupied by the read. No following write, IRQ, or callback is needed.
Cleanup == /\ phase = "HandoffRead" /\ ~cleanup
           /\ (~BadCleanupDependency \/ bus = "Free")
           /\ cleanup' = TRUE
           /\ UNCHANGED <<phase,pending,wake,reader,accepted,completed,irq,bus,otherDone,fenced>>
RespondHandoff == /\ phase = "HandoffRead" /\ cleanup
                  /\ phase' = "Done" /\ completed' = 2 /\ bus' = "Free"
                  /\ reader' = FALSE
                  /\ UNCHANGED <<pending,wake,accepted,cleanup,irq,otherDone,fenced>>
\* Other control traffic may wait behind the ordinary read, or take the bus
\* before it. This is deliberately a shared, single-outstanding control bus.
OtherAccept == /\ ~otherDone /\ bus = "Free"
               /\ bus' = "Other"
               /\ UNCHANGED <<phase,pending,wake,reader,accepted,completed,cleanup,irq,otherDone,fenced>>
OtherRespond == /\ bus = "Other" /\ bus' = "Free" /\ otherDone' = TRUE
                /\ UNCHANGED <<phase,pending,wake,reader,accepted,completed,cleanup,irq,fenced>>
Next == ReserveIdle \/ Kick \/ Dispatch \/ DropDuplicate \/ AcceptIdle \/
        RespondIdle \/ DrainKick \/ Preempt \/ Yield \/ AcceptHandoff \/
        Cleanup \/ RespondHandoff \/ OtherAccept \/ OtherRespond
Spec == Init /\ [][Next]_v
        /\ WF_v(ReserveIdle) /\ WF_v(Kick) /\ WF_v(Dispatch)
        /\ WF_v(DropDuplicate) /\ WF_v(AcceptIdle) /\ WF_v(RespondIdle)
        /\ WF_v(DrainKick) /\ WF_v(Preempt) /\ WF_v(Yield)
        /\ WF_v(AcceptHandoff) /\ WF_v(Cleanup) /\ WF_v(RespondHandoff)
        /\ WF_v(OtherAccept) /\ WF_v(OtherRespond)
TypeOK == /\ phase \in {"Idle","IdleReserved","IdleRead","Running",
                        "Preempt","Yield","HandoffRead","Done"}
          /\ accepted \in 0..2 /\ completed \in 0..2
          /\ bus \in {"Free","Info","Other"}
          /\ <<pending,wake,reader,cleanup,irq,otherDone,fenced>> \in
             BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN
AuthorizedReader == reader => phase \in {"IdleReserved","IdleRead","Yield","HandoffRead"}
StickyNotification == phase = "IdleReserved" => pending
NoOrdinaryIrq == phase \in {"Preempt","Yield","HandoffRead","Done"} => ~irq
NoReturn == phase \in {"Yield","HandoffRead","Done"} => fenced
Readiness == completed = 2 => cleanup
Completes == <>(phase = "Done")
ControlProgress == <>otherDone
=============================================================================
