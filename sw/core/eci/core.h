// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// Core datapath functions over ECI.  These will be used both by the kernel
// (bypass datapath netdev) and by the runtime (RPC handling for user apps).
// Use caution to ensure code is reusable by both -- debug facilities, etc.
// will have both implementations in core-common.h

#ifndef LAUBERHORN_CORE_ECI_CORE_H
#define LAUBERHORN_CORE_ECI_CORE_H

#include "core-common.h"
#include "eci/config.h"
#include "lauberhorn_eci_dev.h"

#define LAUBERHORN_BYPASS_HDR_SIZE (LAUBERHORN_BYPASS_HDR_WIDTH / 8)
#define LAUBERHORN_ONCRPC_INLINE_ARGS (LAUBERHORN_ONCRPC_INLINE_BYTES / 4)

typedef enum {
  TY_ERROR,
  TY_BYPASS,
  TY_ARP_REQ,
  TY_ONCRPC_CALL,
  TY_ONCRPC_REPLY,
} lauberhorn_pkt_desc_type_t;

// descriptor for one packet / transaction
typedef struct {
  lauberhorn_pkt_desc_type_t type;
  // transaction metadata (packet header, RPC session data, etc.)
  union {
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
      uint32_t ip_addr;
    } arp_req;
    struct {
      void *func_ptr;
      int xid;
    } oncrpc_server;
  };

  // payload buffer in lauberhorn_core_state_t
  size_t payload_len;
} lauberhorn_pkt_desc_t;

typedef struct {
  uint8_t *rx_next_cl;
  uint8_t *rx_buf;
  int rx_buf_size;

  uint8_t *tx_next_cl;
  uint8_t *tx_buf;
} lauberhorn_core_state_t;

#define LAUBERHORN_ECI_CL_SIZE (0x80)
#define LAUBERHORN_ECI_INLINE_DATA_OFFSET (0x40)
#define LAUBERHORN_ECI_INLINE_DATA_SIZE                                        \
  (LAUBERHORN_ECI_CL_SIZE - LAUBERHORN_ECI_INLINE_DATA_OFFSET)

// clang-format off
// cache-coherent area layout:
//
// LAUBERHORN_ECI_RX_BASE                                       ECI host control info for RX (lauberhorn_eci_host_ctrl_info_*_t)
//                        + 0x40                                RX inline data
//                        + 0x80                                the other RX CL
//                        + LAUBERHORN_ECI_OVERFLOW_OFFSET      RX overflow cachelines
// (count: LAUBERHORN_ECI_NUM_OVERFLOW_CL)
//
// LAUBERHORN_ECI_TX_BASE                                       ECI host control info for TX - host_ctrl_info_*_t
//                        + 0x40                                TX inline data
//                        + 0x80                                the other TX CL
//                        + LAUBERHORN_ECI_OVERFLOW_OFFSET      TX overflow cachelines
// (count: LAUBERHORN_ECI_NUM_OVERFLOW_CL)
//
// LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET                           preemption control CL (non-bypass)
//
// LAUBERHORN_ECI_CORE_OFFSET                                   the end of mapped region
// clang-format on

static_assert(LAUBERHORN_ECI_OVERFLOW_OFFSET == LAUBERHORN_ECI_CL_SIZE * 2,
              "overflow CL should start after two control CL");
static_assert(LAUBERHORN_ECI_TX_BASE >=
                  LAUBERHORN_ECI_OVERFLOW_OFFSET +
                      LAUBERHORN_ECI_NUM_OVERFLOW_CL * LAUBERHORN_ECI_CL_SIZE,
              "TX control CL should not overlap with RX overflow CL");
static_assert(LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET >=
                  LAUBERHORN_ECI_TX_BASE + LAUBERHORN_ECI_OVERFLOW_OFFSET +
                      LAUBERHORN_ECI_NUM_OVERFLOW_CL * LAUBERHORN_ECI_CL_SIZE,
              "preempt control CL should not overlap with TX overflow CL");
static_assert(
    LAUBERHORN_ECI_CORE_OFFSET >=
        LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET + LAUBERHORN_ECI_CL_SIZE,
    "preempt control CL should not overlap with RX control CL in next core");

