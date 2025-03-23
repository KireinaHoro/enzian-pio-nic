// Core data exchange paths using PCIe
// All functions here operates on mapped pages and should be inlined to rt/usr

// XXX: PCIe worker (core) pages can be mapped to individual threads, but data
//      addr can point to anywhere in bar - currently we assume the whole bar
//      space is mapped for payload data access...

#ifndef __PIONIC_CORE_PCIE_H__
#define __PIONIC_CORE_PCIE_H__

#include "rt-common.h"

#define __PIONIC_RT__
#include "pionic.h"  // get pionic_pkt_desc_t etc. (but not other usr functions)

// mackerel
// provide pionic_pcie_*, pionic_pcie_host_*, pionic_pcie_core_*,
#include "gen/pionic_pcie.h"
#include "gen/pionic_pcie_core.h"
#include "gen/pionic_pcie_global.h"

// generated hw headers
#include "config.h"
#include "regblock_bases.h"

#define PIONIC_PKTBUF_OFF_TO_ADDR(off) ((off) + PIONIC_PCIE_PKT_BASE)
#define PIONIC_ADDR_TO_PKTBUF_OFF(addr) ((addr) - PIONIC_PCIE_PKT_BASE)

static bool core_pcie_rx(void *bar, pionic_pcie_core_t *core_dev, pionic_pkt_desc_t *desc) {
  // XXX: host rx (unlike host tx) has separate FIFO pop doorbell
  uint8_t *host_rx = (uint8_t *)core_dev->base + PIONIC_PCIE_CORE_HOST_RX_BASE;

  if (pionic_pcie_host_ctrl_info_error_valid_extract(host_rx)) {
    // parse host control info
    pionic_pcie_host_packet_desc_type_t ty =
        pionic_pcie_host_ctrl_info_error_ty_extract(host_rx);
    uint32_t read_addr = PIONIC_PKTBUF_OFF_TO_ADDR(
        pionic_pcie_host_ctrl_info_error_addr_extract(host_rx));
    uint32_t pkt_len = pionic_pcie_host_ctrl_info_error_size_extract(host_rx);

    // fill out user-facing struct
    desc->payload_buf = (uint8_t *)bar + read_addr;
    desc->payload_len = pkt_len;
    // payload_buf and payload_len will be offseted below

    switch (ty) {
    case pionic_pcie_bypass:
      desc->type = TY_BYPASS;

      // parsed bypass header is aligned after the descriptor header
      // FIXME: @PX is this what you want?
      desc->bypass.header = host_rx + pionic_pcie_host_ctrl_info_bypass_size;

      // decode header type
      switch (pionic_pcie_host_ctrl_info_bypass_hdr_ty_extract(host_rx)) {
      case pionic_pcie_hdr_ethernet:
        desc->bypass.header_type = HDR_ETHERNET;
        desc->payload_buf += HDR_ETHERNET_SIZE;
        desc->payload_len -= HDR_ETHERNET_SIZE;
        break;
      case pionic_pcie_hdr_ip:
        desc->bypass.header_type = HDR_IP;
        desc->payload_buf += HDR_IP_SIZE;
        desc->payload_len -= HDR_IP_SIZE;
        break;
      case pionic_pcie_hdr_udp:
        desc->bypass.header_type = HDR_UDP;
        desc->payload_buf += HDR_UDP_SIZE;
        desc->payload_len -= HDR_UDP_SIZE;
        break;
      case pionic_pcie_hdr_onc_rpc_call:
        desc->bypass.header_type = HDR_ONCRPC_CALL;
        desc->payload_buf += HDR_ONC_RPC_CALL_SIZE;
        desc->payload_len -= HDR_ONC_RPC_CALL_SIZE;
        break;
      }

      
      break;

    case pionic_pcie_onc_rpc_call:
      desc->type = TY_ONCRPC_CALL;
      desc->oncrpc_call.func_ptr =
          (void *)pionic_pcie_host_ctrl_info_onc_rpc_call_func_ptr_extract(
              host_rx);
      desc->oncrpc_call.xid =
          pionic_pcie_host_ctrl_info_onc_rpc_call_xid_extract(host_rx);

      // parsed oncrpc arguments are aligned after the descriptor header
      desc->oncrpc_call.args =
          (uint32_t *)(host_rx + pionic_pcie_host_ctrl_info_onc_rpc_call_size);
      break;

    default:
      desc->type = TY_ERROR;
    }

    pr_debug("Got packet at pktbuf %#lx len %#lx\n",
           PIONIC_ADDR_TO_PKTBUF_OFF(read_addr), pkt_len);
    return true;
  } else {
    pr_debug("Did not get packet\n");
    return false;
  }
}

