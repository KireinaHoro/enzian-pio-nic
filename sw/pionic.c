#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

#include "pionic.h"

static inline void write64(pionic_ctx_t *ctx, uint64_t addr, uint64_t reg) {
  ((volatile uint64_t *)ctx->bar)[addr / 8] = reg;
}

static inline uint64_t read64(pionic_ctx_t *ctx, uint64_t addr) {
  return ((volatile uint64_t *)ctx->bar)[addr / 8];
}

static inline void write32(pionic_ctx_t *ctx, uint64_t addr, uint32_t reg) {
  ((volatile uint32_t *)ctx->bar)[addr / 4] = reg;
}

static inline uint32_t read32(pionic_ctx_t *ctx, uint64_t addr) {
  return ((volatile uint32_t *)ctx->bar)[addr / 4];
}

int pionic_init(pionic_ctx_t *ctx, const char *dev, bool loopback) {
  int ret = -1;

  char bar_path[64];
  snprintf(bar_path, sizeof(bar_path), "/sys/bus/pci/devices/%s/resource0", dev);

  int fd = open(bar_path, O_RDWR);
  if (fd < 0) {
    perror("open resource");
    goto fail;
  }

  ctx->bar = mmap(NULL, PIONIC_MMAP_END, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (ctx->bar == MAP_FAILED) {
    perror("mmap resource");
    goto fail;
  }

  close(fd);

  // enable device
  char enable_path[64];
  snprintf(enable_path, sizeof(enable_path), "/sys/bus/pci/devices/%s/enable", dev);
  FILE *fp = fopen(enable_path, "w");
  if (!fp) {
    perror("open pcie enable");
    goto fail;
  }
  fputc('1', fp);
  fclose(fp);

  // configure CMAC
  uint32_t status;

  write32(ctx, PIONIC_CMAC_REG(PM_RX_REG1), 1); // ctl_rx_enable
  write32(ctx, PIONIC_CMAC_REG(PM_TX_REG1), 0x10); // ctl_tx_send_rfi

  while (true) {
    write32(ctx, PIONIC_CMAC_REG(PM_TICK_REG), 1);
    status = read32(ctx, PIONIC_CMAC_REG(PM_STAT_RX_STATUS_REG));
    if (status & 2) break; // RX_aligned
    usleep(1000);
  }

  write32(ctx, PIONIC_CMAC_REG(PM_TX_REG1), 1); // ctl_tx_enable, !ctl_tx_send_rfi

  // FIXME: why do we need this?
  while (true) {
    write32(ctx, PIONIC_CMAC_REG(PM_TICK_REG), 1);
    status = read32(ctx, PIONIC_CMAC_REG(PM_STAT_RX_STATUS_REG));
    if (status == 3) break; // RX_aligned && RX_status
    usleep(1000);
  }

  write32(ctx, PIONIC_CMAC_REG(PM_GT_LOOPBACK_REG), loopback);

  printf("Loopback enabled: %s\n", loopback ? "true" : "false");

  // flow control disabled - skipping regs

  // set defaults
  pionic_set_rx_block_cycles(ctx, 200);
  pionic_set_core_mask(ctx, (1 << PIONIC_NUM_CORES) - 1);

  ret = 0;

fail:
  return ret;
}

void pionic_set_rx_block_cycles(pionic_ctx_t *ctx, int cycles) {
  write32(ctx, PIONIC_GLB_RX_BLOCK_CYCLES, cycles);

  printf("Rx block cycles: %d\n", cycles);
}

void pionic_set_core_mask(pionic_ctx_t *ctx, uint64_t mask) {
  write64(ctx, PIONIC_GLB_DISPATCH_MASK, mask);

  printf("Dispatcher mask: %#lx\n", mask);
}

void pionic_fini(pionic_ctx_t *ctx) {
  // disable CMAC
  write32(ctx, PIONIC_CMAC_REG(PM_RX_REG1), 0); // !ctl_rx_enable
  write32(ctx, PIONIC_CMAC_REG(PM_TX_REG1), 0); // !ctl_tx_enable

  munmap(ctx->bar, PIONIC_MMAP_END);
}

bool pionic_rx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = read64(ctx, PIONIC_CORE_REG(cid, PC_RX_NEXT));
  if (reg & 0x1) {
    uint32_t hw_desc = reg >> 1;
    desc->buf = (uint8_t *)(ctx->bar) + PIONIC_PKTBUF(hw_desc & PKT_ADDR_MASK);
    desc->len = (hw_desc >> PKT_ADDR_WIDTH) & PKT_LEN_MASK;
    return true;
  } else {
    return false;
  }
}

bool pionic_rx_ack(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = ((desc->buf - (uint8_t *)(ctx->bar)) & PKT_ADDR_MASK) | ((desc->len & PKT_LEN_MASK) << PKT_ADDR_WIDTH);

  write64(ctx, PIONIC_CORE_REG(cid, PC_RX_NEXT_ACK), reg);
}

void pionic_tx_get_desc(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = read64(ctx, PIONIC_CORE_REG(cid, PC_TX));
  uint64_t buf_off = reg & PKT_ADDR_MASK;

  desc->buf = (uint8_t *)(ctx->bar) + PIONIC_PKTBUF(reg & PKT_LEN_MASK);
  desc->len = (reg >> PKT_ADDR_WIDTH) & PKT_LEN_MASK;
}

void pionic_tx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  write64(ctx, PIONIC_CORE_REG(cid, PC_TX_ACK), desc->len);
}

void dump_glb_stats(pionic_ctx_t *ctx) {
#define READ_PRINT(name) printf("%s\t: %#lx\n", #name, read64(ctx, name));
  READ_PRINT(PIONIC_GLB_RX_OVERFLOW_COUNT)
#undef READ_PRINT
}

void dump_stats(pionic_ctx_t *ctx, int cid) {
#define READ_PRINT(name) printf("core %d: %s\t: %#lx\n", cid, #name, read64(ctx, PIONIC_CORE_REG(cid, name)));
  READ_PRINT(PC_STAT_RX_COUNT)
  READ_PRINT(PC_STAT_TX_COUNT)
  READ_PRINT(PC_STAT_RX_DMA_ERR_COUNT)
  READ_PRINT(PC_STAT_TX_DMA_ERR_COUNT)
  READ_PRINT(PC_STAT_RX_ALLOC_OCCUPANCY_0)
  READ_PRINT(PC_STAT_RX_ALLOC_OCCUPANCY_1)
#undef READ_PRINT
}
