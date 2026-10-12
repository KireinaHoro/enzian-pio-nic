---------------------------- MODULE IdleDispatch ----------------------------
EXTENDS Naturals
CONSTANTS BadDuplicate, BadLostKick, BadOrdinaryIrq, BadCleanupDependency
VARIABLES phase, pending, wake, reader, accepted, completed, cleanup,
          irq,ordinaryIrqs, bus, otherDone, fenced
v == <<phase,pending,wake,reader,accepted,completed,cleanup,
       irq,ordinaryIrqs,bus,otherDone,fenced>>
\* One idle activation followed by one ordinary cooperative handoff.
\* IRQs may be delivered repeatedly, including after notification consumption.
Init == /\ phase = "Idle" /\ pending = FALSE /\ wake = FALSE
        /\ reader = FALSE /\ accepted = 0 /\ completed = 0
        /\ cleanup = FALSE /\ irq = FALSE /\ ordinaryIrqs = 0 /\ bus = "Free"
        /\ otherDone = FALSE /\ fenced = FALSE
ReserveIdle == /\ phase = "Idle"
               /\ phase' = "IdleReserved" /\ pending' = TRUE /\ irq' = TRUE
               /\ UNCHANGED <<wake,reader,accepted,completed,cleanup,ordinaryIrqs,bus,otherDone,fenced>>
\* Hard IRQ only wakes dispatch; it never reads DeschedInfo.
Kick == /\ irq /\ ~wake /\ wake' = TRUE
        /\ pending' = IF BadLostKick THEN FALSE ELSE pending
        /\ UNCHANGED <<phase,reader,accepted,completed,cleanup,irq,ordinaryIrqs,bus,otherDone,fenced>>
\* Per-worker reader serialization and sticky-status recheck before the
\* side-effecting read. A duplicate local wake alone is not authorization.
Dispatch == /\ wake /\ ~reader
            /\ ((pending /\ phase = "IdleReserved") \/ BadDuplicate)
            /\ reader' = TRUE /\ wake' = FALSE
            /\ UNCHANGED <<phase,pending,accepted,completed,cleanup,irq,ordinaryIrqs,bus,otherDone,fenced>>
DropDuplicate == /\ wake /\ ~reader /\ (~pending \/ phase # "IdleReserved")
                 /\ wake' = FALSE
                 /\ UNCHANGED <<phase,pending,reader,accepted,completed,cleanup,irq,ordinaryIrqs,bus,otherDone,fenced>>
AcceptIdle == /\ reader /\ bus = "Free" /\ accepted = 0
              /\ pending /\ accepted' = 1
              /\ bus' = "Info" /\ phase' = "IdleRead"
              /\ UNCHANGED <<pending,wake,reader,completed,cleanup,irq,ordinaryIrqs,otherDone,fenced>>
RespondIdle == /\ phase = "IdleRead" /\ bus = "Info"
               /\ completed' = 1 /\ bus' = "Free" /\ phase' = "Running"
               /\ reader' = FALSE /\ pending' = FALSE
               /\ UNCHANGED <<wake,accepted,cleanup,irq,ordinaryIrqs,otherDone,fenced>>
\* A delayed duplicate of the idle IRQ can arrive during ordinary handoff.
\* Closing the old notification is not a prerequisite for yielding.
DrainKick == /\ phase \in {"Running","Preempt","Yield","HandoffRead","Done"} /\ irq
             /\ irq' = FALSE
             /\ UNCHANGED <<phase,pending,wake,reader,accepted,completed,cleanup,ordinaryIrqs,bus,otherDone,fenced>>
Preempt == /\ phase = "Running" /\ ~reader
           /\ phase' = "Preempt" /\ ordinaryIrqs' = IF BadOrdinaryIrq THEN 1 ELSE 0
           /\ UNCHANGED <<pending,wake,reader,accepted,completed,cleanup,irq,bus,otherDone,fenced>>
Yield == /\ phase = "Preempt" /\ ~reader
         /\ phase' = "Yield" /\ fenced' = TRUE /\ reader' = TRUE
         /\ UNCHANGED <<pending,wake,accepted,completed,cleanup,irq,ordinaryIrqs,bus,otherDone>>
AcceptHandoff == /\ phase = "Yield" /\ bus = "Free"
                 /\ phase' = "HandoffRead" /\ accepted' = 2 /\ bus' = "Info"
                 /\ UNCHANGED <<pending,wake,reader,completed,cleanup,irq,ordinaryIrqs,otherDone,fenced>>
\* Independent coherence cleanup can finish while the shared AXI-Lite bus
\* is occupied by the read. No following write, IRQ, or callback is needed.
Cleanup == /\ phase = "HandoffRead" /\ ~cleanup
           /\ (~BadCleanupDependency \/ bus = "Free")
           /\ cleanup' = TRUE
           /\ UNCHANGED <<phase,pending,wake,reader,accepted,completed,irq,ordinaryIrqs,bus,otherDone,fenced>>
RespondHandoff == /\ phase = "HandoffRead" /\ cleanup
                  /\ phase' = "Done" /\ completed' = 2 /\ bus' = "Free"
                  /\ reader' = FALSE
                  /\ UNCHANGED <<pending,wake,accepted,cleanup,irq,ordinaryIrqs,otherDone,fenced>>
\* Other control traffic may wait behind the ordinary read, or take the bus
\* before it. This is deliberately a shared, single-outstanding control bus.
OtherAccept == /\ ~otherDone /\ bus = "Free"
               /\ bus' = "Other"
               /\ UNCHANGED <<phase,pending,wake,reader,accepted,completed,cleanup,irq,ordinaryIrqs,otherDone,fenced>>
OtherRespond == /\ bus = "Other" /\ bus' = "Free" /\ otherDone' = TRUE
                /\ UNCHANGED <<phase,pending,wake,reader,accepted,completed,cleanup,irq,ordinaryIrqs,fenced>>
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
          /\ ordinaryIrqs \in 0..1
          /\ bus \in {"Free","Info","Other"}
          /\ <<pending,wake,reader,cleanup,irq,otherDone,fenced>> \in
             BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN \X BOOLEAN
AuthorizedReader == reader => phase \in {"IdleReserved","IdleRead","Yield","HandoffRead"}
StickyNotification == phase \in {"IdleReserved","IdleRead"} => pending
NoOrdinaryIrq == ordinaryIrqs = 0
NoReturn == phase \in {"Yield","HandoffRead","Done"} => fenced
Readiness == completed = 2 => cleanup
Completes == <>(phase = "Done")
ControlProgress == <>otherDone
\* Negative reachability check: an old kick can wake dispatch while the
\* ordinary reader owns the bus and awaits independent cleanup.
NoDelayedKickDuringHandoff == ~(phase = "HandoffRead" /\ irq /\ wake)
=============================================================================
