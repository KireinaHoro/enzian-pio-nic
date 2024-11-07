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
  // TODO: write entire descriptor
  // FIXME: the descriptor is bigger than uint64_t!
  uint64_t reg = pionic_pcie_core_host_rx_rd(&ctx->core_dev[cid]);
  if (reg & 0x1) {
    uint64_t hw_desc = reg >> 1;
    uint64_t read_addr =
        PIONIC_PKTBUF_OFF_TO_ADDR(hw_desc & PIONIC_PKT_BUF_ADDR_MASK);
    uint64_t pkt_len =
        (hw_desc >> PIONIC_PKT_BUF_ADDR_WIDTH) & PIONIC_PKT_BUF_LEN_MASK;
    desc->payload_buf = (uint8_t *)(ctx->bar) + read_addr;
    desc->payload_len = pkt_len;
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
  uint64_t reg =
      (PIONIC_ADDR_TO_PKTBUF_OFF(read_addr) & PIONIC_PKT_BUF_ADDR_MASK) |
      ((desc->payload_len & PIONIC_PKT_BUF_LEN_MASK)
       << PIONIC_PKT_BUF_ADDR_WIDTH);

  // TODO: write entire descriptor
  pionic_pcie_core_host_rx_ack_wr(&ctx->core_dev[cid], reg);
}

void pionic_tx_get_desc(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = pionic_pcie_core_host_tx_rd(&ctx->core_dev[cid]);
  uint64_t hw_desc = reg >> 1;

  desc->payload_buf =
      (uint8_t *)(ctx->bar) +
      PIONIC_PKTBUF_OFF_TO_ADDR(hw_desc & PIONIC_PKT_BUF_ADDR_MASK);
  desc->payload_len =
      (hw_desc >> PIONIC_PKT_BUF_ADDR_WIDTH) & PIONIC_PKT_BUF_LEN_MASK;
}

void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  // TODO: write entire descriptor
  pionic_pcie_core_host_tx_ack_wr(&ctx->core_dev[cid], desc->payload_len);
}
