---------------------------- MODULE TwoFClient ----------------------------
EXTENDS Naturals, Sequences
CONSTANTS Clients, Stalled, Directions, AllowedDirections, Control, Dependencies
VARIABLES phase, parity, direction, remaining, completed
vars == <<phase, parity, direction, remaining, completed>>
Door(c) == Control[c][direction[c]][parity[c][direction[c]]]
Init == /\ phase = [c \in Clients |-> "Submit"]
        /\ parity \in [Clients -> [Directions -> 0..1]]
        /\ direction \in [Clients -> Directions]
        /\ \A c \in Clients: direction[c] \in AllowedDirections[c]
        /\ \A c \in Clients: \A d \in Directions \ AllowedDirections[c]: parity[c][d] = 0
        /\ remaining = [c \in Clients |-> 0]
        /\ completed = [c \in Clients |-> FALSE]
TypeOK == /\ phase \in [Clients -> {"Submit", "Held", "NeedInv", "WaitAck", "Return"}]
          /\ parity \in [Clients -> [Directions -> 0..1]]
          /\ direction \in [Clients -> Directions]
          /\ \A c \in Clients: direction[c] \in AllowedDirections[c]
          /\ \A c \in Clients: \A d \in Directions \ AllowedDirections[c]: parity[c][d] = 0
          /\ remaining \in [Clients -> Nat]
          /\ completed \in [Clients -> BOOLEAN]
Accepted(c) == /\ phase[c] = "Submit"
               /\ phase' = [phase EXCEPT ![c] = "Held"]
               /\ UNCHANGED <<parity, direction, remaining, completed>>
Work(c) == /\ phase[c] = "Held" /\ c \notin Stalled
           /\ phase' = [phase EXCEPT ![c] = "NeedInv"]
           /\ remaining' = [remaining EXCEPT ![c] = Len(Dependencies[c][direction[c]][parity[c][direction[c]]])]
           /\ UNCHANGED <<parity, direction, completed>>
Target(c) == Dependencies[c][direction[c]][parity[c][direction[c]]][remaining[c]]
SubmittedInv(c) == /\ phase[c] = "NeedInv" /\ remaining[c] > 0
                   /\ phase' = [phase EXCEPT ![c] = "WaitAck"]
                   /\ UNCHANGED <<parity, direction, remaining, completed>>
InvalidationCompleted(c) == /\ phase[c] = "WaitAck"
    /\ phase' = [phase EXCEPT ![c] = IF remaining[c] = 1 THEN "Return" ELSE "NeedInv"]
    /\ remaining' = [remaining EXCEPT ![c] = @ - 1]
    /\ UNCHANGED <<parity,direction,completed>>
Returned(c, d) == /\ phase[c] = "Return"
                  /\ d \in AllowedDirections[c]
                  /\ phase' = [phase EXCEPT ![c] = "Submit"]
                  /\ parity' = [parity EXCEPT ![c][direction[c]] = 1 - @]
                  /\ direction' = [direction EXCEPT ![c] = d]
                  /\ completed' = [completed EXCEPT ![c] = ~@]
                  /\ UNCHANGED remaining
=============================================================================
