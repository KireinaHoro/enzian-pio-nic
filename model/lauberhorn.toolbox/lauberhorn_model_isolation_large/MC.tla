---- MODULE MC ----
EXTENDS lauberhorn, TLC

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
S1, S2
----

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
C0
----

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
CL0, CL1, CL2, CL3, CL4
----

\* MV CONSTANT definitions Services
const_17429040949511642000 == 
{S1, S2}
----

\* MV CONSTANT definitions Cores
const_17429040949511643000 == 
{C0}
----

\* MV CONSTANT definitions CLs
const_17429040949511644000 == 
{CL0, CL1, CL2, CL3, CL4}
----

\* SYMMETRY definition
symm_17429040949511645000 == 
Permutations(const_17429040949511642000)
----

\* CONSTANT definitions @modelParameterConstants:1CLValues
const_17429040949511646000 == 
Services \cup {"None"}
----

\* CONSTANT definitions @modelParameterConstants:4CLSize
const_17429040949511647000 == 
1
----

\* CONSTANT definitions @modelParameterConstants:5CoreCLAssignment
const_17429040949511648000 == 

C0 :> [
	send |-> 0 :> CL0 @@ 1 :> CL1,
	recv |-> 0 :> CL2 @@ 1 :> CL3,
	control |-> CL4
	]
----

\* CONSTANT definitions @modelParameterConstants:6CLCoreAssignment
const_17429040949511649000 == 

CL0 :> C0 @@
CL1 :> C0 @@
CL2 :> C0 @@
CL3 :> C0 @@
CL4 :> C0
----

\* CONSTANT definitions @modelParameterConstants:7CLWorkerAssignment
const_17429040949511650000 == 

CL0 :> "send" @@
CL1 :> "send" @@
CL2 :> "recv" @@
CL3 :> "recv" @@
CL4 :> "control"
----

\* CONSTANT definition @modelParameterDefinitions:0
def_ov_17429040949511651000 ==
[0..CLSize-1 -> Services \cup {"None"}]
----
\* CONSTANT definition @modelParameterDefinitions:1
def_ov_17429040949511652000 ==
[i \in 0..CLSize-1 |-> "None"]
----
\* CONSTANT definition @modelParameterDefinitions:2
def_ov_17429040949511653000 ==
Services \cup {"None"}
----
\* CONSTANT definition @modelParameterDefinitions:3
def_ov_17429040949511654000(s) ==
{[i \in 0..CLSize-1 |-> s ]}
----
\* CONSTANT definition @modelParameterDefinitions:4
def_ov_17429040949511655000(c) ==
{[i \in 0..CLSize-1 |-> coreServiceAssignment[c]]}
----
=============================================================================
\* Modification History
\* Created Tue Mar 25 13:01:34 CET 2025 by jasmin
