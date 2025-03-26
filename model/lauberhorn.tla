----------------------------- MODULE lauberhorn -----------------------------
EXTENDS Sequences, Naturals, FiniteSets, Bags

CONSTANTS Cores, Services, CLs, CLSize, CLValues, CoreCLAssignment, CLCoreAssignment, CLWorkerAssignment

VARIABLES  thxCache, serviceState, eciLink, cacheQueues, fpgaHandlers, coreServiceAssignment\*, eciLink, interrupt, fpgaControl, fpgaData, dataSent, dataReceived

\**********************************
\* CONSTANT DEFINITIONS (IN TLC)
\* (Both as Ordinary Assinments)
\*
\* Cores <- {"C1"} 
\* Services <- {"S1", "S2"}
\* CLs <- {"CL0", "CL1"}
\* ClPairs <- {[0 |-> "CL0", 1 | -> "CL1"]}
\* CLSize <- 2
\* CLValues <- 0..1
\**********************************



\**********************************
\* BEHAVIOUR SPEC USED IN TLC
\*
(*
/\ LauberhornInit
/\ [][LauberhornStep]_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>
/\ \A c \in Cores: SF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2InitLocalReq(c))
/\ \A c \in Cores: WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2InitRemoteReq(c))
/\ \A c \in Cores: WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2FinishBarrier(c))
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(L2HandleLinkMsg)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWHandleOp)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWFinishOP)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWConsumeAcks)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWBarrierDone)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(SWSignal)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAHandleCurrentCLMsg)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAInitHandlingNextCL)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGARecvDowngrades)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAFinishInvalidations)
/\ WF_<<thxCache,serviceState,eciLink,cacheQueues,fpgaHandlers>>(FPGAHandleNextReq)
*)

\**********************************


\**********************************
\* CHECKED INVARIANTS (IN TLC)
\*
\* dataIntegrity
\* CLExclusion
\**********************************

\**********************************
\* CHECKED LIVENESS PROPERTY (IN TLC)
\*
\* (*Every Handler keeps visiting the IDLE state and message queues drain.*)                         
\* LauberhornLiveness ==
\*    /\ \A s \in Services: ([]<> serviceState[s].state = "IDLE")
\*    /\ \A c \in Cores: ([]<> fpgaHandlers[c].send.state = "IDLE")
\*    /\ \A c \in Cores: ([]<> fpgaHandlers[c].recv.state = "IDLE")
\**********************************





\**********************************
\* TYPE DEFINITIONS
\**********************************


Range(f) == {f[x]: x \in DOMAIN f}


RECURSIVE SeqFromSet(_)
SeqFromSet(S) == 
  IF S = {} THEN << >> 
  ELSE LET x == CHOOSE x \in S : TRUE
       IN  << x >> \o SeqFromSet(S \ {x})


CacheLine == [0..CLSize-1 -> 0..CLValues-1]

SubCacheLine == 0..CLValues-1

ControlCLData == [
    READY: BOOLEAN,
    BUSY: BOOLEAN,
    currentSendCL: 0..1,
    currentRecvCL: 0..1
]


CacheLineMask == [0..CLSize-1 -> BOOLEAN]

ServiceState == {
    "IDLE",
    "PREEMPTED",
    "GET_CONTROL",
    "CHECK_CONTROL",
    "OP",
    "BARRIER",
    "SIGNAL",
    "COMPLETE_PREFETCH"
}


CacheOperation == {
    "UPGRADE",
    "NONE"
}



PrefetchReq == [
    type: {"PrefetchReq"},
    cl: CLs
]


ReadReq == [
    type: {"ReadReq"},
    cl: CLs,
    index: 0..CLSize-1
]

SetBusyCAS == [
    type: {"SetBusyCAS"},
    cl: CLs
]

SetBusyCASAck == [
    type: {"SetBusyCASAck"},
    cl: CLs,
    success: BOOLEAN,
    control: ControlCLData
]

UnsetBusyCAS == [
    type: {"UnsetBusyCAS"},
    cl: CLs,
    control: ControlCLData
]



ReadAck == [
    type: {"ReadAck"},
    cl: CLs,
    index: 0..CLSize-1,
    value:  SubCacheLine,
    control: ControlCLData
]

WriteReq == [
    type: {"WriteReq"},
    cl: CLs,
    index: 0..CLSize-1,
    value: SubCacheLine,
    control: ControlCLData
]

UpgradeReq == [
    type: {"UpgradeReq"},
    cl: CLs
]

UpgradeAck == [
    type: {"UpgradeAck"},
    cl: CLs,
    value: CacheLine,
    control: ControlCLData
]

DowngradeReq == [
    type: {"Downgrade"},
    cl: CLs,
    value: CacheLine,
    control: ControlCLData
]

InvReq == [
    type: {"InvReq"},
    cl: CLs
]

InvAck == [
    type: {"InvAck"},
    cl: CLs,
    was_valid : BOOLEAN,
    value: CacheLine,
    control: ControlCLData
]

Interrupt == [
    type: {"Interrupt"},
    core: Cores,
    service: Services
]

InterruptAck == [
    type: {"InterruptAck"},
    core: Cores
]


CoreToCacheMsgs == ReadReq \cup WriteReq \cup PrefetchReq \cup SetBusyCAS \cup UnsetBusyCAS
CacheToCoreMsgs == ReadAck \cup SetBusyCASAck
CacheToFPGAMsgs == UpgradeReq \cup InvAck \cup DowngradeReq \cup InterruptAck
FPGAToCacheMsgs == UpgradeAck \cup InvReq \cup Interrupt


Link == [
    toFPGA: SUBSET CacheToFPGAMsgs,
    toCPU: SUBSET FPGAToCacheMsgs
]

CacheState == [
        CLs -> [valid: BOOLEAN,
                value: CacheLine,
                control: ControlCLData,
                optype: CacheOperation,
                inv: BOOLEAN
               ]
]


CLsInProgress == [
    CLs -> [
            idle: BOOLEAN,
            type: CacheOperation 
            ]
]

CacheQueues == [
    Cores -> [
                barrier: BOOLEAN,
                reqs: SUBSET CoreToCacheMsgs,
                resps: Seq(CacheToCoreMsgs)
              ]

]


         
         
SWHandler == [
    state: ServiceState,
    preempted: BOOLEAN,
    core: Cores,
    op: {"TX", "RX", "NONE"},
    sendCLs: [0..1 -> CLs],
    recvCLs: [0..1 -> CLs],
    (*currentSendCL: 0..1,
    currentRecvCL: 0..1,*)
    controlCL: CLs,
    control: ControlCLData,
    data: CacheLine,
    dataMask: CacheLineMask
]


FPGAStates == {
    "IDLE",
    "AWAIT_ACK",
    "HANDLE",
    "AWAIT_FLUSH",
    "FINISH_PREEMPT"
}

PreemptStates == {
    "IDLE",
    "START_PREEMPT",
    "GET_CONTROL",
    "AWAIT_WORKERS",
    "AWAIT_IPI_ACK",
    "AWAIT_CONTROL_INV"
}

FPGAHandler == [
    state: FPGAStates,
    CLPair: [0..1 -> CLs],
    currentCL: 0..1,
    data: CacheLine,
    inCache: BOOLEAN,
    pendingAck: BOOLEAN,
    op: {"TX", "RX", "None"}
]

