#include "def_attrs.h"

#define DEVICE_TO_MACKEREL_DEV(class, name, dev)                         \
	({                                                               \
		struct netdev_priv *priv = netdev_priv(to_net_dev(dev)); \
		&priv->class##_dev;                                      \
	})

#define DMA_STATS_LIST(FUNC)                    \
	FUNC(dma, rx_packet_count)              \
	FUNC(dma, tx_packet_count)              \
	FUNC(dma, rx_dma_error_count)           \
	FUNC(dma, tx_dma_error_count)           \
	FUNC(dma, rx_alloc_occupancy_up_to_192) \
	FUNC(dma, rx_alloc_occupancy_up_to_1600)

#define BYPASS_STATS_LIST(FUNC)           \
	FUNC(bypassSink, queue_occupancy) \
	FUNC(bypassSink, queue_availability)

#define ETH_DEC_STATS_LIST(FUNC)                 \
	FUNC(EthernetDecoder, header_only)       \
	FUNC(EthernetDecoder, partial_header)    \
	FUNC(EthernetDecoder, incomplete_header) \
	FUNC(EthernetDecoder, normal_packets)    \
	FUNC(EthernetDecoder, total_headers)     \
	FUNC(EthernetDecoder, drop_count)

#define IP_DEC_STATS_LIST(FUNC)            \
	FUNC(IpDecoder, header_only)       \
	FUNC(IpDecoder, partial_header)    \
	FUNC(IpDecoder, incomplete_header) \
	FUNC(IpDecoder, normal_packets)    \
	FUNC(IpDecoder, total_headers)     \
	FUNC(IpDecoder, drop_count)

#define UDP_DEC_STATS_LIST(FUNC)            \
	FUNC(UdpDecoder, header_only)       \
	FUNC(UdpDecoder, partial_header)    \
	FUNC(UdpDecoder, incomplete_header) \
	FUNC(UdpDecoder, normal_packets)    \
	FUNC(UdpDecoder, total_headers)

#define IP_ENC_STATS_LIST(FUNC)  \
	FUNC(IpEncoder, dropped) \
	FUNC(IpEncoder, neigh_tbl_full)

#define MAC_IF_STATS_LIST(FUNC)            \
	FUNC(macIf, rx_mac_overflow_count) \
	FUNC(macIf, rx_mac_ingress_count)  \
	FUNC(macIf, rx_mac_ingress_after_cdc_count)

#define CMAC_STATUS_LIST(FUNC)         \
	FUNC(cmac, tx_status)          \
	FUNC(cmac, rx_status)          \
	FUNC(cmac, status_1)           \
	FUNC(cmac, rx_block_lock)      \
	FUNC(cmac, rx_lane_sync)       \
	FUNC(cmac, rx_lane_sync_err)   \
	FUNC(cmac, rx_am_err)          \
	FUNC(cmac, rx_am_len_err)      \
	FUNC(cmac, rx_am_repeat_err)   \
	FUNC(cmac, rx_pcsl_demuxed)    \
	FUNC(cmac, rx_bip_override)    \
	FUNC(cmac, tx_otn_status)      \
	FUNC(cmac, an_status)          \
	FUNC(cmac, an_ability)         \
	FUNC(cmac, an_link_ctl_1)      \
	FUNC(cmac, an_link_ctl_2)      \
	FUNC(cmac, lt_status_1)        \
	FUNC(cmac, lt_status_2)        \
	FUNC(cmac, lt_status_3)        \
	FUNC(cmac, lt_status_4)        \
	FUNC(cmac, rsfec_status)       \
	FUNC(cmac, rsfec_lane_mapping) \
	FUNC(cmac, tx_otn_rsfec_status)

#define CMAC_STATUS_RD_FUNC(class, name)                             \
	static inline u64 lauberhorn_eci_##class##_stat_##name##_rd( \
		class##_t *d)                                        \
	{                                                            \
		return cmac_stat_##name##_rd(d);                     \
	}
CMAC_STATUS_LIST(CMAC_STATUS_RD_FUNC)

