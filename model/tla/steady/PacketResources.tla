--------------------------- MODULE PacketResources ---------------------------
EXTENDS Naturals
CONSTANTS Buffers, WriteLines, BufferOf
VARIABLES buffer, writes
vars == <<buffer,writes>>
Init == /\ buffer = [b \in Buffers |-> "Owned"]
        /\ writes = [a \in WriteLines |-> 0]
\* Two finite write-service stages: data completion, then response acceptance.
\* This abstracts a whole admitted coherence writeback, not CPU instruction work.
BeginWrite(a) == /\ writes[a] = 0 /\ buffer[BufferOf[a]] = "Owned"
                 /\ writes' = [writes EXCEPT ![a] = 2] /\ UNCHANGED buffer
ServiceWrite(a) == /\ writes[a] > 0
                   /\ writes' = [writes EXCEPT ![a] = @-1] /\ UNCHANGED buffer
WritesDone(b) == \A a \in WriteLines: BufferOf[a] = b => writes[a] = 0
Release(b,send) == /\ buffer[b] = "Owned" /\ WritesDone(b)
    /\ buffer' = [buffer EXCEPT ![b] = IF send THEN "Output" ELSE "Returned"]
    /\ UNCHANGED writes
Deliver(b) == /\ buffer[b] = "Output"
              /\ buffer' = [buffer EXCEPT ![b] = "Sent"] /\ UNCHANGED writes
Done(b) == buffer[b] \in {"Returned","Sent"}
TypeOK == /\ buffer \in [Buffers -> {"Owned","Output","Returned","Sent"}]
          /\ writes \in [WriteLines -> 0..2]
NoUseAfterRelease == \A a \in WriteLines:
    writes[a] > 0 => buffer[BufferOf[a]] = "Owned"
Safety == TypeOK /\ NoUseAfterRelease
=============================================================================