FPGAPreemptHandler == [
    state: PreemptStates,
    controlCL: CLs,
    inCache: BOOLEAN,
    pendingAck: BOOLEAN,
    control: ControlCLData,
    sendPreempt: BOOLEAN,
    recvPreempt: BOOLEAN
]


FPGAWorker == [
    send: FPGAHandler,
    recv: FPGAHandler,
    control: FPGAPreemptHandler
]




\**********************************
\* HELPER OPERATORS
\**********************************
NoneElem == "None"
IsInjective(f) ==  \A a,b \in DOMAIN f : f[a] = f[b] => a = b \/ f[a] = NoneElem

InjectiveFuncsWithNone(A, B) == {F \in [A -> B \cup {NoneElem}] : IsInjective(F)}


SeqIndex(seq, elem) == LET i == CHOOSE i \in 1..Len(seq): seq[i] = elem IN i

\**********************************
\* INITIALIZATION
\**********************************

EMPTY_CL == [i \in 0..CLSize-1 |-> 0]


DEFAULT_CONTROL == [
    READY |-> TRUE,
    BUSY |-> FALSE,
    currentSendCL |-> 0,
    currentRecvCL |-> 0
]

NON_EMPTY_CLs == CacheLine \ {EMPTY_CL}



EMPTY_MASK == [i \in 0..CLSize-1 |-> FALSE]

LauberhornInit ==  

    \E core \in Cores:
    \E coreService \in {f \in [Cores -> Services]: IsInjective(f)}:
    /\ thxCache = [
            cl \in CLs |->  [
                            valid |-> FALSE, 
                            value |-> EMPTY_CL,
                            control |-> DEFAULT_CONTROL, 
                            optype |-> "NONE",
                            inv |-> FALSE]
        ]
    /\ 
        serviceState = [ 
          s \in Services |-> [\* only one serive for now
            state |-> "IDLE",
            preempted |-> coreService[core] # s,
            core |-> core,
            sendCLs |-> CoreCLAssignment[core].send,
            recvCLs |-> CoreCLAssignment[core].recv,
            controlCL |-> CoreCLAssignment[core].control,
            control |-> DEFAULT_CONTROL,
            (*currentSendCL |-> 0,
            currentRecvCL |-> 0,*)
            op |-> "NONE",
            data |-> EMPTY_CL,
            dataMask |-> EMPTY_MASK
         ]
       ]
    /\ fpgaHandlers = [
        c \in Cores |->
            [
                send |-> [
                    state |-> "IDLE",
                    CLPair |-> CoreCLAssignment[c].send,
                    currentCL |-> 0,
                    data |-> EMPTY_CL,
                    inCache |-> FALSE,
                    pendingAck |-> FALSE,
                    op |-> "TX"
                   ],
               recv |-> [
                        state |-> "IDLE",
                        CLPair |-> CoreCLAssignment[c].recv,
                        currentCL |-> 0,
                        data |-> [i \in 0..CLSize-1 |-> coreService[c]],
                        inCache |-> FALSE,
                        pendingAck |-> FALSE,
                        op |-> "RX" 
                    ],
               control |-> [
                        state |-> "IDLE",
                        controlCL |-> CoreCLAssignment[c].control,
                        inCache|-> FALSE,
                        pendingAck |-> FALSE,
                        sendPreempt |-> FALSE,
                        recvPreempt |-> FALSE,
                        control |-> DEFAULT_CONTROL
               ]
            ]    
        ]
    
    
    (*fpgaHandlers = [
            send |-> [
                    state |-> "IDLE",
                    CLPair |-> [i \in 0..1 |-> cls[i+1]],
                    currentCL |-> 0,
                    data |-> EMPTY_CL,
                    inCache |-> FALSE,
                    pendingAck |-> FALSE,
                    op |-> "TX"
                  ],
            recv |-> [
                        state |-> "IDLE",
                        CLPair |-> [i \in 0..1 |-> cls[i+3]],
                        currentCL |-> 0,
                        data |-> EMPTY_CL,
                        inCache |-> FALSE,
                        pendingAck |-> FALSE,
                        op |-> "RX" 
                    ],
            clToFPGAHandler |-> [cl \in CLs |-> IF SeqIndex(cls, cl) <= 2 THEN "send" ELSE "recv"]
       ]*)
    /\ eciLink = [toCPU |-> {}, toFPGA |-> {}]
    /\ cacheQueues = [c \in Cores |-> [barrier |-> FALSE, reqs |-> {}, resps |-> <<>>]]
    /\ coreServiceAssignment = coreService

                    
    

\**********************************
\* MESSAGE HELPER OPERATORS
\**********************************

SendToFPGA(msg) == eciLink' = 
                            [eciLink EXCEPT 
                                !.toFPGA = eciLink.toFPGA \cup {msg}]

SendToCPU(msg) == eciLink' = 
                            [eciLink EXCEPT 
                                !.toCPU = eciLink.toCPU \cup {msg}]
                                

RecvFromCPU(msg) == eciLink' = [eciLink EXCEPT !.toCPU = eciLink.toCPU \ {msg}]
RecvFromFPGA(msg) == eciLink' = [eciLink EXCEPT !.toFPGA = eciLink.toFPGA \ {msg}]

ReceiveSendCPU(recv, send) == eciLink' = [
                                   toCPU |-> eciLink.toCPU \ {recv},
                                   toFPGA |-> eciLink.toFPGA \cup {send}
                                   ]

ReceiveSendFPGA(recv, send) == eciLink' = [
                                   toCPU |-> eciLink.toCPU \cup {send},
                                   toFPGA |-> eciLink.toFPGA \ {recv}
                                   ]


\**********************************
\* EXECUTION STEP DEFINITIONS
\**********************************