static void core_pcie_rx_ack(void *bar, pionic_pcie_core_t *core_dev, pionic_pkt_desc_t *desc) {
  uint64_t read_addr = desc->payload_buf - (uint8_t *)bar;

  // valid for host-driven descriptor doesn't actually do anything so we don't
  // bother
  pionic_pcie_core_host_pkt_buf_desc_t reg =
      pionic_pcie_core_host_pkt_buf_desc_size_insert(
          pionic_pcie_core_host_pkt_buf_desc_addr_insert(
              pionic_pcie_core_host_pkt_buf_desc_default,
              PIONIC_ADDR_TO_PKTBUF_OFF(read_addr)),
          desc->payload_len);

  // XXX: write rx ack reg in one go since this reg has FIFO semantics
  pionic_pcie_core_host_rx_ack_wr(core_dev, reg);
}

static void core_pcie_tx_prepare_desc(void *bar, pionic_pcie_core_t *core_dev, pionic_pkt_desc_t *desc) {
  // XXX: read tx reg in one go since this reg has FIFO semantics
  pionic_pcie_core_host_pkt_buf_desc_t reg =
      pionic_pcie_core_host_tx_rd(core_dev);

  // for now, TX desc should always be valid
  assert(pionic_pcie_core_host_pkt_buf_desc_valid_extract(reg));
  uint32_t off = pionic_pcie_core_host_pkt_buf_desc_addr_extract(reg);
  uint32_t len = pionic_pcie_core_host_pkt_buf_desc_size_extract(reg);

  desc->payload_buf = (uint8_t *)bar + PIONIC_PKTBUF_OFF_TO_ADDR(off);
  desc->payload_len = len;

  // set up correct header/args pointers, so that application directly writes
  // into descriptor
  switch (desc->type) {
  case TY_BYPASS:
    // FIXME: host_tx_ack? where is the backing storage of header buf?
    desc->bypass.header = host_tx_ack + pionic_pcie_host_ctrl_info_bypass_size;
    desc->bypass.args = NULL;
    break;
  case TY_ONCRPC_CALL:
    desc->bypass.header = NULL;
    desc->bypass.args = host_tx_ack + pionic_pcie_host_ctrl_info_bypass_size;
    break;
  }
}

static void core_pcie_tx(void *bar, pionic_pcie_core_t *core_dev, pionic_pkt_desc_t *desc) {
  // XXX: host tx ack (unlike host rx ack) has separate FIFO push doorbell
  uint8_t *host_tx_ack = (uint8_t *)(PIONIC_PCIE_CORE_BASE(cid) +
                                     PIONIC_PCIE_CORE_HOST_TX_ACK_BASE);

  uint64_t write_addr = desc->payload_buf - (uint8_t *)bar;

  // fill out packet buffer desc
  pionic_pcie_host_ctrl_info_error_addr_insert(
      host_tx_ack, PIONIC_ADDR_TO_PKTBUF_OFF(write_addr));
  pionic_pcie_host_ctrl_info_error_size_insert(host_tx_ack, desc->payload_len);

  // valid doesn't matter, not setting

  switch (desc->type) {
  case TY_BYPASS:
    pionic_pcie_host_ctrl_info_bypass_ty_insert(host_tx_ack,
                                                pionic_pcie_bypass);

    // encode header type
    switch (desc->bypass.header_type) {
    case HDR_ETHERNET:
      pionic_pcie_host_ctrl_info_bypass_hdr_ty_insert(host_tx_ack,
                                                      pionic_pcie_hdr_ethernet);
      break;
    case HDR_IP:
      pionic_pcie_host_ctrl_info_bypass_hdr_ty_insert(host_tx_ack,
                                                      pionic_pcie_hdr_ip);
      break;
    case HDR_UDP:
      pionic_pcie_host_ctrl_info_bypass_hdr_ty_insert(host_tx_ack,
                                                      pionic_pcie_hdr_udp);
      break;
    case HDR_ONCRPC_CALL:
      pionic_pcie_host_ctrl_info_bypass_hdr_ty_insert(
          host_tx_ack, pionic_pcie_hdr_onc_rpc_call);
      break;
    }
    break;

  case TY_ONCRPC_CALL:
    pionic_pcie_host_ctrl_info_onc_rpc_call_ty_insert(host_tx_ack,
                                                      pionic_pcie_onc_rpc_call);
    pionic_pcie_host_ctrl_info_onc_rpc_call_func_ptr_insert(
        host_tx_ack, desc->oncrpc_call.func_ptr);
    pionic_pcie_host_ctrl_info_onc_rpc_call_xid_insert(host_tx_ack,
                                                       desc->oncrpc_call.xid);
    break;

  default:
  }

  // ring doorbell: tx packet ready
  // TODO
}


#endif  // __PIONIC_CORE_PCIE_H__