static inline bool core_eci_rx(void *base, lauberhorn_core_state_t *ctx,
                               lauberhorn_pkt_desc_t *desc) {

  // make sure previous RX/TX actually took effect before we attempt to RX
  BARRIER;

  enter_cs(base);

  bool rx_parity = *ctx->rx_next_cl;
  pr_debug("eci_rx: current cacheline ID: %d\n", rx_parity);

  uint8_t *rx_base = (uint8_t *)base + LAUBERHORN_ECI_RX_BASE;
  uint8_t *rx_ctrl = rx_base + rx_parity * LAUBERHORN_ECI_CL_SIZE;

  bool valid = lauberhorn_eci_host_ctrl_info_error_valid_extract(rx_ctrl);
  BARRIER; // make sure the CL is actually read

  // always toggle CL
  *ctx->rx_next_cl = !rx_parity;

  // decode the packet
  if (!valid) {
    pr_debug("eci_rx: did not get a packet\n");
  } else {

    size_t pkt_len = lauberhorn_eci_host_ctrl_info_error_len_extract(rx_ctrl);
    pr_debug("eci_rx: got a packet with len %#lx\n", pkt_len);

    lauberhorn_eci_packet_desc_type_t ty =
        lauberhorn_eci_host_ctrl_info_error_ty_extract(rx_ctrl);

#ifdef __KERNEL__
    if (ty != lauberhorn_eci_bypass && ty != lauberhorn_eci_arp_req) {
      pr_err("unexpected RX request type in kernel: %s!\n",
             lauberhorn_eci_host_req_type_describe(ty));
      BUG();
    }
#endif

    switch (ty) {
#ifdef __KERNEL__
    case lauberhorn_eci_bypass:
      size_t bypass_hdr_len;

      desc->type = TY_BYPASS;

      // decode header type
      switch (lauberhorn_eci_host_ctrl_info_bypass_hdr_ty_extract(rx_ctrl)) {
      case lauberhorn_eci_hdr_ethernet:
        desc->bypass.header_type = HDR_ETHERNET;
        bypass_hdr_len = 14;
        break;
      case lauberhorn_eci_hdr_ip:
        desc->bypass.header_type = HDR_IP;
        bypass_hdr_len = 14 + 20;
        break;
      case lauberhorn_eci_hdr_udp:
        desc->bypass.header_type = HDR_UDP;
        bypass_hdr_len = 14 + 20 + 8;
        break;
      default:
        pr_err("unexpected bypass packet type: %s\n",
               lauberhorn_eci_packet_desc_type_describe(ty));
        desc->bypass.header_type = HDR_ERROR;
        bypass_hdr_len = LAUBERHORN_BYPASS_HDR_SIZE;
      }

      // parsed bypass header is aligned after the descriptor header (still
      // inside ctrl CL)
      memcpy(ctx->rx_buf, rx_ctrl + lauberhorn_eci_host_ctrl_info_bypass_size,
             bypass_hdr_len);
      desc->payload_len = bypass_hdr_len;

      break;
    case lauberhorn_eci_arp_req:
      desc->type = TY_ARP_REQ;
      desc->arp_req.neigh_tbl_idx =
          lauberhorn_eci_host_ctrl_info_arp_req_tbl_idx_extract(rx_ctrl);
      desc->arp_req.ip_addr =
          lauberhorn_eci_host_ctrl_info_arp_req_ip_addr_extract(rx_ctrl);
      desc->payload_len = 0;
      break;
#else // ! __KERNEL__
    case lauberhorn_eci_onc_rpc_call:
      desc->type = TY_ONCRPC_CALL;
      desc->oncrpc_server.func_ptr =
          (void *)lauberhorn_eci_host_ctrl_info_onc_rpc_server_func_ptr_extract(
              rx_ctrl);
      desc->oncrpc_server.xid =
          lauberhorn_eci_host_ctrl_info_onc_rpc_server_xid_extract(rx_ctrl);

      // parsed oncrpc arguments are aligned after the descriptor header
      // XXX: we don't have the actual count of args, copy maximum
      memcpy(ctx->rx_buf,
             rx_ctrl + lauberhorn_eci_host_ctrl_info_onc_rpc_server_size,
             LAUBERHORN_ONCRPC_INLINE_BYTES);
      desc->payload_len = LAUBERHORN_ONCRPC_INLINE_BYTES;

      break;
#endif
    default:
      desc->type = TY_ERROR;
      desc->payload_len = 0;
    }

    if (pkt_len > 0) {
      uint8_t *copy_dest = ctx->rx_buf + desc->payload_len;
      desc->payload_len += pkt_len;
      assert(desc->payload_len <= ctx->rx_buf_size);

      int first_read_size = pkt_len > LAUBERHORN_ECI_INLINE_DATA_SIZE
                                ? LAUBERHORN_ECI_INLINE_DATA_SIZE
                                : pkt_len;
      memcpy(copy_dest, rx_ctrl + LAUBERHORN_ECI_INLINE_DATA_OFFSET,
             first_read_size);
      copy_dest += first_read_size;
      pkt_len -= first_read_size;

      if (pkt_len > 0) {
        memcpy(copy_dest, rx_base + LAUBERHORN_ECI_OVERFLOW_OFFSET, pkt_len);
      }
    }

    // All data in ctx->rx_buf, good to exit the critical section
  }

  BARRIER; // make sure !BUSY comes after

  pr_debug("eci_rx: finished rx\n");

  exit_cs(base);

  // Done
  return valid;
}