L2InitLocalReq(c) == \E req \in cacheQueues[c].reqs:
                    (* we aren't processing a request on the CL *)
                     /\ thxCache[req.cl].optype = "NONE"
                     (* the CL is valid so we don't need to perform any remote interaction *)
                     /\ thxCache[req.cl].valid 
                     /\(CASE req.type = "ReadReq" ->
                            /\ cacheQueues' = [
                                cacheQueues EXCEPT ![c] = [
                                                reqs |-> cacheQueues[c].reqs \ {req},
                                                barrier |-> cacheQueues[c].barrier,
                                                resps |->  Append(cacheQueues[c].resps,
                                                        [type |-> "ReadAck",
                                                         cl |-> req.cl,
                                                         index |-> req.index,
                                                         value |-> thxCache[req.cl].value[req.index],
                                                         control |-> thxCache[req.cl].control
                                                        ])
                                               ]
                               ]
                           /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink, serviceState>>)
                        [] req.type = "WriteReq" ->
                              /\ cacheQueues' = [cacheQueues EXCEPT ![c].reqs = cacheQueues[c].reqs \ {req}]
                              /\ thxCache' = 
                                  [thxCache EXCEPT ![req.cl] = 
                                     [thxCache[req.cl] EXCEPT 
                                        !.value = [thxCache[req.cl].value EXCEPT ![req.index] = req.value],
                                        !.control = req.control
                                     ]
                                  ]
                               /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,eciLink, serviceState>>)
                        [] req.type = "PrefetchReq" ->
                            /\ cacheQueues' = [cacheQueues EXCEPT ![c].reqs = cacheQueues[c].reqs \ {req}]
                            /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,eciLink, serviceState, thxCache>>)
                        [] req.type = "SetBusyCAS" ->
                            /\ cacheQueues' = [
                                        cacheQueues EXCEPT ![c] = [
                                                        reqs |-> cacheQueues[c].reqs \ {req},
                                                        barrier |-> cacheQueues[c].barrier,
                                                        resps |->  Append(cacheQueues[c].resps,
                                                                [type |-> "SetBusyCASAck",
                                                                 cl |-> req.cl,
                                                                 success |-> thxCache[req.cl].control.READY,
                                                                 control |-> [thxCache[req.cl].control EXCEPT 
                                                                                !.BUSY = thxCache[req.cl].control.READY
                                                                             ]
                                                                ])
                                                       ]
                                       ]
                           /\ thxCache' =
                                [thxCache EXCEPT ![req.cl] = 
                                     [thxCache[req.cl] EXCEPT 
                                        !.control.BUSY = thxCache[req.cl].control.READY
                                     ]
                                  ]
                           /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,eciLink, serviceState>>)
                          [] req.type = "UnsetBusyCAS" ->
                           /\ cacheQueues' = [cacheQueues EXCEPT ![c].reqs = cacheQueues[c].reqs \ {req}]
                           /\ thxCache' =
                                [thxCache EXCEPT ![req.cl].control = 
                                     [req.control EXCEPT 
                                        !.READY = thxCache[req.cl].control.READY,
                                        !.BUSY = FALSE
                                     ]
                                  ]
                          /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,eciLink, serviceState>>)
                        [] OTHER -> FALSE
                       )
                       
L2InitRemoteReq(c) == \E req \in cacheQueues[c].reqs:
                    (* we aren't processing a request on the CL *)
                     /\ thxCache[req.cl].optype = "NONE"
                     (* the CL is not valid so we need to initiate a remote req *)
                     /\ \lnot thxCache[req.cl].valid 
                     /\ thxCache' = [thxCache EXCEPT ![req.cl] =
                                           [thxCache[req.cl] EXCEPT !.optype = "UPGRADE"]
                                    ]
                     /\ SendToFPGA(
                            [
                                type |-> "UpgradeReq",
                                cl |-> req.cl
                           ])
                     /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, cacheQueues>>)
                       

(*L2InitCoreReq(c) == \E req \in cacheQueues[c].reqs:
                    (* we aren't processing a request on the CL *)
                     /\ thxCache[req.cl].optype = "NONE"
                     /\ (CASE thxCache[req.cl].valid ->
                            (CASE req.type = "ReadReq" ->
                                /\ cacheQueues' = [
                                    cacheQueues EXCEPT ![c] = [
                                                    reqs |-> cacheQueues[c].reqs \ {req},
                                                    barrier |-> cacheQueues[c].barrier,
                                                    resps |->  Append(cacheQueues[c].resps,
                                                            [type |-> "ReadAck",
                                                             cl |-> req.cl,
                                                             index |-> req.index,
                                                             value |-> thxCache[req.cl].value[req.index]
                                                            ])
                                                   ]
                                   ]
                               /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink, serviceState>>)
                            [] req.type = "WriteReq" ->
                                  /\ thxCache' = 
                                      [thxCache EXCEPT ![req.cl] = 
                                         [thxCache[req.cl] EXCEPT !.value = 
                                            [thxCache[req.cl].value EXCEPT ![req.index] = req.value]
                                         ]
                                      ]
                                   /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,cacheQueues, eciLink, serviceState>>)
                            [] req.type = "PrefetchReq" ->
                                /\ cacheQueues' = [cacheQueues EXCEPT ![c].reqs = cacheQueues[c].reqs \ {req}]
                                /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,eciLink, serviceState, thxCache>>)
                            [] OTHER -> FALSE
                           )
                       [] \lnot thxCache[req.cl].valid ->
                          (* initiate request *)
                         (*/\ cacheQueues' = [cacheQueues EXCEPT ![c] = [
                                                cacheQueues[c] EXCEPT 
                                                    !.reqs = cacheQueues[c].reqs - {req}
                                                ]
                                             ] *)
                         /\ thxCache' = [thxCache EXCEPT ![req.cl] =
                                           [thxCache[req.cl] EXCEPT !.optype = "UPGRADE"]
                                        ]
                         /\ SendToFPGA(
                                [
                                    type |-> "UpgradeReq",
                                    cl |-> req.cl
                               ])
                         /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, cacheQueues>>)
                        )*)
                        
L2FinishBarrier(c) == /\ cacheQueues[c].barrier
                      /\ cacheQueues[c].reqs = {}
                      /\ cacheQueues' = [cacheQueues EXCEPT ![c] = 
                                            [cacheQueues[c] EXCEPT !.barrier = FALSE]
                                        ]
                      /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, thxCache, eciLink>>)


(*L2HandleLinkMsg == \E msg \in eciLink.toCPU:
                       (CASE msg.type = "UpgradeAck" ->
                            /\ thxCache[msg.cl].optype = "UPGRADE"
                            /\ thxCache' = [thxCache EXCEPT ![msg.cl] =
                                                [valid |-> TRUE,
                                                 value |-> msg.value,
                                                 control |-> msg.control,
                                                 optype|-> "NONE",
                                                 inv |-> thxCache[msg.cl].inv]
                                           ]
                           /\ RecvFromCPU(msg)
                           /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState,cacheQueues>>)
                       [] msg.type = "InvReq" -> 
                            (* we need to answer to the INV REQ even with a pending upgrade *)
                            (* only used for control CLs *)
                            (*/\ thxCache[msg.cl].optype = "NONE"*)
                            /\ CLWorkerAssignment[msg.cl] # "control"
                            /\ thxCache' = [thxCache EXCEPT ![msg.cl] =
                                                [valid |-> FALSE,
                                                 value |-> EMPTY_CL,
                                                 control |-> DEFAULT_CONTROL,
                                                 optype |-> thxCache[msg.cl].optype,
                                                 inv |-> thxCache[msg.cl].optype = "UPGRADE"]
                                           ]
                           /\ ReceiveSendCPU(msg, [
                                type |-> "InvAck",
                                cl |-> msg.cl,
                                value |-> thxCache[msg.cl].value,
                                control |-> thxCache[msg.cl].control,
                                was_valid |-> thxCache[msg.cl].valid
                               ])
                           /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, cacheQueues>>)
                      [] OTHER -> FALSE *)
                                                             
L2HandleUpgradeAck == \E msg \in eciLink.toCPU:
                        /\ msg.type = "UpgradeAck" 
                        /\ thxCache[msg.cl].optype = "UPGRADE"
                        /\ thxCache' = [thxCache EXCEPT ![msg.cl] =
                                            [valid |-> TRUE,
                                             value |-> msg.value,
                                             control |-> msg.control,
                                             optype|-> "NONE",
                                             inv |-> thxCache[msg.cl].inv]
                                       ]
                       /\ RecvFromCPU(msg)
                       /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState,cacheQueues>>)
                     
