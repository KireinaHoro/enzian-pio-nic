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

#define SCHED_STATS_LIST(FUNC)         \
	FUNC(sched, pushed)            \
	FUNC(sched, dropped)           \
	FUNC(sched, popped_core_1)     \
	FUNC(sched, popped_core_2)     \
	FUNC(sched, popped_core_3)     \
	FUNC(sched, popped_core_4)     \
	FUNC(sched, preempted_core_1)  \
	FUNC(sched, preempted_core_2)  \
	FUNC(sched, preempted_core_3)  \
	FUNC(sched, preempted_core_4)  \
	FUNC(sched, dispatched_core_1) \
	FUNC(sched, dispatched_core_2) \
	FUNC(sched, dispatched_core_3) \
	FUNC(sched, dispatched_core_4)

#define STAT_GROUPS(FUNC)                     \
	FUNC(rpcdec_stat, RPC_DEC_STATS_LIST) \
	FUNC(udpdec_stat, UDP_DEC_STATS_LIST) \
	FUNC(rpcenc_stat, RPC_ENC_STATS_LIST) \
	FUNC(sched_stat, SCHED_STATS_LIST)

MAKE_STAT_GROUPS(worker, STAT_GROUPS)