static inline void core_eci_tx(void *base, lauberhorn_core_state_t *ctx,
                               lauberhorn_pkt_desc_t *desc) {
  // ECI backend: do not release payload_buf

  // make sure previous RX/TX actually took effect before we attempt to RX
  BARRIER;

  enter_cs(base);

  bool tx_parity = *ctx->tx_next_cl;
  pr_debug("eci_tx: current cacheline ID: %d\n", tx_parity);

  uint8_t *tx_base = (uint8_t *)base + LAUBERHORN_ECI_TX_BASE;
  uint8_t *tx_ctrl = tx_base + tx_parity * LAUBERHORN_ECI_CL_SIZE;

  uint8_t *copy_from = ctx->tx_buf;
  size_t payload_len = desc->payload_len;

#ifndef __KERNEL__
  size_t oncrpc_inlined_bytes = 0;
#endif

  switch (desc->type) {
#ifdef __KERNEL__
  case TY_BYPASS:
    uint8_t *tx_cmd = tx_ctrl + lauberhorn_eci_host_ctrl_info_bypass_size;

    lauberhorn_eci_host_ctrl_info_error_ty_insert(tx_ctrl,
                                                  lauberhorn_eci_bypass);
    if (desc->bypass.header_type != HDR_ETHERNET) {
      pr_err("bypass TX only accepts Ethernet packets; trying to send %s\n",
             lauberhorn_eci_packet_desc_type_describe(desc->type));
      goto out;
    }

    lauberhorn_eci_host_ctrl_info_bypass_hdr_ty_insert(
        tx_ctrl, lauberhorn_eci_hdr_ethernet);

    // Ethernet bypass only takes destination mac and ethertype
    // TODO: use Mackerel datatypes here

    // destination MAC
    memcpy(tx_cmd, copy_from, 6);

    // XXX: not writing source MAC, thus not supporting bridging, forwarding,
    //      etc.

    // EtherType
    memcpy(tx_cmd + 6, copy_from + 12, 2);

    copy_from += 14;
    payload_len -= 14;

    // tx bypass len field does not include header
    lauberhorn_eci_host_ctrl_info_bypass_len_insert(tx_ctrl, payload_len);
    break;

#else // ! __KERNEL__
  case TY_ONCRPC_REPLY:
    lauberhorn_eci_host_ctrl_info_onc_rpc_server_ty_insert(
        tx_ctrl, lauberhorn_eci_onc_rpc_reply);

    lauberhorn_eci_host_ctrl_info_onc_rpc_server_xid_insert(
        tx_ctrl, desc->oncrpc_server.xid);
    lauberhorn_eci_host_ctrl_info_onc_rpc_server_func_ptr_insert(
        tx_ctrl, (uint64_t)desc->oncrpc_server.func_ptr);

    // tx RPC len field INCLUDES inlined words
    lauberhorn_eci_host_ctrl_info_onc_rpc_server_len_insert(tx_ctrl,
                                                            payload_len);

    if (payload_len > LAUBERHORN_ONCRPC_INLINE_BYTES) {
      oncrpc_inlined_bytes = LAUBERHORN_ONCRPC_INLINE_BYTES;
    } else {
      oncrpc_inlined_bytes = payload_len;
    }

    // inlined ONCRPC words
    memcpy(tx_ctrl + lauberhorn_eci_host_ctrl_info_onc_rpc_server_size,
           copy_from, oncrpc_inlined_bytes);
    copy_from += oncrpc_inlined_bytes;
    payload_len -= oncrpc_inlined_bytes;
    break;

#endif
  default:
    pr_err("eci_tx: unhandled host request type %d\n", desc->type);
    break;
  }

  if (payload_len > 0) {
    assert(payload_len <=
           LAUBERHORN_ECI_INLINE_DATA_SIZE +
               LAUBERHORN_ECI_NUM_OVERFLOW_CL * LAUBERHORN_ECI_CL_SIZE);

    // fill second half-CL in control CL first
    int first_write_size = min(LAUBERHORN_ECI_INLINE_DATA_SIZE, payload_len);
    memcpy(tx_ctrl + LAUBERHORN_ECI_INLINE_DATA_OFFSET, copy_from,
           first_write_size);
    copy_from += first_write_size;
    payload_len -= first_write_size;

    // fill overflow CLs
    if (payload_len > 0) {
      memcpy(tx_base + LAUBERHORN_ECI_OVERFLOW_OFFSET, copy_from, payload_len);
    }
  }
  BARRIER; // make sure all data is written before we ring the doorbell

  // Flip the parity
  *ctx->tx_next_cl = !tx_parity;

  // Ring the doorbell: read next CL to trigger send
  (void)*(uint8_t *)(base + LAUBERHORN_ECI_TX_BASE +
                     !tx_parity * LAUBERHORN_ECI_CL_SIZE);

  pr_debug("eci_tx: finished tx\n");

#ifdef __KERNEL__
out:
#endif
  BARRIER; // make sure !BUSY comes after

  exit_cs(base);
}

#endif // LAUBERHORN_CORE_ECI_CORE_H