L2HandleDataInv == \E msg \in eciLink.toCPU:
                    /\ msg.type = "InvReq" 
                    (* we need to answer to the INV REQ even with a pending upgrade *)
                    (* only used for control CLs *)
                    (*/\ thxCache[msg.cl].optype = "NONE"*)
                    /\ CLWorkerAssignment[msg.cl] # "control"
                    /\ thxCache' = [thxCache EXCEPT ![msg.cl] =
                                        [valid |-> FALSE,
                                         value |-> EMPTY_CL,
                                         control |-> DEFAULT_CONTROL,
                                         optype |-> thxCache[msg.cl].optype,
                                         inv |-> thxCache[msg.cl].optype = "UPGRADE"]
                                   ]
                   /\ ReceiveSendCPU(msg, [
                        type |-> "InvAck",
                        cl |-> msg.cl,
                        value |-> thxCache[msg.cl].value,
                        control |-> thxCache[msg.cl].control,
                        was_valid |-> thxCache[msg.cl].valid
                       ])
                    /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, cacheQueues>>)
    

L2HandleControlInv == \E msg \in eciLink.toCPU:
                /\ msg.type = "InvReq"
                /\ CLWorkerAssignment[msg.cl] = "control"
                (* we need to answer to the INV REQ even with a pending upgrade *)
                (* only used for control CLs *)
                (*/\ thxCache[msg.cl].optype = "NONE"*)
                /\ thxCache' = [thxCache EXCEPT ![msg.cl] =
                                    [valid |-> FALSE,
                                     value |-> EMPTY_CL,
                                     control |-> DEFAULT_CONTROL,
                                     optype |-> thxCache[msg.cl].optype,
                                     inv |-> thxCache[msg.cl].optype = "UPGRADE"]
                               ]
               /\ ReceiveSendCPU(msg, [
                    type |-> "InvAck",
                    cl |-> msg.cl,
                    value |-> thxCache[msg.cl].value,
                    control |-> thxCache[msg.cl].control,
                    was_valid |-> thxCache[msg.cl].valid
                   ])
               /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, cacheQueues>>)

(*L2HandleUpgradeAck == \E msg \in eciLink.toCPU:
                        /\ msg.type = "UpgradeAck"
                        /\ thxCache[msg.cl].optype = "UPGRADE"
                        /\ thxCache' = [thxCache EXCEPT ![msg.cl] =
                                            [valid |-> TRUE,
                                             value |-> msg.value,
                                             control |-> msg.control,
                                             optype|-> "NONE",
                                             inv |-> thxCache[msg.cl].inv]
                                       ]
                       /\ RecvFromCPU(msg)
                       /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState,cacheQueues>>)
*)                      
                     
L2VoluntaryDowngrade == \E cl \in CLs:
                /\ thxCache[cl].valid 
                /\ thxCache[cl].optype = "NONE"
                /\ thxCache' = [thxCache EXCEPT ![cl] = 
                                    [valid |-> FALSE,
                                     value |-> EMPTY_CL,
                                     control |-> DEFAULT_CONTROL,
                                     optype |-> "NONE",
                                     inv |-> FALSE
                                     ]
                              ]
                /\ SendToFPGA([type |-> "Downgrade",
                               cl |-> cl,
                               value |-> thxCache[cl].value,
                               control |-> thxCache[cl].control
                               ])
                /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, cacheQueues>>)
                
L2RequiredDowngrade == \E cl \in CLs:
                        /\ thxCache[cl].valid 
                        /\ thxCache[cl].inv
                        /\ thxCache[cl].optype = "NONE"
                        /\ thxCache' = [thxCache EXCEPT ![cl] = 
                                    [valid |-> FALSE,
                                     value |-> EMPTY_CL,
                                     control |-> DEFAULT_CONTROL,
                                     optype |-> "NONE",
                                     inv |-> FALSE
                                     ]
                              ]
                /\ SendToFPGA([type |-> "Downgrade",
                               cl |-> cl,
                               value |-> thxCache[cl].value,
                               control |-> thxCache[cl].control
                               ])
                /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,serviceState, cacheQueues>>)


SWGetTXValue(s) == NON_EMPTY_CLs

                
SWHandleInitSend == \E s \in Services:
                    \E val \in SWGetTXValue(s):
                    /\ serviceState[s].state = "IDLE"
                    /\ \lnot serviceState[s].preempted
                    /\ serviceState' = [serviceState EXCEPT ![s].state = "GET_CONTROL", ![s].dataMask = EMPTY_MASK, ![s].data = val, ![s].op = "TX"]
                    /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,cacheQueues, thxCache, eciLink>>)
                    

SWHandleInitRecv == \E s \in Services:
                    /\ serviceState[s].state = "IDLE"
                    /\ \lnot serviceState[s].preempted
                    /\ serviceState' = [serviceState EXCEPT ![s].state = "GET_CONTROL", ![s].dataMask = EMPTY_MASK, ![s].data = EMPTY_CL, ![s].op = "RX"]
                    /\  UNCHANGED(<<coreServiceAssignment,fpgaHandlers,cacheQueues, thxCache, eciLink>>)
                    
                    

SWGetInterrupt == \E msg \in eciLink.toCPU:
                  \E s \in Services:
                  /\ msg.type = "Interrupt"
                  /\ \lnot serviceState[s].preempted
                  /\ msg.core = serviceState[s].core
                  (*an interrupt acts like a barrier, so it can only happen when we have cleared the queues*)
                  /\ cacheQueues[msg.core].reqs = {}
                  /\ Len(cacheQueues[msg.core].resps) = 0
                  /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,cacheQueues,thxCache>>)
                  /\ ReceiveSendCPU(msg, [
                        type |-> "InterruptAck",
                        core |-> serviceState[s].core
                     ])
                  /\ (CASE s = msg.service -> UNCHANGED(<<serviceState>>)
                      [] OTHER -> serviceState' = [serviceState EXCEPT 
                                                        ![s].preempted = TRUE,
                                                        ![msg.service].preempted = FALSE
                                                  ]
                    )
                  (* TODO: perform barrier *)
                  (* TODO: either switch to other core or choose other service to run *)
                  
(*SWResume == \E s \in Services:
            \E c \in Cores:
            /\ (\A s2 \in Services: serviceState[s2].preempted \/ serviceState[s2].core # c)
            /\ serviceState[s].preempted
            /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,cacheQueues,thxCache,eciLink>>)
            /\ serviceState' = [serviceState EXCEPT ![s].preempted = FALSE, ![s].core = c]*)

(* will need to CAS on the control instead of reading regularily.*)                   
SWGetControl == \E s \in Services:
                /\ serviceState[s].state = "GET_CONTROL"
                /\ \lnot serviceState[s].preempted
                (* CAS acts like a barrier *)
                /\ cacheQueues[serviceState[s].core].reqs = {}
                /\ cacheQueues' = [cacheQueues EXCEPT ![serviceState[s].core].reqs =
                                        cacheQueues[serviceState[s].core].reqs \cup 
                                            {[
                                                type |-> "SetBusyCAS", 
                                                cl |-> serviceState[s].controlCL
                                            ]}
                                   ]
               /\ serviceState' = [serviceState EXCEPT ![s].state = "CHECK_CONTROL"]
               /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink>>)
               
