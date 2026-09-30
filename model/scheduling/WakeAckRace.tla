----------------------------- MODULE WakeAckRace -----------------------------
EXTENDS Naturals, Sequences
CONSTANT BadClear
\* Two notifications of one cookie. Counters are ghost generations, not a proposed ABI.
VARIABLES seen, accepted, serviced, outstanding, command, wakePending
v == <<seen,accepted,serviced,outstanding,command,wakePending>>
Init == /\ seen = 0 /\ accepted = 0 /\ serviced = 0 /\ outstanding = 0
        /\ command = <<>> /\ wakePending = FALSE
Notify == /\ seen < 2 /\ seen' = seen+1 /\ wakePending' = TRUE
          /\ UNCHANGED <<accepted,serviced,outstanding,command>>
Publish == /\ wakePending /\ outstanding = 0
           /\ outstanding' = seen
           /\ command' = <<[type |-> "Wake", cookie |-> 7, generation |-> seen, state |-> "Submitted"]>>
           /\ UNCHANGED <<seen,accepted,serviced,wakePending>>
Accept == /\ Len(command) = 1 /\ command[1].state = "Submitted"
          /\ accepted' = command[1].generation
          /\ command' = [command EXCEPT ![1].state = "Accepted"]
          /\ UNCHANGED <<seen,serviced,outstanding,wakePending>>
\* A callback on another CPU can run after Publish/Accept but before this ACK.
Ack == /\ Len(command) = 1 /\ command[1].state = "Accepted"
       /\ wakePending' = IF BadClear THEN FALSE ELSE seen > command[1].generation
       /\ outstanding' = 0 /\ command' = <<>>
       /\ UNCHANGED <<seen,accepted,serviced>>
Service == /\ serviced < accepted /\ serviced' = accepted
           /\ UNCHANGED <<seen,accepted,outstanding,command,wakePending>>
Next == Notify \/ Publish \/ Accept \/ Ack \/ Service
Spec == Init /\ [][Next]_v /\ WF_v(Publish) /\ WF_v(Accept) /\ WF_v(Ack) /\ WF_v(Service)
TypeOK == /\ seen \in 0..2 /\ accepted \in 0..seen /\ serviced \in 0..accepted
          /\ outstanding \in 0..seen /\ wakePending \in BOOLEAN
          /\ command \in Seq([type : {"Wake"}, cookie : {7}, generation : 1..2,
                               state : {"Submitted","Accepted"}]) /\ Len(command) <= 1
          /\ (outstanding = 0) = (command = <<>>)
NoLostNotification == seen > accepted => wakePending
WakeProgress == \A n \in 1..2: (seen >= n) ~> (serviced >= n)
=============================================================================
