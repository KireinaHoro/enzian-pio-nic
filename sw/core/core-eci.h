// Core Data Exchange paths using ECI
// All functions here operates on mapped pages and should be inlined to rt/usr

#ifndef __PIONIC_CORE_ECI_H__
#define __PIONIC_CORE_ECI_H__

#include "debug.h"
#include "rt-common.h"
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <stdint.h>

#define __PIONIC_RT__
#include "pionic.h"  // get pionic_pkt_desc_t etc. (but not other usr functions)

// mackerel
// provide pionic_eci_*, pionic_eci_host_*, pionic_eci_core_*,
#include "gen/pionic_eci.h"
#include "gen/pionic_eci_core.h"
#include "gen/pionic_eci_global.h"

// generated hw headers
#include "config.h"
#include "regblock_bases.h"


// TODO: @PX please move these to the generated header --ZL
#define PIONIC_ECI_CL_SIZE (0x80)
#define PIONIC_ECI_INLINE_DATA_OFFSET (0x40)
#define PIONIC_ECI_INLINE_DATA_SIZE (PIONIC_ECI_CL_SIZE - PIONIC_ECI_INLINE_DATA_OFFSET)
#define PIONIC_ECI_WORKER_CTRL_INFO_BASE (0xFF80)  // currently last CL in the region PIONIC_ECI_CORE_OFFSET

// mapped pages layout:
//
// PIONIC_ECI_RX_BASE                                   ECI host control info for RX - host_ctrl_info_*_t
//                    + PIONIC_ECI_INLINE_DATA_OFFSET   RX inline data
// PIONIC_ECI_RX_BASE + PIONIC_ECI_CL_SIZE              the other RX CL
// PIONIC_ECI_RX_BASE + PIONIC_ECI_OVERFLOW_OFFSET      RX overflow cachelines (count: PIONIC_ECI_NUM_OVERFLOW_CL)
//
// PIONIC_ECI_TX_BASE                                   ECI host control info for TX - host_ctrl_info_*_t
//                    + PIONIC_ECI_INLINE_DATA_OFFSET   RX inline data
// PIONIC_ECI_TX_BASE + PIONIC_ECI_CL_SIZE              the other TX CL
// PIONIC_ECI_TX_BASE + PIONIC_ECI_OVERFLOW_OFFSET      TX overflow cachelines (count: PIONIC_ECI_NUM_OVERFLOW_CL)
// 
// PIONIC_ECI_WORKER_CTRL_INFO_BASE                     current worker ctrl_info
//
// until PIONIC_ECI_CORE_OFFSET                         the end of mapped region
//
STATIC_ASSERT(PIONIC_ECI_OVERFLOW_OFFSET >= PIONIC_ECI_CL_SIZE * 2, "overlapped");
STATIC_ASSERT(PIONIC_ECI_TX_BASE >= PIONIC_ECI_OVERFLOW_OFFSET + PIONIC_ECI_NUM_OVERFLOW_CL * PIONIC_ECI_CL_SIZE, "overlapped");
STATIC_ASSERT(PIONIC_ECI_WORKER_CTRL_INFO_BASE >= PIONIC_ECI_TX_BASE + PIONIC_ECI_OVERFLOW_OFFSET + PIONIC_ECI_NUM_OVERFLOW_CL * PIONIC_ECI_CL_SIZE, "overlapped");
STATIC_ASSERT(PIONIC_ECI_CORE_OFFSET >= PIONIC_ECI_WORKER_CTRL_INFO_BASE + PIONIC_ECI_CL_SIZE, "overlapped");


#define BARRIER asm volatile("dmb sy\nisb");
#define COMPARE_AND_SWAP __sync_val_compare_and_swap
#define FETCH_AND_AND __sync_fetch_and_and

