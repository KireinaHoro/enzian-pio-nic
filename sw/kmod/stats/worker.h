#include "def_attrs.h"

#define DEVICE_TO_MACKEREL_DEV(class, name, dev)                \
	({                                                      \
		struct worker_dev *priv = dev_get_drvdata(dev); \
		&priv->class##_dev;                             \
	})

#define RPC_DEC_STATS_LIST(FUNC)                   \
	FUNC(OncRpcCallDecoder, header_only)       \
	FUNC(OncRpcCallDecoder, partial_header)    \
	FUNC(OncRpcCallDecoder, incomplete_header) \
	FUNC(OncRpcCallDecoder, normal_packets)

#define UDP_DEC_STATS_LIST(FUNC)            \
	FUNC(UdpDecoder, header_only)       \
	FUNC(UdpDecoder, partial_header)    \
	FUNC(UdpDecoder, incomplete_header) \
	FUNC(UdpDecoder, normal_packets)

#define RPC_ENC_STATS_LIST(FUNC) FUNC(OncRpcReplyEncoder, dropped)

#if LAUBERHORN_NUM_WORKER_CORES >= 1
#define SCHED_CORE_1(FUNC) \
    FUNC(sched, popped_core_1) \
    FUNC(sched, preempted_core_1) \
    FUNC(sched, dispatched_core_1)
#else
#define SCHED_CORE_1(FUNC)
#endif

#if LAUBERHORN_NUM_WORKER_CORES >= 2
#define SCHED_CORE_2(FUNC) \
    FUNC(sched, popped_core_2) \
    FUNC(sched, preempted_core_2) \
    FUNC(sched, dispatched_core_2)
#else
#define SCHED_CORE_2(FUNC)
#endif

#if LAUBERHORN_NUM_WORKER_CORES >= 3
#define SCHED_CORE_3(FUNC) \
    FUNC(sched, popped_core_3) \
    FUNC(sched, preempted_core_3) \
    FUNC(sched, dispatched_core_3)
#else
#define SCHED_CORE_3(FUNC)
#endif

#if LAUBERHORN_NUM_WORKER_CORES >= 4
#define SCHED_CORE_4(FUNC) \
    FUNC(sched, popped_core_4) \
    FUNC(sched, preempted_core_4) \
    FUNC(sched, dispatched_core_4)
#else
#define SCHED_CORE_4(FUNC)
#endif

#if LAUBERHORN_NUM_WORKER_CORES >= 5
#define SCHED_CORE_5(FUNC) \
    FUNC(sched, popped_core_5) \
    FUNC(sched, preempted_core_5) \
    FUNC(sched, dispatched_core_5)
#else
#define SCHED_CORE_5(FUNC)
#endif

#if LAUBERHORN_NUM_WORKER_CORES >= 6
#define SCHED_CORE_6(FUNC) \
    FUNC(sched, popped_core_6) \
    FUNC(sched, preempted_core_6) \
    FUNC(sched, dispatched_core_6)
#else
#define SCHED_CORE_6(FUNC)
#endif

#if LAUBERHORN_NUM_WORKER_CORES >= 7
#define SCHED_CORE_7(FUNC) \
    FUNC(sched, popped_core_7) \
    FUNC(sched, preempted_core_7) \
    FUNC(sched, dispatched_core_7)
#else
#define SCHED_CORE_7(FUNC)
#endif

#define SCHED_STATS_LIST(FUNC) \
    FUNC(sched, pushed) \
    FUNC(sched, dropped) \
    SCHED_CORE_1(FUNC) \
    SCHED_CORE_2(FUNC) \
    SCHED_CORE_3(FUNC) \
    SCHED_CORE_4(FUNC) \
    SCHED_CORE_5(FUNC) \
    SCHED_CORE_6(FUNC) \
    SCHED_CORE_7(FUNC)

#define STAT_GROUPS(FUNC)                     \
	FUNC(rpcdec_stat, RPC_DEC_STATS_LIST) \
	FUNC(udpdec_stat, UDP_DEC_STATS_LIST) \
	FUNC(rpcenc_stat, RPC_ENC_STATS_LIST) \
	FUNC(sched_stat, SCHED_STATS_LIST)

MAKE_STAT_GROUPS(worker, STAT_GROUPS)
