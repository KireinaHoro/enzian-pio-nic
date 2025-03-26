---- MODULE MC ----
EXTENDS lauberhorn, TLC

\* MV CONSTANT declarations@modelParameterConstants
CONSTANTS
S1
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
const_17428889987671585000 == 
{S1}
----

\* MV CONSTANT definitions Cores
const_17428889987671586000 == 
{C0}
----

\* MV CONSTANT definitions CLs
const_17428889987671587000 == 
{CL0, CL1, CL2, CL3, CL4}
----

\* CONSTANT definitions @modelParameterConstants:1CLValues
const_17428889987671588000 == 
Services \cup {"None"}
----

\* CONSTANT definitions @modelParameterConstants:4CLSize
const_17428889987671589000 == 
1
----

\* CONSTANT definitions @modelParameterConstants:5CoreCLAssignment
const_17428889987671590000 == 

C0 :> [
	send |-> 0 :> CL0 @@ 1 :> CL1,
	recv |-> 0 :> CL2 @@ 1 :> CL3,
	control |-> CL4
	]
----

\* CONSTANT definitions @modelParameterConstants:6CLCoreAssignment
const_17428889987671591000 == 

CL0 :> C0 @@
CL1 :> C0 @@
CL2 :> C0 @@
CL3 :> C0 @@
CL4 :> C0
----

\* CONSTANT definitions @modelParameterConstants:7CLWorkerAssignment
const_17428889987671592000 == 

CL0 :> "send" @@
CL1 :> "send" @@
CL2 :> "recv" @@
CL3 :> "recv" @@
CL4 :> "control"
----

\* CONSTANT definition @modelParameterDefinitions:0
def_ov_17428889987671593000 ==
[0..CLSize-1 -> Services \cup {"None"}]
----
\* CONSTANT definition @modelParameterDefinitions:1
def_ov_17428889987671594000 ==
[i \in 0..CLSize-1 |-> "None"]
----
\* CONSTANT definition @modelParameterDefinitions:2
def_ov_17428889987671595000 ==
Services \cup {"None"}
----
\* CONSTANT definition @modelParameterDefinitions:3
def_ov_17428889987671596000(s) ==
{[i \in 0..CLSize-1 |-> s ]}
----
\* CONSTANT definition @modelParameterDefinitions:4
def_ov_17428889987671597000(c) ==
{[i \in 0..CLSize-1 |-> coreServiceAssignment[c]]}
----
\* SPECIFICATION definition @modelBehaviorSpec:0
spec_17428889987671598000 ==
/\ LauberhornInit
/\ [][LauberhornStep]_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers,coreServiceAssignment>>
/\ \A c \in Cores: SF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2InitLocalReq(c))
/\ \A c \in Cores: WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2InitRemoteReq(c))
/\ \A c \in Cores: WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2FinishBarrier(c))
/\ SF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2HandleControlInv)
/\ SF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2HandleDataInv)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2HandleUpgradeAck)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWHandleOp)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWFinishOP)
/\ SF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWGetControl)
/\ SF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWGetInterrupt)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWCheckControl)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWConsumeAcks)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWBarrierDone) 
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWCompleteTXPRefetch)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWSignal)
/\ SF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWResume)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAHandleCurrentCLMsg)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAHandleControlReq)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAInitHandlingNextCL)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGARecvDowngrades)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAFinishInvalidations)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAHandleNextReq)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAStartPreempt)
/\ SF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAGetControl)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAAwaitWorkers)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAInitFlushData)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAFinishFlush)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAHandlerFinishPreempt)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAGetControl)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAFinishPreempt)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAAwaitWorkers)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAHandleControlReq)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAFinishPreempt)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2RequiredDowngrade)
/\ WF_<<coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAAwaitInv)
----
\* PROPERTY definition @modelCorrectnessProperties:0
prop_17428889987671603000 ==
/\ \A c \in Cores: []<> (fpgaHandlers[c].send.state = "IDLE")
----
\* PROPERTY definition @modelCorrectnessProperties:1
prop_17428889987671604000 ==
/\ \A c \in Cores: []<> (fpgaHandlers[c].recv.state = "IDLE")
----
\* PROPERTY definition @modelCorrectnessProperties:2
prop_17428889987671605000 ==
/\ \A c \in Cores: []<> (fpgaHandlers[c].control.state = "IDLE")
----
=============================================================================
\* Modification History
\* Created Tue Mar 25 08:49:58 CET 2025 by jasmin