static bool core_eci_rx(void *base, volatile bool *rx_parity_ptr, pionic_pkt_desc_t *desc) {

  // make sure previous RX/TX actually took effect before we attempt to RX
  BARRIER

  uint64_t worker_ctrl_addr = (uint64_t)base + PIONIC_ECI_WORKER_CTRL_INFO_BASE;
  
  pr_debug("eci_rx: waiting for READY and setting BUSY\n");
  while (!COMPARE_AND_SWAP((uint8_t *)worker_ctrl_addr, (uint8_t)0b01, (uint8_t)0b11))
  BARRIER  // make sure BUSY actually took effect
critical_section_start:
  pr_debug("eci_rx: entered critical section\n");

  bool rx_parity = *rx_parity_ptr;
  pr_debug("eci_rx: current cacheline ID: %d\n", rx_parity);

  uint64_t rx_base = (uint64_t)base + PIONIC_ECI_RX_BASE + rx_parity * PIONIC_ECI_CL_SIZE;

  bool valid = pionic_eci_host_ctrl_info_error_valid_extract(rx_base);
  BARRIER  // make sure the CL is actually read

  // always toggle CL
  *rx_parity_ptr = !rx_parity;

  // decode the packet
  if (!valid) {
    pr_debug("eci_rx: did not get a packet\n");
  } else {

    size_t pkt_len = pionic_eci_host_ctrl_info_error_len_extract(rx_base);
    pr_debug("eci_rx: got a packet with len %#lx\n", pkt_len);

    pionic_eci_host_packet_desc_type_t ty =
        pionic_eci_host_ctrl_info_error_ty_extract(rx_base);

    switch (ty) {
    case pionic_eci_bypass:
      desc->type = TY_BYPASS;

      // decode header type
      switch (pionic_eci_host_ctrl_info_bypass_hdr_ty_extract(rx_base)) {
      case pionic_eci_hdr_ethernet:
        desc->bypass.header_type = HDR_ETHERNET;
        break;
      case pionic_eci_hdr_ip:
        desc->bypass.header_type = HDR_IP;
        break;
      case pionic_eci_hdr_udp:
        desc->bypass.header_type = HDR_UDP;
        break;
      case pionic_eci_hdr_onc_rpc_call:
        desc->bypass.header_type = HDR_ONCRPC_CALL;
        break;
      }

      // parsed bypass header is aligned after the descriptor header
      // XXX: we don't have the actual size of the header, copy maximum
      memcpy(desc->bypass.header, host_rx + pionic_eci_host_ctrl_info_bypass_size, sizeof(desc->bypass.header));
      
      break;
  
    case pionic_eci_onc_rpc_call:
      desc->type = TY_ONCRPC_CALL;
      desc->oncrpc_call.func_ptr =
          (void *)pionic_eci_host_ctrl_info_onc_rpc_call_func_ptr_extract(
              host_rx);
      desc->oncrpc_call.xid =
          pionic_eci_host_ctrl_info_onc_rpc_call_xid_extract(host_rx);

      // parsed oncrpc arguments are aligned after the descriptor header
      // XXX: we don't have the actual count of args, copy maximum
      memcpy(desc->oncrpc_call.args, host_rx + pionic_eci_host_ctrl_info_onc_rpc_call_size, sizeof(desc->oncrpc_call.args));

      break;

    default:
      desc->type = TY_ERROR;
    }

    if (pkt_len == 0) {
      desc->payload_buf = NULL;
      desc->payload_len = 0;
    } else {
      pr_debug("eci_rx: extra payload buffer allocated, len = %d\n", pkt_len);
      desc->payload_buf = malloc(pkt_len);
      desc->payload_len = pkt_len;
      
      int first_read_size = pkt_len > PIONIC_ECI_INLINE_DATA_SIZE ? PIONIC_ECI_INLINE_DATA_SIZE : pkt_len;
      memcpy(desc->payload_buf, rx_base + PIONIC_ECI_INLINE_DATA_OFFSET, first_read_size);
      if (pkt_len > PIONIC_ECI_INLINE_DATA_SIZE) {
        memcpy(desc->payload_buf + PIONIC_ECI_INLINE_DATA_SIZE, rx_base + PIONIC_ECI_OVERFLOW_OFFSET, pkt_len - PIONIC_ECI_INLINE_DATA_SIZE);
      }
    }

    // All data in sofware buf, good to exit the critical section
  }

  BARRIER  // make sure !BUSY comes after
  assert(FETCH_AND_AND((uint8_t *)worker_ctrl_addr, (uint8_t)0b11111101) & 0b10 != 0, "was not in the critical section?");
critical_section_end:
  pr_debug("eci_rx: exited critical section\n");
    
  // Done
  return valid;
}

static void core_eci_rx_ack(pionic_pkt_desc_t *desc) {
  if (desc->payload_buf != NULL) {
    free(desc->payload_buf);
    desc->payload_buf = NULL;
  } else {
    // Do nothing!
  }
}

static void core_eci_tx_prepare_desc(void *base, pionic_pkt_desc_t *desc) {
  // ECI backend: do not allocate payload_buf
  desc->payload_buf = NULL;
  desc->payload_len = 0;
}

