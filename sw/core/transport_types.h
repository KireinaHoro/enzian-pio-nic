/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LH_TRANSPORT_TYPES_H
#define LH_TRANSPORT_TYPES_H
#ifdef __KERNEL__
#include <linux/types.h>
#else
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#endif
typedef enum {
  TY_ERROR,
  TY_BYPASS,
  TY_NEIGHBOR_MISS,
  TY_ONCRPC_CALL_RX,
  TY_ONCRPC_REPLY_TX,
  TY_ONCRPC_CALL_TX,
  TY_ONCRPC_REPLY_RX,
  TY_EXECUTION_CONTROL,
  TY_NATIVE_UDP_RX,
  TY_NATIVE_UDP_TX,
  TY_DESCHED, /* RX status only: no application payload */
} lauberhorn_pkt_desc_type_t;

// descriptor for one packet / transaction
typedef struct {
  lauberhorn_pkt_desc_type_t type;
  uint8_t execution; /* local interval end=1, begin=2, combined=3 */
  // transaction metadata (packet header, RPC session data, etc.)
  union {
    struct { uint32_t endpoint, saddr, daddr; uint16_t sport, dport, trace_ref; } udp;
    struct {
      enum {
        HDR_ERROR,
        HDR_ETHERNET,
        HDR_IP,
        HDR_UDP,
      } header_type;
    } bypass;
    struct {
      int neigh_tbl_idx;
      // Wire-native IPv4 values consumed directly by the Linux network stack.
      uint32_t ip_addr;
      uint32_t saddr;
      uint8_t proto;
    } neighbor_miss;
    struct {
      void *func_ptr;
      uint32_t request_token;
    } oncrpc_call_rx;
    struct {
      uint32_t request_token;
      uint8_t status; // ONC-RPC accept_stat, 0 for success
    } oncrpc_reply_tx;
    struct {
      uint16_t pid;
      uint32_t cookie;
      // RPC numbers and IPv4 address are host-order; ECI performs wire conversion.
      uint32_t xid;
      uint32_t daddr;
      uint16_t sport;
      uint16_t dport;
      uint32_t prog_num;
      uint32_t prog_ver;
      uint32_t proc_num;
    } oncrpc_call_tx;
    struct {
      uint16_t pid;
      uint32_t cookie;
      // Host-order XID, converted by ECI for pending-call lookup.
      uint32_t xid;
      uint8_t status; // nonzero replies bypass success deserialization
    } oncrpc_reply_rx;
  };

  // Number of bytes in the contiguous transport payload.
  size_t payload_len;
} lauberhorn_pkt_desc_t;

typedef struct {
  uint8_t *rx_next_cl;

  /* One contiguous RX borrow, including zero-length packets. data is valid
   * until core_rx_release(); no acquisition or yield while pending.
   * The next acquisition retires the underlying hardware slot. */
  struct {
    uint8_t *data;
    size_t length;
    bool pending;
  } rx_view;

  bool tx_reserved;
  size_t tx_capacity;
  void *tx_reservation_base;
  uint64_t tx_generation;

  uint8_t *tx_next_cl;
  uint8_t *tx_buf;
} lauberhorn_core_state_t;


#endif
