// Core Data Exchange paths using ECI
// All functions here operates on mapped pages and should be inlined to rt/usr

#ifndef __PIONIC_CORE_ECI_H__
#define __PIONIC_CORE_ECI_H__

#include "rt-common.h"
#include <cstddef>
#include <cstdint>

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

// buf must be large enough to hold the maximum packet (PIONIC_MTU)
static bool core_eci_rx(void *base, pionic_pkt_desc_t *desc) {

  // make sure previous RX/TX actually took effect before we attempt to RX
  // TODO: is this needed?
  BARRIER

  uint64_t worker_ctrl_addr = (uint64_t)base + PIONIC_ECI_WORKER_CTRL_INFO_BASE;
  
  pr_debug("eci_rx: waiting for READY\n");
  // XXX: mackerel functions don't use volatile?
  while (!pionic_eci_host_worker_ctrl_ready_extract(worker_ctrl_addr));


  pionic_eci_host_worker_ctrl_busy_insert(worker_ctrl_addr, 1);
  BARRIER  // make sure BUSY actually took effect
critical_section_start:
  pr_debug("eci_rx: entered critical section\n");

  bool rx_parity = pionic_eci_host_worker_ctrl_rx_parity_extract(worker_ctrl_addr);
  // TODO: @JS please check if saving the parity to local variable **here** is OK
  pr_debug("pionic_rx: current cacheline ID: %d\n", rx_parity);

  uint64_t rx_base = (uint64_t)base + PIONIC_ECI_RX_BASE + rx_parity * PIONIC_ECI_CL_SIZE;

  bool valid = pionic_eci_host_ctrl_info_error_valid_extract(rx_base);
  BARRIER  // make sure the CL is actually read

  // always toggle CL
  pionic_eci_host_worker_ctrl_rx_parity_insert(worker_ctrl_addr, !rx_parity);

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

    // TODO: @PX does pkt_len excludes the decoded hdr/args that directly follows the ctrl_info?
    if (pkt_len == 0) {
      desc->payload_buf = NULL;
      desc->payload_len = 0;
    } else {
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

  pionic_eci_host_worker_ctrl_busy_insert(worker_ctrl_addr, 0);
  BARRIER  // make sure !BUSY actually took effect
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
  // TODO: slow? Alternative: fixed per-thread buffer, but disallow coroutines in one thread
  desc->payload_buf = malloc(PIONIC_MTU);
  desc->payload_len = PIONIC_MTU;
}

static void core_eci_tx(void *base, pionic_pkt_desc_t *desc) {
  // FIXME: to be adapted like rx

  uint64_t tx_base = PIONIC_ECI_TX_BASE + cid * PIONIC_ECI_CORE_OFFSET;
  bool *next_cl = &ctx->core_states[cid].tx_next_cl;

#ifdef DEBUG
  printf("pionic_tx: next cacheline ID: %d\n", *next_cl);
#endif

  uint64_t pkt_len = desc->len;

  // write packet length to control
  write64_fpgamem(ctx, *next_cl * 0x80 + tx_base, pkt_len);

  // copy from prepared buffer
  int first_write_size = pkt_len > 64 ? 64 : pkt_len;
  copy_to_fpgamem(ctx, *next_cl * 0x80 + 0x40 + tx_base, desc->buf,
                  first_write_size);
  if (pkt_len > 64) {
    copy_to_fpgamem(ctx, tx_base + PIONIC_ECI_OVERFLOW_OFFSET, desc->buf + 64,
                    pkt_len - 64);
  }

  *next_cl ^= true;

  // make sure packet data actually hit L2, before the FPGA invalidates
  BARRIER

  // trigger actual sending by doing a dummy read on the next cacheline
  read64_fpgamem(ctx, *next_cl * 0x80 + tx_base);

  // make sure TX actually took effect before e.g. we attempt to RX
  // make sure next_cl tracking is not out of sync due to reordering
  BARRIER
}

#endif  // __PIONIC_CORE_ECI_H__