SWCheckControl == \E s \in Services:
                  /\ serviceState[s].state = "CHECK_CONTROL"
                  /\ \lnot serviceState[s].preempted
                  /\ Len(cacheQueues[serviceState[s].core].resps) > 0
                  /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink>>) 
                  /\ cacheQueues' = [cacheQueues EXCEPT ![serviceState[s].core].resps 
                                        = Tail(cacheQueues[serviceState[s].core].resps)
                                   ]
                  /\ LET msg == Head(cacheQueues[serviceState[s].core].resps) IN
                        /\ msg.type = "SetBusyCASAck"
                        /\ msg.cl = serviceState[s].controlCL
                        /\ serviceState' = [serviceState EXCEPT
                                                ![s].state = IF msg.success THEN "OP" ELSE "GET_CONTROL",
                                                ![s].control = msg.control
                                              ]
                    
SWHandleOp == \E s \in Services:
              \E i \in 0..CLSize-1:
              /\ serviceState[s].state = "OP"
              /\ \lnot serviceState[s].preempted
              /\ serviceState[s].op \in {"TX", "RX"}
              /\ \lnot serviceState[s].dataMask[i]
              /\ serviceState' = [serviceState EXCEPT ![s].dataMask[i] = TRUE]
              /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink>>)
              /\ (CASE serviceState[s].op = "TX" -> 
                    cacheQueues' = [cacheQueues EXCEPT ![serviceState[s].core].reqs =
                                        cacheQueues[serviceState[s].core].reqs \cup 
                                            {[
                                                type |-> "WriteReq", 
                                                cl |-> serviceState[s].sendCLs[serviceState[s].control.currentSendCL],
                                                index |-> i,
                                                control |-> DEFAULT_CONTROL,
                                                value |-> serviceState[s].data[i]
                                            ]}
                                   ]
                  [] serviceState[s].op = "RX" ->
                      cacheQueues' = [cacheQueues EXCEPT ![serviceState[s].core].reqs =
                                        cacheQueues[serviceState[s].core].reqs \cup 
                                            {[
                                                type |-> "ReadReq", 
                                                cl |-> serviceState[s].recvCLs[serviceState[s].control.currentRecvCL],
                                                index |-> i
      
                                            ]}
                                   ]
                [] OTHER -> FALSE)
                
SWFinishOP == \E s \in Services:
              /\ serviceState[s].state = "OP"
              /\ \lnot serviceState[s].preempted
              /\  (\A i \in 0..CLSize-1: serviceState[s].dataMask[i])
              /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink>>)
              /\ cacheQueues' = [cacheQueues EXCEPT ![serviceState[s].core].barrier = TRUE]
              /\ serviceState' = [serviceState EXCEPT ![s].state = "BARRIER"]
              
SWConsumeAcks == \E s \in Services:
                 /\ serviceState[s].state = "OP" \/ serviceState[s].state = "BARRIER"
                 /\ \lnot serviceState[s].preempted
                 /\ Len(cacheQueues[serviceState[s].core].resps) > 0
                 /\ serviceState[s].op = "RX" (*only reads are acked*)
                 /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink>>)
                 /\ cacheQueues' = [cacheQueues EXCEPT ![serviceState[s].core].resps 
                                        = Tail(cacheQueues[serviceState[s].core].resps)
                                   ]
                 /\ LET resp == Head(cacheQueues[serviceState[s].core].resps) IN
                        serviceState' = [serviceState EXCEPT ![s].data[resp.index] = resp.value ]
                        
SWBarrierDone == \E s \in Services:
                 /\ serviceState[s].state = "BARRIER"
                 /\ \lnot serviceState[s].preempted
                 /\ \lnot cacheQueues[serviceState[s].core].barrier
                 /\ Len(cacheQueues[serviceState[s].core].resps) = 0
                 /\ (CASE serviceState[s].op = "TX" ->
                            LET nextCL == serviceState[s].sendCLs[1 - serviceState[s].control.currentSendCL] IN
                            /\ cacheQueues' = [cacheQueues EXCEPT 
                                                ![serviceState[s].core].reqs =
                                                    cacheQueues[serviceState[s].core].reqs 
                                                        \cup {[type |-> "PrefetchReq", cl |-> nextCL]},
                                                ![serviceState[s].core].barrier = TRUE
                                               ]
                           /\ serviceState' = [serviceState EXCEPT ![s].state = "COMPLETE_PREFETCH"]
                           /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink>>) 
                     [] serviceState[s].op = "RX" ->
                        /\ serviceState' = [serviceState EXCEPT ![s].state = "SIGNAL"]
                        /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,cacheQueues, thxCache, eciLink>>) 
                     [] OTHER -> FALSE
                    )
                 
                 
                 

(* Will need to split this into signalling and toggeling of CL parity for preemption...!*)

(* Q: what if we simply retrieve the old current CL value by invalidating that CL on preemption?*)
(*The FPGA can then hold onto it and serve it for the process to continue correctly if necessary*)
(* We are only safe once we are in the Barrier stage, during OP there may still be more steps.*)           
SWSignal == \E s \in Services:
            /\ serviceState[s].state = "SIGNAL"
            /\ \lnot serviceState[s].preempted
                (*put the retrieved data somewhere?*)
            /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,thxCache, eciLink>>)
            /\ (CASE serviceState[s].op = "TX" ->
                    LET nextIndex == 1 - serviceState[s].control.currentSendCL IN
                    LET nextCL == serviceState[s].sendCLs[nextIndex] IN
                    /\ cacheQueues' = [cacheQueues EXCEPT 
                                            ![serviceState[s].core].reqs =
                                                cacheQueues[serviceState[s].core].reqs 
                                                   (* \cup {[type |-> "PrefetchReq", cl |-> nextCL]}*)
                                                    \cup {[type |-> "UnsetBusyCAS", 
                                                           cl |-> serviceState[s].controlCL,
                                                           control |-> [serviceState[s].control EXCEPT !.currentSendCL = nextIndex]
                                                           ]} (*,
                                            ![serviceState[s].core].barrier = TRUE*)
                                      ]
                    /\ serviceState' = [serviceState EXCEPT ![s].state = "IDLE", ![s].op = "NONE", ![s].control.currentSendCL = nextIndex, ![s].control.BUSY = FALSE]
               [] serviceState[s].op = "RX" ->
                    LET nextIndex == 1 - serviceState[s].control.currentRecvCL IN
                    LET nextCL == serviceState[s].recvCLs[nextIndex] IN
                    /\ cacheQueues' = [cacheQueues EXCEPT 
                                            ![serviceState[s].core].reqs =
                                                cacheQueues[serviceState[s].core].reqs 
                                                        \cup {[type |-> "UnsetBusyCAS", 
                                                           cl |-> serviceState[s].controlCL,
                                                           control |-> [serviceState[s].control EXCEPT !.currentRecvCL = nextIndex]
                                                           ]}(*,
                                            ![serviceState[s].core].barrier = TRUE*)
                                            ]
                    /\ serviceState' = [serviceState EXCEPT ![s].state = "IDLE", ![s].op = "NONE", ![s].control.currentRecvCL = nextIndex]
               [] OTHER -> FALSE)
              
