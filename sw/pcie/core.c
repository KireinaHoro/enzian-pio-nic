#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "api.h"
#include "cmac.h"
#include "diag.h"
#include "hal.h"

#include "config.h"
#include "gen/pionic_pcie.h"
#include "gen/pionic_pcie_core.h"
#include "gen/pionic_pcie_global.h"
#include "regblock_bases.h"

struct pionic_ctx {
  void *bar;
  pionic_pcie_global_t glb_dev;
  pionic_pcie_core_t core_dev[PIONIC_NUM_CORES];
};

#define PIONIC_PKTBUF_OFF_TO_ADDR(off) ((off) + PIONIC_PCIE_PKT_BASE)
#define PIONIC_ADDR_TO_PKTBUF_OFF(addr) ((addr)-PIONIC_PCIE_PKT_BASE)

#define PIONIC_CMAC_BASE 0x200000UL
#define PIONIC_MMAP_END 0x300000UL

int pionic_init(pionic_ctx_t *usr_ctx, const char *dev, bool loopback) {
  int ret = -1;

  pionic_ctx_t ctx = *usr_ctx = malloc(sizeof(struct pionic_ctx));

  char bar_path[64];
  snprintf(bar_path, sizeof(bar_path), "/sys/bus/pci/devices/%s/resource0",
           dev);

  int fd = open(bar_path, O_RDWR);
  if (fd < 0) {
    perror("open resource");
    goto fail;
  }

  ctx->bar =
      mmap(NULL, PIONIC_MMAP_END, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (ctx->bar == MAP_FAILED) {
    perror("mmap resource");
    goto fail;
  }

  close(fd);

  // initialize Mackerel devices
  pionic_pcie_global_initialize(&ctx->glb_dev,
                                ctx->bar + PIONIC_PCIE_GLOBAL_BASE);
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    pionic_pcie_core_initialize(&ctx->core_dev[i],
                                ctx->bar + PIONIC_PCIE_CORE_BASE(i));
  }

  // enable device
  char enable_path[64];
  snprintf(enable_path, sizeof(enable_path), "/sys/bus/pci/devices/%s/enable",
           dev);
  FILE *fp = fopen(enable_path, "w");
  if (!fp) {
    perror("open pcie enable");
    goto fail;
  }
  fputc('1', fp);
  fclose(fp);

  // configure CMAC
  if (start_cmac(ctx, PIONIC_CMAC_BASE, loopback)) {
    printf("Failed to start CMAC\n");
    goto fail;
  }

  // set defaults
  pionic_set_rx_block_cycles(ctx, 200);
  pionic_oncrpc_set_core_mask(ctx, (1 << PIONIC_NUM_CORES) - 1);

  // reset packet buffer allocator
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    pionic_reset_pkt_alloc(ctx, i);
  }

  ret = 0;

fail:
  return ret;
}

void pionic_set_rx_block_cycles(pionic_ctx_t ctx, int cycles) {
  pionic_pcie_global_rx_block_cycles_wr(&ctx->glb_dev, cycles);

  printf("Rx block cycles: %d\n", cycles);
}

void pionic_fini(pionic_ctx_t *usr_ctx) {
  pionic_ctx_t ctx = *usr_ctx;

  // disable CMAC
  stop_cmac(ctx, PIONIC_CMAC_BASE);

  munmap(ctx->bar, PIONIC_MMAP_END);

  *usr_ctx = NULL;
}

bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  // XXX: host rx (unlike host tx) has separate FIFO pop doorbell
  uint8_t *host_rx =
      (uint8_t *)(PIONIC_PCIE_CORE_BASE(cid) + PIONIC_PCIE_CORE_HOST_RX_BASE);

  if (pionic_pcie_host_ctrl_info_error_valid_extract(host_rx)) {
    // parse host control info
    pionic_pcie_host_packet_desc_type_t ty =
        pionic_pcie_host_ctrl_info_error_ty_extract(host_rx);
    uint32_t read_addr = PIONIC_PKTBUF_OFF_TO_ADDR(
        pionic_pcie_host_ctrl_info_error_addr_extract(host_rx));
    uint32_t pkt_len = pionic_pcie_host_ctrl_info_error_size_extract(host_rx);

    // fill out user-facing struct
    desc->payload_buf = (uint8_t *)(ctx->bar) + read_addr;
    desc->payload_len = pkt_len;

    switch (ty) {
    case pionic_pcie_bypass:
      desc->type = TY_BYPASS;

      // decode header type
      switch (pionic_pcie_host_ctrl_info_bypass_hdr_ty_extract(host_rx)) {
      case pionic_pcie_hdr_ethernet:
        desc->bypass.header_type = HDR_ETHERNET;
        break;
      case pionic_pcie_hdr_ip:
        desc->bypass.header_type = HDR_IP;
        break;
      case pionic_pcie_hdr_udp:
        desc->bypass.header_type = HDR_UDP;
        break;
      case pionic_pcie_hdr_onc_rpc_call:
        desc->bypass.header_type = HDR_ONCRPC_CALL;
        break;
      }

      // parsed bypass header is aligned after the descriptor header
      desc->bypass.header = host_rx + pionic_pcie_host_ctrl_info_bypass_size;
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

#ifdef DEBUG
    printf("Got packet at pktbuf %#lx len %#lx\n",
           PIONIC_ADDR_TO_PKTBUF_OFF(read_addr), pkt_len);
#endif
    return true;
  } else {
#ifdef DEBUG
    printf("Did not get packet\n");
#endif
    return false;
  }
}

void pionic_rx_ack(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t read_addr = desc->payload_buf - (uint8_t *)(ctx->bar);

  // valid for host-driven descriptor doesn't actually do anything so we don't
  // bother
  pionic_pcie_core_host_pkt_buf_desc_t reg =
      pionic_pcie_core_host_pkt_buf_desc_size_insert(
          pionic_pcie_core_host_pkt_buf_desc_addr_insert(
              pionic_pcie_core_host_pkt_buf_desc_default,
              PIONIC_ADDR_TO_PKTBUF_OFF(read_addr)),
          desc->payload_len);

  // XXX: write rx ack reg in one go since this reg has FIFO semantics
  pionic_pcie_core_host_rx_ack_wr(&ctx->core_dev[cid], reg);
}

void pionic_tx_get_desc(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  // XXX: read tx reg in one go since this reg has FIFO semantics
  pionic_pcie_core_host_pkt_buf_desc_t reg =
      pionic_pcie_core_host_tx_rd(&ctx->core_dev[cid]);

  // for now, TX desc should always be valid
  assert(pionic_pcie_core_host_pkt_buf_desc_valid_extract(reg));
  uint32_t off = pionic_pcie_core_host_pkt_buf_desc_addr_extract(reg);
  uint32_t len = pionic_pcie_core_host_pkt_buf_desc_size_extract(reg);

  desc->payload_buf = (uint8_t *)(ctx->bar) + PIONIC_PKTBUF_OFF_TO_ADDR(off);
  desc->payload_len = len;

  // set up correct header/args pointers, so that application directly writes
  // into descriptor
  switch (desc->type) {
  case TY_BYPASS:
    desc->bypass.header = host_tx_ack + pionic_pcie_host_ctrl_info_bypass_size;
    break;
  case TY_ONCRPC_CALL:
    desc->bypass.args = host_tx_ack + pionic_pcie_host_ctrl_info_bypass_size;
    break;
  }
}

void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  // XXX: host tx ack (unlike host rx ack) has separate FIFO push doorbell
  uint8_t *host_tx_ack = (uint8_t *)(PIONIC_PCIE_CORE_BASE(cid) +
                                     PIONIC_PCIE_CORE_HOST_TX_ACK_BASE);

  uint64_t write_addr = desc->payload_buf - (uint8_t *)(ctx->bar);

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