#define CMAC_STATISTICS_LIST(FUNC)            \
	FUNC(cmac, cycle_count)               \
	FUNC(cmac, rx_bad_code)               \
	FUNC(cmac, tx_frame_error)            \
	FUNC(cmac, tx_total_packets)          \
	FUNC(cmac, tx_total_good_packets)     \
	FUNC(cmac, tx_total_bytes)            \
	FUNC(cmac, tx_total_good_bytes)       \
	FUNC(cmac, tx_packet_64_bytes)        \
	FUNC(cmac, tx_packet_65_127_bytes)    \
	FUNC(cmac, tx_packet_128_255_bytes)   \
	FUNC(cmac, tx_packet_256_511_bytes)   \
	FUNC(cmac, tx_packet_512_1023_bytes)  \
	FUNC(cmac, tx_packet_1024_1518_bytes) \
	FUNC(cmac, tx_packet_1519_1522_bytes) \
	FUNC(cmac, tx_packet_1523_1548_bytes) \
	FUNC(cmac, tx_packet_1549_2047_bytes) \
	FUNC(cmac, tx_packet_2048_4095_bytes) \
	FUNC(cmac, tx_packet_4096_8191_bytes) \
	FUNC(cmac, tx_packet_8192_9215_bytes) \
	FUNC(cmac, tx_packet_large)           \
	FUNC(cmac, tx_packet_small)           \
	FUNC(cmac, tx_bad_fcs)                \
	FUNC(cmac, tx_unicast)                \
	FUNC(cmac, tx_multicast)              \
	FUNC(cmac, tx_broadcast)              \
	FUNC(cmac, tx_vlan)                   \
	FUNC(cmac, tx_pause)                  \
	FUNC(cmac, tx_user_pause)             \
	FUNC(cmac, rx_total_packets)          \
	FUNC(cmac, rx_total_good_packets)     \
	FUNC(cmac, rx_total_bytes)            \
	FUNC(cmac, rx_total_good_bytes)       \
	FUNC(cmac, rx_packet_64_bytes)        \
	FUNC(cmac, rx_packet_65_127_bytes)    \
	FUNC(cmac, rx_packet_128_255_bytes)   \
	FUNC(cmac, rx_packet_256_511_bytes)   \
	FUNC(cmac, rx_packet_512_1023_bytes)  \
	FUNC(cmac, rx_packet_1024_1518_bytes) \
	FUNC(cmac, rx_packet_1519_1522_bytes) \
	FUNC(cmac, rx_packet_1523_1548_bytes) \
	FUNC(cmac, rx_packet_1549_2047_bytes) \
	FUNC(cmac, rx_packet_2048_4095_bytes) \
	FUNC(cmac, rx_packet_4096_8191_bytes) \
	FUNC(cmac, rx_packet_8192_9215_bytes) \
	FUNC(cmac, rx_packet_large)           \
	FUNC(cmac, rx_packet_small)           \
	FUNC(cmac, rx_undersize)              \
	FUNC(cmac, rx_fragment)               \
	FUNC(cmac, rx_oversize)               \
	FUNC(cmac, rx_toolong)                \
	FUNC(cmac, rx_jabber)                 \
	FUNC(cmac, rx_bad_fcs)                \
	FUNC(cmac, rx_packet_bad_fcs)         \
	FUNC(cmac, rx_stomped_fcs)            \
	FUNC(cmac, rx_unicast)                \
	FUNC(cmac, rx_multicast)              \
	FUNC(cmac, rx_broadcast)              \
	FUNC(cmac, rx_vlan)                   \
	FUNC(cmac, rx_pause)                  \
	FUNC(cmac, rx_user_pause)             \
	FUNC(cmac, rx_inrangeerr)             \
	FUNC(cmac, rx_truncated)              \
	FUNC(cmac, otn_tx_jabber)             \
	FUNC(cmac, otn_tx_oversize)           \
	FUNC(cmac, otn_tx_undersize)          \
	FUNC(cmac, otn_tx_toolong)            \
	FUNC(cmac, otn_tx_fragment)           \
	FUNC(cmac, otn_tx_packet_bad_fcs)     \
	FUNC(cmac, otn_tx_stomped_fcs)        \
	FUNC(cmac, otn_tx_bad_code)

static struct {
#define DECL_CMAC_COUNTER(class, name) u64 name;
	CMAC_STATISTICS_LIST(DECL_CMAC_COUNTER)
} cmac_statistics_acc;

static void cmac_latch_all_statistics(cmac_t *d)
{
	cmac_tick_enable_wrf(d, 1);
#define ACC_CMAC_COUNTER(class, name) \
	cmac_statistics_acc.name += cmac_stat_##name##_value_rdf(d);
	CMAC_STATISTICS_LIST(ACC_CMAC_COUNTER)
}

#define CMAC_STATISTIC_RD_FUNC(class, name)                          \
	static inline u64 lauberhorn_eci_##class##_stat_##name##_rd( \
		class##_t *d)                                        \
	{                                                            \
		cmac_latch_all_statistics(d);                        \
		return cmac_statistics_acc.name;                     \
	}
CMAC_STATISTICS_LIST(CMAC_STATISTIC_RD_FUNC)

#define STAT_GROUPS(FUNC)                      \
	FUNC(dma_stats, DMA_STATS_LIST)        \
	FUNC(bypass_stats, BYPASS_STATS_LIST)  \
	FUNC(ethdec_stats, ETH_DEC_STATS_LIST) \
	FUNC(ipdec_stats, IP_DEC_STATS_LIST)   \
	FUNC(udpdec_stats, UDP_DEC_STATS_LIST) \
	FUNC(ipenc_stats, IP_ENC_STATS_LIST)   \
	FUNC(macif_stats, MAC_IF_STATS_LIST)
// FUNC(cmac_status, CMAC_STATUS_LIST)
// FUNC(cmac_stats, CMAC_STATISTICS_LIST)

MAKE_STAT_GROUPS(bypass, STAT_GROUPS)
