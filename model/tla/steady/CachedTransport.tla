---------------------------- MODULE CachedTransport ----------------------------
EXTENDS Naturals, Sequences, FiniteSets
CONSTANTS Clients, Addresses, Colors, ColorOf, Directions, Stalled, Control,
          Dependencies, AllowedDirections, Queues, QueueOf, QueueDepth, Pools, PoolOf, Slots,
          SharedAck, Slices, SliceOf, ReadSlots,
          Lines, Sets, SetOf, Ways, EvictionQueues, EvictionQueueOf, EvictionDepth
VARIABLES reads,invQ,held,phase,parity,direction,remaining,completed,
          resident,directory,dirty,evictions,retiring
P == INSTANCE ProgressSystem
R == INSTANCE RetainedCache
transport == <<reads,invQ,held,phase,parity,direction,remaining,completed>>
cache == <<resident,directory,dirty,evictions,retiring>>
vars == <<transport,cache>>
\* Composition adapter, not a scheduler or another DCU implementation. Each action
\* frames all state in vars. Callers add their own control-state frame, admission
\* guards, initial cache history and fairness over the resulting guarded actions.
\* No Spec here: granting fairness before adding a harness guard can assume away
\* the exact shared-resource obstruction that the harness is meant to detect.
Pinned == {P!C!Door(c): c \in held} \cup
          UNION {{invQ[q][i].address: i \in 1..Len(invQ[q])}: q \in Queues}
Need(c) == P!C!Door(c)
Prepare(c, replacement) ==
    /\ phase[c] = "Submit" /\ Need(c) \notin resident /\ Need(c) \notin Pinned
    /\ (R!Fill(Need(c)) \/ (replacement /\ \E a \in resident \ Pinned:
           SetOf[a] = SetOf[Need(c)] /\ R!Evict(a)))
    /\ UNCHANGED transport
Evict(a) == /\ a \notin Pinned /\ R!Evict(a) /\ UNCHANGED transport
Deliver(q) == /\ R!Deliver(q) /\ UNCHANGED transport
Admit(c) == /\ Need(c) \in resident /\ P!Admit(c) /\ UNCHANGED cache
Work(c) == /\ P!Work(c) /\ UNCHANGED cache
Inv(c) == /\ P!Inv(c) /\ UNCHANGED cache
Ack(c) == /\ P!Ack(c)
          /\ R!ReleaseInvalidation(P!C!Target(c))
Return(c,d) == /\ P!Return(c,d) /\ UNCHANGED cache
Process(q) == /\ P!Process(q) /\ R!Invalidate(Head(invQ[q]).address)
Safety == P!TypeOK /\ P!Tracking /\ P!Conservation /\ R!TypeOK /\ R!Conservation
=============================================================================