SWCompleteTXPRefetch == \E s \in Services:
                     /\ serviceState[s].state = "COMPLETE_PREFETCH"
                     /\ \lnot serviceState[s].preempted
                     /\ \lnot cacheQueues[serviceState[s].core].barrier
                     /\ Len(cacheQueues[serviceState[s].core].resps) = 0
                     /\ serviceState' = [serviceState EXCEPT ![s].state = "SIGNAL"]
                     /\ UNCHANGED(<<coreServiceAssignment,fpgaHandlers,cacheQueues, thxCache, eciLink>>) 
               
FPGAGetValue(c) == NON_EMPTY_CLs
            
FPGAHandleCurrentCLMsg == \E msg \in eciLink.toFPGA \ InterruptAck:
                      LET c == CLCoreAssignment[msg.cl] IN
                      LET handlerName == CLWorkerAssignment[msg.cl] IN
                      LET handler == fpgaHandlers[c][handlerName] IN
                      /\ handlerName \in {"send", "recv"}
                      /\ handler.state = "IDLE"
                      /\  msg.cl = handler.CLPair[handler.currentCL]

                      /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues>>)
                      
                      /\ (CASE msg.type = "UpgradeReq" ->   
                            /\ \lnot handler.inCache 
                            /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].inCache = TRUE]
                            /\ ReceiveSendFPGA(msg, [
                                type |-> "UpgradeAck",
                                value |-> handler.data,
                                control |-> DEFAULT_CONTROL,
                                cl |-> msg.cl
                               ])
                         [] msg.type = "Downgrade" ->
                            /\ handler.inCache
                            /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].inCache = FALSE, ![c][handlerName].data = msg.value]
                            /\ RecvFromFPGA(msg)
                         [] OTHER -> FALSE)
                         
                         
                        
                         
FPGAInitHandlingNextCL == \E msg \in eciLink.toFPGA \ InterruptAck:
                      LET c == CLCoreAssignment[msg.cl] IN
                      LET handlerName == CLWorkerAssignment[msg.cl] IN
                      LET handler == fpgaHandlers[c][handlerName] IN
                      /\ handler.state = "IDLE"
                      /\ handlerName \in {"send", "recv"}
                      /\  msg.cl # handler.CLPair[handler.currentCL]
                      /\ msg.type = "UpgradeReq"
                      /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues>>)
                      /\ (CASE handler.inCache ->
                            (*need to invalidate current CL first*)
                             /\ ReceiveSendFPGA(msg, [
                                    type |-> "InvReq",
                                    cl |-> handler.CLPair[handler.currentCL]                              
                                ])
                             /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "AWAIT_ACK", ![c][handlerName].pendingAck = TRUE]
                          [] \lnot handler.inCache ->
                             LET other == handler.CLPair[1-handler.currentCL] IN
                             /\ RecvFromFPGA(msg)
                             /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "HANDLE"]
                          [] OTHER -> FALSE)
                          
                          
FPGARecvDowngrades == \E msg \in eciLink.toFPGA \ InterruptAck:
                         LET c == CLCoreAssignment[msg.cl] IN
                         LET handlerName == CLWorkerAssignment[msg.cl] IN
                         LET handler == fpgaHandlers[c][handlerName] IN
                          /\ handlerName \in {"send", "recv"}
                          /\ handler.state = "AWAIT_ACK" \/ handler.state =  "AWAIT_FLUSH"
                          /\ msg.cl = handler.CLPair[handler.currentCL]
                          /\ RecvFromFPGA(msg)
                          /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues>>)
                          /\ (CASE msg.type = "InvAck" ->
                                
                                /\ fpgaHandlers' = IF msg.was_valid THEN [
                                                              fpgaHandlers EXCEPT 
                                                                ![c][handlerName].pendingAck = FALSE, 
                                                                ![c][handlerName].inCache = FALSE, 
                                                                ![c][handlerName].data = msg.value
                                                        ] ELSE [
                                                                fpgaHandlers EXCEPT
                                                                    ![c][handlerName].pendingAck = FALSE
                                                            ]
                             [] msg.type = "Downgrade" ->
                                /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                                        ![c][handlerName].inCache = FALSE, 
                                                        ![c][handlerName].data = msg.value
                                                    ]
                             [] OTHER -> FALSE)
                             
FPGAFinishInvalidations ==  \E c \in Cores:
                            \E handlerName \in {"send", "recv"}:
                           LET handler == fpgaHandlers[c][handlerName] IN
                           /\ handler.state = "AWAIT_ACK"
                           /\ handler.inCache = FALSE
                           /\ handler.pendingAck = FALSE
                           /\ UNCHANGED(<<coreServiceAssignment,thxCache, cacheQueues, serviceState, eciLink>>)
                           /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "HANDLE"]
                           
  
FPGAHandleNextReq == \E c \in Cores:
                    \E handlerName \in {"send", "recv"}:
                     LET handler == fpgaHandlers[c][handlerName] IN 
                     /\ handler.state = "HANDLE"
                     /\ UNCHANGED(<<coreServiceAssignment,serviceState,thxCache,cacheQueues>>)
                     /\ LET next == 1-handler.currentCL IN
                        (CASE handlerName = "send" ->
                            /\  SendToCPU([
                                    type |-> "UpgradeAck",
                                    cl |-> handler.CLPair[next],
                                    control |-> DEFAULT_CONTROL,
                                    value |-> EMPTY_CL  
                                   ])
                           /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                                ![c][handlerName].data = EMPTY_CL, 
                                                ![c][handlerName].currentCL = next,
                                                ![c][handlerName].inCache = TRUE,
                                                ![c][handlerName].state = "IDLE"]
                        [] handlerName = "recv" ->
                           \E val \in FPGAGetValue(c):
                           /\  SendToCPU([
                                    type |-> "UpgradeAck",
                                    cl |-> handler.CLPair[next],
                                    control |-> DEFAULT_CONTROL,
                                    value |-> val  
                                   ])
                           /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                                ![c][handlerName].data = val, 
                                                ![c][handlerName].currentCL = next,
                                                ![c][handlerName].inCache = TRUE,
                                                ![c][handlerName].state = "IDLE"]
                       [] OTHER -> FALSE)
                                                                     
                             
FPGAHandleControlReq == \E msg \in eciLink.toFPGA \ InterruptAck:
                      LET c == CLCoreAssignment[msg.cl] IN
                      LET handlerName == CLWorkerAssignment[msg.cl] IN
                      LET handler == fpgaHandlers[c][handlerName] IN
                      /\ handlerName = "control"
                      /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues>>)
                      
                      /\ (CASE msg.type = "UpgradeReq" ->
                            /\ \lnot handler.pendingAck
                            (* don't supply the control CL to the CPU when we are waiting on it ourselves *)
                            /\ \lnot handler.state = "GET_CONTROL" 
                            /\ \lnot handler.state = "AWAIT_CONTROL_INV" 
                            /\ \lnot handler.inCache 
                            /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].inCache = TRUE]
                            /\ ReceiveSendFPGA(msg, [
                                type |-> "UpgradeAck",
                                value |-> EMPTY_CL,
                                control |-> handler.control,
                                cl |-> msg.cl
                               ])
                         [] msg.type = "Downgrade" ->
                            /\ handler.inCache
                            /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                    ![c][handlerName].inCache = FALSE, 
                                    ![c][handlerName].control = 
                                                       (* don't overwrite READY *)
                                                       [msg.control EXCEPT !.READY = fpgaHandlers[c][handlerName].control.READY]
                               ]
                            /\ RecvFromFPGA(msg)
                         [] msg.type = "InvAck" ->
                            /\ fpgaHandlers' = IF msg.was_valid THEN [
                                                      fpgaHandlers EXCEPT 
                                                        ![c][handlerName].pendingAck = FALSE, 
                                                        ![c][handlerName].inCache = FALSE, 
                                                        ![c][handlerName].control = 
                                                            (* don't overwrite READY *)
                                                            [msg.control EXCEPT !.READY = fpgaHandlers[c][handlerName].control.READY]
                                                ] ELSE [
                                                        fpgaHandlers EXCEPT
                                                            ![c][handlerName].pendingAck = FALSE
                                                ]
                           /\ RecvFromFPGA(msg)
                         [] OTHER -> FALSE) 
                         
