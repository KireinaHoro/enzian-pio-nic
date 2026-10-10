// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// Core datapath functions over ECI.  These will be used both by the kernel
// (bypass datapath netdev) and by the runtime (RPC handling for user apps).
// Use caution to ensure code is reusable by both -- debug facilities, etc.
// will have both implementations in core-common.h

#ifndef LAUBERHORN_CORE_ECI_CORE_H
#define LAUBERHORN_CORE_ECI_CORE_H

#include "config.h"
#include "core-common.h"
#include "lauberhorn_eci_dev.h"

#if !defined(LAUBERHORN_ECI_PAYLOAD_LAYOUT_VERSION) || LAUBERHORN_ECI_PAYLOAD_LAYOUT_VERSION != 3
#error "ECI runtime requires TX transaction version 3 headers"
#endif

#define LAUBERHORN_BYPASS_HDR_SIZE (LAUBERHORN_BYPASS_HDR_WIDTH / 8)
#define LAUBERHORN_ONCRPC_INLINE_ARGS (LAUBERHORN_ONCRPC_INLINE_BYTES / 4)

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
  TY_PREEMPT, /* RX status only: no application payload */
} lauberhorn_pkt_desc_type_t;

// descriptor for one packet / transaction
typedef struct {
  lauberhorn_pkt_desc_type_t type;
  uint8_t execution; /* local interval end=1, begin=2, combined=3 */
  // transaction metadata (packet header, RPC session data, etc.)
  union {
    struct { uint32_t endpoint, saddr, daddr; uint16_t sport, dport; } udp;
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
   * until core_eci_rx_release(); no acquisition or yield while pending.
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
// LAUBERHORN_ECI_CORE_OFFSET                                   the end of mapped region
// clang-format on

static_assert(LAUBERHORN_ECI_OVERFLOW_OFFSET == LAUBERHORN_ECI_CL_SIZE * 2,
              "overflow CL should start after two control CL");
static_assert(LAUBERHORN_ECI_TX_BASE >=
                  LAUBERHORN_ECI_OVERFLOW_OFFSET +
                      LAUBERHORN_ECI_NUM_OVERFLOW_CL * LAUBERHORN_ECI_CL_SIZE,
              "TX control CL should not overlap with RX overflow CL");
static_assert(LAUBERHORN_ECI_CORE_OFFSET >=
                  LAUBERHORN_ECI_TX_BASE + LAUBERHORN_ECI_OVERFLOW_OFFSET +
                      LAUBERHORN_ECI_NUM_OVERFLOW_CL * LAUBERHORN_ECI_CL_SIZE,
              "TX overflow must fit within its physical worker alias");

typedef uint64_t (*lauberhorn_trace_now_fn_t)(void);

/* Wait for RX and decode its metadata. Packet success creates rx_view; PREEMPT
 * succeeds without a borrow. NACK or an outstanding borrow returns false.
 * The traced form optionally stamps the control-line read completion. */
static inline bool core_eci_rx_acquire_traced(void *base, lauberhorn_core_state_t *ctx,
                                      lauberhorn_pkt_desc_t *desc,
                                      uint64_t *rx_unblock_ns,
                                      lauberhorn_trace_now_fn_t trace_now) {

  /* Check even in release builds: a second acquisition would retire live data. */
  if (ctx->rx_view.pending) return false;
  memset(&ctx->rx_view, 0, sizeof(ctx->rx_view));

  // make sure previous RX/TX actually took effect before we attempt to RX
  BARRIER;

  bool rx_parity = *ctx->rx_next_cl;
  pr_debug("eci_rx: current cacheline ID: %d\n", rx_parity);

  uint8_t *rx_base = (uint8_t *)base + LAUBERHORN_ECI_RX_BASE;
  uint8_t *rx_ctrl = rx_base + rx_parity * LAUBERHORN_ECI_CL_SIZE;

  bool valid = lauberhorn_eci_host_ctrl_info_error_valid_extract(rx_ctrl);
  BARRIER; // make sure the CL is actually read
  if (rx_unblock_ns && trace_now)
    *rx_unblock_ns = trace_now();

  // always toggle CL
  *ctx->rx_next_cl = !rx_parity;

  // decode the packet
  if (!valid) {
    if (lauberhorn_eci_host_rx_status_preempt_extract(rx_ctrl)) {
      desc->type = TY_PREEMPT;
      desc->payload_len = 0;
      return true;
    }
    pr_debug("eci_rx: did not get a packet\n");
  } else {

    size_t pkt_len = lauberhorn_eci_host_ctrl_info_error_len_extract(rx_ctrl);
    pr_debug("eci_rx: got a packet with len %#lx\n", pkt_len);

    lauberhorn_eci_packet_desc_type_t ty =
        lauberhorn_eci_host_ctrl_info_error_ty_extract(rx_ctrl);

#ifdef __KERNEL__
    if (ty != lauberhorn_eci_bypass && ty != lauberhorn_eci_neighbor_miss) {
      pr_err("unexpected RX request type in kernel: %s!\n",
             lauberhorn_eci_host_req_type_describe(ty));
      BUG();
    }
#endif

    switch (ty) {
#ifdef __KERNEL__
    case lauberhorn_eci_bypass:

      desc->type = TY_BYPASS;

      // decode header type
      switch (lauberhorn_eci_host_ctrl_info_bypass_rx_hdr_ty_extract(rx_ctrl)) {
      case lauberhorn_eci_hdr_ethernet:
        desc->bypass.header_type = HDR_ETHERNET;
        break;
      case lauberhorn_eci_hdr_ip:
        desc->bypass.header_type = HDR_IP;
        break;
      case lauberhorn_eci_hdr_udp:
        desc->bypass.header_type = HDR_UDP;
        break;
      default:
        pr_err("unexpected bypass packet type: %s\n",
               lauberhorn_eci_packet_desc_type_describe(ty));
        desc->bypass.header_type = HDR_ERROR;
      }

      // The borrowed payload includes the complete bypass header.

      desc->payload_len = 0;

      break;
    case lauberhorn_eci_neighbor_miss:
      desc->type = TY_NEIGHBOR_MISS;
      desc->neighbor_miss.neigh_tbl_idx =
          lauberhorn_eci_host_ctrl_info_neighbor_miss_tbl_idx_extract(rx_ctrl);
      desc->neighbor_miss.ip_addr =
          lauberhorn_eci_host_ctrl_info_neighbor_miss_ip_addr_extract(rx_ctrl);
      desc->neighbor_miss.saddr =
          lauberhorn_eci_host_ctrl_info_neighbor_miss_saddr_extract(rx_ctrl);
      desc->neighbor_miss.proto =
          lauberhorn_eci_host_ctrl_info_neighbor_miss_proto_extract(rx_ctrl);
      desc->payload_len = 0;
      break;
#else // ! __KERNEL__
    case lauberhorn_eci_native_udp_rx:
      desc->type = TY_NATIVE_UDP_RX;
      desc->udp.endpoint = lauberhorn_eci_host_ctrl_info_native_udp_rx_endpoint_extract(rx_ctrl);
      desc->udp.saddr = lauberhorn_eci_host_ctrl_info_native_udp_rx_saddr_extract(rx_ctrl);
      desc->udp.daddr = lauberhorn_eci_host_ctrl_info_native_udp_rx_daddr_extract(rx_ctrl);
      desc->udp.sport = lauberhorn_eci_host_ctrl_info_native_udp_rx_sport_extract(rx_ctrl);
      desc->udp.dport = lauberhorn_eci_host_ctrl_info_native_udp_rx_dport_extract(rx_ctrl);
      desc->payload_len = 0;
      break;
    case lauberhorn_eci_onc_rpc_call_rx:
      desc->type = TY_ONCRPC_CALL_RX;
      desc->oncrpc_call_rx.func_ptr = (void *)
          lauberhorn_eci_host_ctrl_info_onc_rpc_call_rx_func_ptr_extract(
              rx_ctrl);
      desc->oncrpc_call_rx.request_token =
          lauberhorn_eci_host_ctrl_info_onc_rpc_call_rx_request_token_extract(rx_ctrl);

      // Short RPC arguments retain the hardware decoder's 48-byte padding.

      desc->payload_len = 0;

      break;
    case lauberhorn_eci_onc_rpc_reply_rx:
      desc->type = TY_ONCRPC_REPLY_RX;
      desc->oncrpc_reply_rx.status =
          lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_status_extract(rx_ctrl);
      desc->oncrpc_reply_rx.pid =
          lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_pid_extract(rx_ctrl);
      desc->oncrpc_reply_rx.cookie =
          lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_cookie_extract(
              rx_ctrl);
      desc->oncrpc_reply_rx.xid =
          lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_xid_extract(rx_ctrl);

      desc->payload_len = 0;

      break;
#endif
    default:
      desc->type = TY_ERROR;
      desc->payload_len = 0;
    }

    desc->payload_len = pkt_len;
    ctx->rx_view.data = pkt_len <= LAUBERHORN_ECI_INLINE_DATA_SIZE
        ? rx_ctrl + LAUBERHORN_ECI_INLINE_DATA_OFFSET
        : rx_base + LAUBERHORN_ECI_OVERFLOW_OFFSET;
    ctx->rx_view.length = pkt_len;
    ctx->rx_view.pending = true; /* includes zero-length packets */

  }

  BARRIER; // Complete transport memory accesses before returning

  pr_debug("eci_rx: finished rx\n");

  // Done
  return valid;
}

/* End the software borrow without a control-line read or hardware handshake. */
static inline void core_eci_rx_release(lauberhorn_core_state_t *ctx) {
  BARRIER;
  memset(&ctx->rx_view, 0, sizeof(ctx->rx_view));
}

static inline bool core_eci_rx_acquire(void *base, lauberhorn_core_state_t *ctx,
                                     lauberhorn_pkt_desc_t *desc) {
  return core_eci_rx_acquire_traced(base, ctx, desc, NULL, NULL);
}

/* Local TX result: accepted, definite rejection, or indeterminate error.
 * A submitted descriptor consumes its reservation for all three results. */
enum { CORE_TX_ACCEPTED = 1, CORE_TX_RETRY = 2, CORE_TX_ERROR = 3 };
/* Reservation fixes placement, not actual send length. A short overflow send
 * remains in overflow. No staging allocation and no transport-pointer escape
 * across another commit or assignment change. */
static inline void *core_eci_tx_get_buffer(void *base, lauberhorn_core_state_t *ctx,
                                          size_t capacity) {
  if (ctx->tx_reserved || capacity > LAUBERHORN_MTU) return NULL;
  uint8_t *start = (uint8_t *)base + LAUBERHORN_ECI_TX_BASE;
  uint8_t *control = start + *ctx->tx_next_cl * LAUBERHORN_ECI_CL_SIZE;
  ctx->tx_reserved = true;
  ctx->tx_capacity = capacity;
  ctx->tx_reservation_base = base;
  ++ctx->tx_generation;
  lauberhorn_eci_host_tx_placement_inline_payload_insert(control, capacity <= 64);
  BARRIER;
  return capacity <= 64 ? control + LAUBERHORN_ECI_INLINE_DATA_OFFSET
                       : start + LAUBERHORN_ECI_OVERFLOW_OFFSET;
}

static inline void core_eci_tx_finish(lauberhorn_core_state_t *ctx) {
  BARRIER;
  ctx->tx_reserved = false;
  ctx->tx_reservation_base = NULL;
}

static inline int core_eci_tx_commit(void *base, lauberhorn_core_state_t *ctx,
                               lauberhorn_pkt_desc_t *desc) {
  int tx_result = CORE_TX_ERROR;
  if (!ctx->tx_reserved || ctx->tx_reservation_base != base) return CORE_TX_ERROR;
  size_t requested = desc->payload_len;
#ifdef __KERNEL__
  if (desc->type == TY_BYPASS) {
    if (requested < 14) return CORE_TX_ERROR;
    requested -= 14;
  }
#endif
  if (requested > ctx->tx_capacity) return CORE_TX_ERROR;

  // make sure previous RX/TX actually took effect before we attempt to RX
  BARRIER;

  bool tx_parity = *ctx->tx_next_cl;
  pr_debug("eci_tx: current cacheline ID: %d\n", tx_parity);

  uint8_t *tx_base = (uint8_t *)base + LAUBERHORN_ECI_TX_BASE;
  uint8_t *tx_ctrl = tx_base + tx_parity * LAUBERHORN_ECI_CL_SIZE;

#ifdef __KERNEL__
  uint8_t *copy_from = ctx->tx_buf;
#endif
  size_t payload_len = desc->payload_len;

  switch (desc->type) {
  case TY_EXECUTION_CONTROL:
    lauberhorn_eci_host_ctrl_info_execution_tx_ty_insert(tx_ctrl, lauberhorn_eci_execution_control);
    lauberhorn_eci_host_ctrl_info_execution_tx_len_insert(tx_ctrl, 0);
    lauberhorn_eci_host_ctrl_info_execution_tx_execution_insert(tx_ctrl, desc->execution);
    payload_len = 0;
    break;

#ifdef __KERNEL__
  case TY_BYPASS:
    uint8_t *tx_cmd = tx_ctrl + lauberhorn_eci_host_ctrl_info_bypass_tx_size;

    lauberhorn_eci_host_ctrl_info_bypass_tx_ty_insert(tx_ctrl,
                                                      lauberhorn_eci_bypass);
    if (desc->bypass.header_type != HDR_ETHERNET) {
      pr_err("bypass TX only accepts Ethernet packets; trying to send %s\n",
             lauberhorn_eci_packet_desc_type_describe(desc->type));
      goto out;
    }

    lauberhorn_eci_host_ctrl_info_bypass_tx_hdr_ty_insert(
        tx_ctrl, lauberhorn_eci_hdr_ethernet);

    // Ethernet bypass only takes destination mac and ethertype
    // TODO: use Mackerel datatypes here

    // destination MAC
    memcpy(tx_cmd, copy_from, 6);

    // XXX: not writing source MAC, thus not supporting bridging, forwarding,
    //      etc.

    // EtherType
    memcpy(tx_cmd + 6, copy_from + 12, 2);

    payload_len -= 14;

    // tx bypass len field does not include header
    lauberhorn_eci_host_ctrl_info_bypass_tx_len_insert(tx_ctrl, payload_len);
    break;

#else // ! __KERNEL__
  case TY_NATIVE_UDP_TX:
    lauberhorn_eci_host_ctrl_info_native_udp_tx_ty_insert(tx_ctrl, lauberhorn_eci_native_udp_tx);
    lauberhorn_eci_host_ctrl_info_native_udp_tx_len_insert(tx_ctrl, payload_len);
    lauberhorn_eci_host_ctrl_info_native_udp_tx_execution_insert(tx_ctrl, desc->execution);
    lauberhorn_eci_host_ctrl_info_native_udp_tx_endpoint_insert(tx_ctrl, desc->udp.endpoint);
    lauberhorn_eci_host_ctrl_info_native_udp_tx_daddr_insert(tx_ctrl, desc->udp.daddr);
    lauberhorn_eci_host_ctrl_info_native_udp_tx_dport_insert(tx_ctrl, desc->udp.dport);
    break;

  case TY_ONCRPC_REPLY_TX:
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_execution_insert(tx_ctrl, desc->execution);
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_ty_insert(
        tx_ctrl, lauberhorn_eci_onc_rpc_reply_tx);

    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_status_insert(
        tx_ctrl, desc->oncrpc_reply_tx.status);
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_request_token_insert(
        tx_ctrl, desc->oncrpc_reply_tx.request_token);

    // TX RPC length covers the complete contiguous argument stream
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_len_insert(tx_ctrl,
                                                              payload_len);

    break;

  case TY_ONCRPC_CALL_TX:
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_execution_insert(tx_ctrl, desc->execution);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_ty_insert(
        tx_ctrl, lauberhorn_eci_onc_rpc_call_tx);

    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_pid_insert(
        tx_ctrl, desc->oncrpc_call_tx.pid);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_cookie_insert(
        tx_ctrl, desc->oncrpc_call_tx.cookie);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_xid_insert(
        tx_ctrl, desc->oncrpc_call_tx.xid);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_daddr_insert(
        tx_ctrl, desc->oncrpc_call_tx.daddr);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_sport_insert(
        tx_ctrl, desc->oncrpc_call_tx.sport);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_dport_insert(
        tx_ctrl, desc->oncrpc_call_tx.dport);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_prog_num_insert(
        tx_ctrl, desc->oncrpc_call_tx.prog_num);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_prog_ver_insert(
        tx_ctrl, desc->oncrpc_call_tx.prog_ver);
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_proc_insert(
        tx_ctrl, desc->oncrpc_call_tx.proc_num);

    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_len_insert(tx_ctrl,
                                                             payload_len);

    break;

#endif
  default:
    pr_err("eci_tx: unhandled host request type %d\n", desc->type);
    return CORE_TX_ERROR;
  }

  lauberhorn_eci_host_tx_placement_inline_payload_insert(tx_ctrl,
      desc->type != TY_EXECUTION_CONTROL && ctx->tx_capacity <= 64);
  BARRIER; // make sure all data is written before we ring the doorbell

  // Flip the parity
  *ctx->tx_next_cl = !tx_parity;

  // Ring the doorbell: read next CL to trigger send
  volatile uint8_t *tx_next_ctrl =
      tx_base + !tx_parity * LAUBERHORN_ECI_CL_SIZE;
  tx_result = *tx_next_ctrl;

  pr_debug("eci_tx: finished tx\n");

#ifdef __KERNEL__
out:
#endif
  BARRIER; // Complete transport memory accesses before returning

  core_eci_tx_finish(ctx);
  return tx_result;
}

/* Finish this 2F2F transaction without emitting a packet. Same-domain cache
 * contents may remain stale; ownership handoff performs full cleanup. */
static inline void core_eci_tx_cancel(lauberhorn_core_state_t *ctx) {
  if (!ctx->tx_reserved) return;
  lauberhorn_pkt_desc_t noop = {.type = TY_EXECUTION_CONTROL};
  (void)core_eci_tx_commit(ctx->tx_reservation_base, ctx, &noop);
}

#endif // LAUBERHORN_CORE_ECI_CORE_H