static void core_eci_tx(void *base, volatile bool *tx_parity_ptr, pionic_pkt_desc_t *desc) {
  // ECI backend: do not release payload_buf
  
  // make sure previous RX/TX actually took effect before we attempt to RX
  BARRIER

  uint64_t worker_ctrl_addr = (uint64_t)base + PIONIC_ECI_WORKER_CTRL_INFO_BASE;
  
  pr_debug("eci_tx: waiting for READY and setting BUSY\n");
  while (!COMPARE_AND_SWAP((uint8_t *)worker_ctrl_addr, (uint8_t)0b01, (uint8_t)0b11))
  BARRIER  // make sure BUSY actually took effect
critical_section_start:
  pr_debug("eci_tx: entered critical section\n");

  bool tx_parity = *tx_parity_ptr;
  pr_debug("eci_tx: current cacheline ID: %d\n", tx_parity);

  uint64_t tx_base = (uint64_t)base + PIONIC_ECI_TX_BASE + tx_parity * PIONIC_ECI_CL_SIZE;
  
  switch (desc->type) {
  case TY_BYPASS:
    pionic_eci_host_ctrl_info_error_ty_insert(tx_base, pionic_eci_bypass);
    switch (desc->bypass.header_type) {
    case HDR_ETHERNET:
      pionic_eci_host_ctrl_info_bypass_hdr_ty_insert(tx_base, pionic_eci_hdr_ethernet);
      break;
    case HDR_IP::
      pionic_eci_host_ctrl_info_bypass_hdr_ty_insert(tx_base, pionic_eci_hdr_ip);
      break;
    case HDR_UDP:
      pionic_eci_host_ctrl_info_bypass_hdr_ty_insert(tx_base, pionic_eci_hdr_udp);
      break;
    case HDR_ONCRPC_CALL:
      pionic_eci_host_ctrl_info_bypass_hdr_ty_insert(tx_base, pionic_eci_hdr_onc_rpc_call);
      break;
    }
    memcpy(host_tx + pionic_eci_host_ctrl_info_bypass_size, desc->bypass.header, sizeof(desc->bypass.header));
    break;
  
  case TY_ONCRPC_CALL;
    pionic_eci_host_ctrl_info_error_ty_insert(tx_base, pionic_eci_onc_rpc_reply);
    pionic_eci_host_ctrl_info_onc_rpc_call_func_ptr_insert(
      tx_base, desc->oncrpc_call.func_ptr);
    pionic_eci_host_ctrl_info_onc_rpc_call_xid_insert(tx_base,
                                                      desc->oncrpc_call.xid);
    break;
  
    memcpy(host_tx + pionic_eci_host_ctrl_info_onc_rpc_call_size, desc->oncrpc_call.args, sizeof(desc->oncrpc_call.args));
    break;

  case TY_ONCRPC_REPLY:
    pionic_eci_host_ctrl_info_onc_rpc_reply_ty_insert(tx_base, pionic_eci_onc_rpc_reply);
    memcpy(tx_base + pionic_eci_host_ctrl_info_onc_rpc_reply_size, desc->oncrpc_reply.buf, sizeof(desc->oncrpc_reply.buf));
    break;
  
  default:
    pr_err("eci_tx: unhandled desc->type %d\n", desc->type);
    break;
  }
  

  if (desc->payload_buf != NULL) {
    int first_read_size = min(PIONIC_ECI_INLINE_DATA_SIZE, desc->payload_size);
    memcpy((void *)(host_tx + PIONIC_ECI_INLINE_DATA_OFFSET), desc->payload_buf, first_read_size);
    if (desc->payload_size > PIONIC_ECI_INLINE_DATA_SIZE) {
      // XXX: user can overflow the overflow CLs, better give an error
      memcpy(tx_base + PIONIC_ECI_OVERFLOW_OFFSET, desc->payload_buf + PIONIC_ECI_INLINE_DATA_SIZE, desc->payload_size - PIONIC_ECI_INLINE_DATA_SIZE);
    }
  }

  BARRIER  // make sure all data is written before we ring the doorbell

  Fetch the other tx cL

  // Flip the parity
  *tx_parity_ptr = !tx_parity;

  // Ring the doorbell
  // TODO: read or prefetch?
  (void) *((uint8_t *)((uint64_t)base + PIONIC_ECI_TX_BASE + !tx_parity * PIONIC_ECI_CL_SIZE))


  BARRIER  // make sure !BUSY comes after
  assert(FETCH_AND_AND((uint8_t *)worker_ctrl_addr, (uint8_t)0b11111101) & 0b10 != 0, "was not in the critical section?");
critical_section_end:
  pr_debug("eci_tx: exited critical section\n");
}

#endif  // __PIONIC_CORE_ECI_H__