(*FPGAInitFlushData == \E c \in Cores:
                 \E handlerName \in {"send", "recv"}:
                 LET handler == fpgaHandlers[c][handlerName] IN 
                 /\ handler.state = "IDLE"
                 /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues,eciLink>>)
                 
                 /\ (CASE handlerName = "send" ->
                     /\ fpgaHandlers[c]["control"].sendPreempt
                     /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                            ![c][handlerName].data = EMPTY_CL,
                                            ![c]["control"].sendPreempt = FALSE
                                        ]
                     [] OTHER ->
                     /\ fpgaHandlers[c]["control"].recvPreempt
                     /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                            ![c][handlerName].data = EMPTY_CL,
                                            ![c]["control"].recvPreempt = FALSE
                                        ]
                   )*)
                 
(*FPGAInitFlushData == \E c \in Cores:
                     \E handlerName \in {"send", "recv"}:
                     LET handler == fpgaHandlers[c][handlerName] IN 
                     /\ handler.state = "IDLE"
                     /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues,eciLink>>)
                     /\ fpgaHandlers[c]["control"][handlerName \o "Preempt"]
                     /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                            ![c][handlerName].data = EMPTY_CL,
                                            ![c]["control"][handlerName \o "Preempt"] = FALSE
                                        ]*)

FPGAInitFlushData == \E c \in Cores:
                     \E handlerName \in {"send", "recv"}:
                     LET handler == fpgaHandlers[c][handlerName] IN 
                     /\ handler.state = "IDLE"
                     /\ fpgaHandlers[c]["control"][handlerName \o "Preempt"]
                     /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues>>)
                     /\ (CASE handler.inCache ->
                            /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                            ![c][handlerName].data = EMPTY_CL,
                                            ![c][handlerName].state = "AWAIT_FLUSH",
                                            ![c][handlerName].pendingAck = TRUE
                                        ]
                            /\ SendToCPU([ type |-> "InvReq", cl |-> handler.CLPair[handler.currentCL]])
                     
                         [] OTHER -> 
                                /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                            ![c][handlerName].data = EMPTY_CL,
                                            ![c][handlerName].state = "FINISH_PREEMPT",
                                            ![c]["control"][handlerName \o "Preempt"] = FALSE
                                        ]
                                /\ UNCHANGED(<<eciLink>>)
                         
                         )
                                        
FPGAFinishFlush ==  \E c \in Cores:
                            \E handlerName \in {"send", "recv"}:
                           LET handler == fpgaHandlers[c][handlerName] IN
                           /\ handler.state = "AWAIT_FLUSH"
                           /\ handler.inCache = FALSE
                           /\ handler.pendingAck = FALSE
                           /\ UNCHANGED(<<coreServiceAssignment,thxCache, cacheQueues, serviceState, eciLink>>)
                           /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "FINISH_PREEMPT",
                                                                   ![c]["control"][handlerName \o "Preempt"] = FALSE]

FPGAHandlerFinishPreempt ==  \E c \in Cores:
                            \E handlerName \in {"send", "recv"}:
                           LET handler == fpgaHandlers[c][handlerName] IN
                           /\ handler.state = "FINISH_PREEMPT"
                           /\ fpgaHandlers[c]["control"].state = "AWAIT_CONTROL_INV"
                           /\ UNCHANGED(<<coreServiceAssignment,thxCache, cacheQueues, serviceState, eciLink>>)
                           /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "IDLE",
                                                                   ![c][handlerName].data = [i \in 0..CLSize-1 |-> coreServiceAssignment[c]]
                                              ]
                
                 
                         
FPGAInitPreemption == \E c \in Cores:
                      LET handlerName == "control" IN
                      LET handler == fpgaHandlers[c][handlerName] IN
                      /\ handler.state = "IDLE"
                      (* check that our service is running on this core?*)
                      /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues,eciLink>>)
                      /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "START_PREEMPT"]
                      
FPGAStartPreempt == \E c \in Cores:
                      LET handlerName == "control" IN
                      LET handler == fpgaHandlers[c][handlerName] IN
                      /\ handler.state = "START_PREEMPT"
                      /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues>>)
                      (* check if we have the control already *)
                      /\ (CASE handler.inCache ->
                                /\ fpgaHandlers' = [fpgaHandlers EXCEPT
                                                            ![c][handlerName].state = "GET_CONTROL",
                                                            ![c][handlerName].pendingAck = TRUE,
                                                            ![c][handlerName].control.READY = FALSE
                                                        ]
                                /\ SendToCPU([
                                            type |-> "InvReq",
                                            cl |-> handler.controlCL
                                       ])
                          [] OTHER ->
                                 /\ UNCHANGED(<<eciLink>>)
                                 /\ (CASE handler.control.BUSY -> 
                                        (*we do have the control but it is saying BUSY*)
                                        fpgaHandlers' = [fpgaHandlers EXCEPT 
                                                    
                                                    (* stay in state *)
                                                    ![c][handlerName].control.READY = FALSE
                                                ]
                                      [] OTHER -> 
                                        (*we have the control and it's not BUSY*)
                                                fpgaHandlers' = [fpgaHandlers EXCEPT 
                                                    
                                                    ![c][handlerName].state = "AWAIT_WORKERS",
                                                    ![c][handlerName].sendPreempt = TRUE,
                                                    ![c][handlerName].recvPreempt = TRUE,
                                                    ![c][handlerName].control.READY = FALSE
                                                  ]
                                     )
                           )
                           
FPGAGetControl ==   \E c \in Cores:
                      LET handlerName == "control" IN
                      LET handler == fpgaHandlers[c][handlerName] IN
                      /\ handler.state = "GET_CONTROL"
                      /\ \lnot handler.pendingAck 
                      /\ \lnot handler.inCache
                      /\ UNCHANGED(<<coreServiceAssignment,coreServiceAssignment,thxCache,serviceState,eciLink,cacheQueues>>)
                      /\ (CASE handler.control.BUSY -> 
                                        (*we do have the control but it is saying BUSY*)
                                        fpgaHandlers' = [fpgaHandlers EXCEPT 
                                                    
                                                            ![c][handlerName].state = "START_PREEMPT"
                                                        ]
                          [] OTHER -> 
                            (*we have the control and it's not BUSY*)
                                    fpgaHandlers' = [fpgaHandlers EXCEPT 
                                        
                                        ![c][handlerName].state = "AWAIT_WORKERS",
                                        ![c][handlerName].sendPreempt = TRUE,
                                        ![c][handlerName].recvPreempt = TRUE,
                                        ![c][handlerName].control.READY = FALSE
                                      ]
                         )   

