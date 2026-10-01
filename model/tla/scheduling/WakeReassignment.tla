--------------------------- MODULE WakeReassignment ---------------------------
EXTENDS Integers, Sequences
CONSTANTS BadDrop, BadDelivery
\* One physical worker moves A -> B -> A. Epoch is assignment identity, not domain.
VARIABLES epoch, stage, issued, command, pending, target, runtime, serviced, deliveredDomain
v == <<epoch,stage,issued,command,pending,target,runtime,serviced,deliveredDomain>>
KernelOwns == Len(command) = 1 /\ command[1].state = "Submitted"
Owner == IF stage = "Parked" THEN -1 ELSE IF epoch = 1 THEN 1 ELSE 0
Init == /\ epoch = 0 /\ stage = "Run" /\ issued = FALSE /\ command = <<>>
        /\ pending = FALSE /\ target = -1 /\ runtime = FALSE /\ serviced = FALSE
        /\ deliveredDomain = -1
\* Callback records domain/cookie plus a routing hint; acceptance may be delayed.
Notify == /\ ~issued /\ issued' = TRUE
          /\ command' = <<[type |-> "Wake", domain |-> 0, cookie |-> 7,
                            hintEpoch |-> epoch, state |-> "Submitted"]>>
          /\ UNCHANGED <<epoch,stage,pending,target,runtime,serviced,deliveredDomain>>
Accept == /\ Len(command) = 1 /\ command[1].state = "Submitted"
          /\ command' = [command EXCEPT ![1].state = "Accepted"]
          /\ pending' = IF BadDrop THEN Owner = command[1].domain /\ stage = "Run" ELSE TRUE
          /\ target' = IF Owner = command[1].domain /\ stage = "Run" THEN epoch ELSE -1
          /\ UNCHANGED <<epoch,stage,issued,runtime,serviced,deliveredDomain>>
\* Acceptance and its kernel acknowledgment are distinct boundaries.
Ack == /\ Len(command) = 1 /\ command[1].state = "Accepted" /\ command' = <<>>
       /\ UNCHANGED <<epoch,stage,issued,pending,target,runtime,serviced,deliveredDomain>>
Begin == /\ epoch < 2 /\ stage = "Run" /\ stage' = "Draining"
         /\ UNCHANGED <<epoch,issued,command,pending,target,runtime,serviced,deliveredDomain>>
\* The proven handoff contract supplies drain/retirement; host work must also finish.
Park == /\ stage = "Draining" /\ ~runtime /\ stage' = "Parked"
        /\ UNCHANGED <<epoch,issued,command,pending,target,runtime,serviced,deliveredDomain>>
Install == /\ stage = "Parked" /\ epoch' = epoch+1 /\ stage' = "Installed"
           /\ UNCHANGED <<issued,command,pending,target,runtime,serviced,deliveredDomain>>
Activate == /\ stage = "Installed" /\ stage' = "Run"
            /\ UNCHANGED <<epoch,issued,command,pending,target,runtime,serviced,deliveredDomain>>
Resolve == /\ pending /\ Owner = 0 /\ stage = "Run" /\ target # epoch
           /\ target' = epoch
           /\ UNCHANGED <<epoch,stage,issued,command,pending,runtime,serviced,deliveredDomain>>
Deliver == /\ pending /\ stage = "Run" /\ target >= 0
           /\ (BadDelivery \/ (Owner = 0 /\ target = epoch))
           /\ pending' = FALSE /\ runtime' = TRUE /\ deliveredDomain' = Owner
           /\ UNCHANGED <<epoch,stage,issued,command,target,serviced>>
Service == /\ runtime /\ Owner = deliveredDomain /\ runtime' = FALSE /\ serviced' = TRUE
           /\ UNCHANGED <<epoch,stage,issued,command,pending,target,deliveredDomain>>
\* Lifecycle adapter ports, guarded by the containing scheduling composition.
Capture == /\ stage = "Installed" /\ Owner = 0 /\ pending
           /\ pending' = FALSE /\ runtime' = TRUE /\ deliveredDomain' = 0
           /\ UNCHANGED <<epoch,stage,issued,command,target,serviced>>
Dispose == /\ issued /\ ~serviced /\ ~KernelOwns
           /\ pending' = FALSE /\ runtime' = FALSE /\ serviced' = TRUE
           /\ UNCHANGED <<epoch,stage,issued,command,target,deliveredDomain>>
ResetCommand == /\ serviced /\ command = <<>>
    /\ issued' = FALSE /\ pending' = FALSE /\ runtime' = FALSE
    /\ serviced' = FALSE /\ target' = -1 /\ deliveredDomain' = -1
    /\ UNCHANGED <<epoch,stage,command>>
SetStage(nextStage) == /\ stage' = nextStage
    /\ UNCHANGED <<epoch,issued,command,pending,target,runtime,serviced,deliveredDomain>>
Next == Notify \/ Accept \/ Ack \/ Begin \/ Park \/ Install \/ Activate \/ Resolve \/ Deliver \/ Service
Spec == Init /\ [][Next]_v
        /\ WF_v(Accept) /\ WF_v(Ack) /\ WF_v(Begin) /\ WF_v(Park) /\ WF_v(Install)
        /\ WF_v(Activate) /\ WF_v(Resolve) /\ WF_v(Deliver) /\ WF_v(Service)
TypeOK == /\ epoch \in 0..2 /\ stage \in {"Run","Draining","Parked","Installed"}
          /\ issued \in BOOLEAN /\ pending \in BOOLEAN /\ runtime \in BOOLEAN /\ serviced \in BOOLEAN
          /\ target \in -1..2 /\ deliveredDomain \in -1..1
          /\ command \in Seq([type : {"Wake"}, domain : {0}, cookie : {7},
                               hintEpoch : 0..2, state : {"Submitted","Accepted"}])
          /\ Len(command) <= 1
Responsibility == /\ issued = (KernelOwns \/ pending \/ runtime \/ serviced)
                  /\ ~(KernelOwns /\ (pending \/ runtime \/ serviced))
                  /\ ~(pending /\ (runtime \/ serviced)) /\ ~(runtime /\ serviced)
RightSubscriber == deliveredDomain \in {-1,0}
WakeProgress == issued ~> serviced
Acknowledged == issued ~> (command = <<>> /\ ~KernelOwns)
=============================================================================