FPGAAwaitWorkers == \E c \in Cores:
                    \E s \in Services:
                      LET handlerName == "control" IN
                      LET handler == fpgaHandlers[c][handlerName] IN
                      (*select a service that currently isn't active on any other core*)
                      /\ \A c2 \in Cores \ {c}: coreServiceAssignment[c2] # s
                      /\ handler.state = "AWAIT_WORKERS"
                      /\ handler.sendPreempt = FALSE
                      /\ handler.recvPreempt = FALSE
                      /\ UNCHANGED(<<thxCache,serviceState,cacheQueues>>)
                      /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "AWAIT_IPI_ACK"]
                      /\ SendToCPU([
                               type |-> "Interrupt",
                               core |-> c,
                               service |-> s
                              ])
                      /\ coreServiceAssignment' = [coreServiceAssignment EXCEPT ![c] = s]
                        
                    
                                                
                                                                                       
FPGAFinishPreempt == \E msg \in eciLink.toFPGA \cap InterruptAck:
                     LET handlerName == "control" IN
                     LET handler == fpgaHandlers[msg.core][handlerName] IN
                     /\ handler.state = "AWAIT_IPI_ACK"
                     /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues>>)
                     /\ (CASE handler.inCache ->
                            /\ ReceiveSendFPGA(msg, [
                                    type |-> "InvReq",
                                    cl |-> handler.controlCL
                                  ])
                            /\ fpgaHandlers' = [fpgaHandlers EXCEPT 
                                                    ![msg.core][handlerName].state = "AWAIT_CONTROL_INV", 
                                                    ![msg.core][handlerName].pendingAck = TRUE,
                                                    ![msg.core][handlerName].control.READY = TRUE
                                                ]
                       [] OTHER ->
                             /\ RecvFromFPGA(msg)
                             /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![msg.core][handlerName].state = "AWAIT_CONTROL_INV", ![msg.core][handlerName].control.READY = TRUE]
                       )
                       
FPGAAwaitInv ==      \E c \in Cores:
                     LET handlerName == "control" IN
                     LET handler == fpgaHandlers[c][handlerName] IN
                     /\ handler.state = "AWAIT_CONTROL_INV"
                     /\ \lnot handler.inCache
                     /\ \lnot handler.pendingAck
                     /\ \A worker \in {"send", "recv"}: fpgaHandlers[c][worker].state # "FINISH_PREEMPT"
                     /\ UNCHANGED(<<coreServiceAssignment,thxCache,serviceState,cacheQueues,eciLink>>)
                     /\ fpgaHandlers' = [fpgaHandlers EXCEPT ![c][handlerName].state = "IDLE"]
                      
                                                             
    
LauberhornStep   == \E c \in Cores:
                    \/ L2InitLocalReq(c)
                    \/ L2InitRemoteReq(c)
                    \/ L2FinishBarrier(c)  
                    \/ L2HandleControlInv
                    \/ L2HandleDataInv
                    \/ L2HandleUpgradeAck
                    \/ L2VoluntaryDowngrade  
                    \/ SWHandleInitSend 
                    \/ SWHandleInitRecv
                    \/ SWGetControl
                    \/ SWGetInterrupt
                    (*\/ SWResume*)
                    \/ SWCheckControl
                    \/ SWHandleOp
                    \/ SWFinishOP
                    \/ SWConsumeAcks
                    \/ SWBarrierDone
                    \/ SWCompleteTXPRefetch
                    \/ SWSignal
                    \/ FPGAHandleCurrentCLMsg
                    \/ FPGAInitHandlingNextCL
                    \/ FPGARecvDowngrades
                    \/ FPGAFinishInvalidations
                    \/ FPGAHandleNextReq
                    \/ FPGAInitPreemption
                    \/ FPGAStartPreempt
                    \/ FPGAGetControl
                    \/ FPGAAwaitWorkers
                    \/ FPGAHandleControlReq
                    \/ FPGAInitFlushData
                    \/ FPGAFinishFlush
                    \/ FPGAHandlerFinishPreempt
                    \/ FPGAFinishPreempt
                    \/ L2RequiredDowngrade
                    \/ FPGAAwaitInv
                    
                   
\**********************************
\* INVARIANTS
\**********************************

LauberhornTypeOk ==
    /\ thxCache \in CacheState
    /\ \A cl \in CLs:
        /\ (CASE CLWorkerAssignment[cl] = "control" ->
                /\ thxCache[cl].value = EMPTY_CL
           [] OTHER  ->
                /\ thxCache[cl].control = DEFAULT_CONTROL
            )
    /\ cacheQueues \in CacheQueues
    /\ \A s \in Services: serviceState[s] \in SWHandler
    /\ eciLink \in Link
    /\ \A c \in Cores: 
        fpgaHandlers[c] \in FPGAWorker
    /\ IsInjective(coreServiceAssignment)
      
    (* at most one message to send when delete queue finishes *)
    (* ensure message queue is bounded*)
    

LauberhornCLExclusion ==  \A s \in Services: 
                          \A pair \in {serviceState[s].sendCLs, serviceState[s].recvCLs}:
                          (* the ThunderX never has both cache lines in its cache *)
                         /\ \lnot (thxCache[pair[0]].valid /\ thxCache[pair[1]].valid)
                         (* An InvReq and Upgrade request never cross for the same CL *)
                         /\  \lnot \E cl \in CLs:
                                /\ CLWorkerAssignment[cl] # "control"
                                /\    {m \in eciLink.toCPU: m.type = "InvReq" /\ m.cl = cl} # {} 
                                /\    {m \in eciLink.toFPGA: m.type = "UpgradeReq" /\ m.cl = cl} # {}
                                
                         
PreemptionInvariants ==  (* it can happen that the handlers are still busy (due to the prefetch write!*)
                                        (*/\ \A c \in Cores:
                                            \/ \lnot (fpgaHandlers[c]["control"].sendPreempt \/ fpgaHandlers[c]["control"].recvPreempt)
                                            \/ (fpgaHandlers[c]["send"].state \in {"IDLE","AWAIT_ACK"} /\ fpgaHandlers[c]["recv"].state \in {"IDLE", "AWAIT_ACK"})*)
                                       /\ \A c \in Cores:
                                          (\E msg \in eciLink.toCPU: msg.type = "Interrupt" /\ msg.core = c) => 
                                          (\A s \in Services: serviceState[s].core # c \/ serviceState[s].state \in {"IDLE","GET_CONTROL","CHECK_CONTROL"})
IsolationInvariants ==  /\ \A s \in Services:
                              serviceState[s].state = "SIGNAL" /\ serviceState[s].op = "RX" => serviceState[s].data = [i \in 0..CLSize-1 |-> s]
                        /\ \A c \in Cores:
                              fpgaHandlers[c]["send"].state = "HANDLE" => fpgaHandlers[c]["send"].data = [i \in 0..CLSize-1 |-> coreServiceAssignment[c]]
                                          
                                                       
                                    
                                   
                                        

                    
                                                    
                  

=============================================================================
\* Modification History
\* Last modified Tue Mar 25 13:01:29 CET 2025 by jasmin
\* Created Thu Mar 06 13:29:27 CET 2025 by jasmin
