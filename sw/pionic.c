#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

#include "pionic.h"

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

  // reset packet buffer allocator
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    pionic_reset_pkt_alloc(ctx, i);
  }

  ret = 0;

fail:
  return ret;
}

void pionic_set_rx_block_cycles(pionic_ctx_t *ctx, int cycles) {
  write32(ctx, PIONIC_GLOBAL_CTRL, cycles);

  printf("Rx block cycles: %d\n", cycles);
}

void pionic_set_core_mask(pionic_ctx_t *ctx, uint64_t mask) {
  write64(ctx, PIONIC_GLOBAL_DISPATCH_MASK, mask);

  printf("Dispatcher mask: %#lx\n", mask);
}

void pionic_fini(pionic_ctx_t *ctx) {
  // disable CMAC
  write32(ctx, PIONIC_CMAC_REG(PM_RX_REG1), 0); // !ctl_rx_enable
  write32(ctx, PIONIC_CMAC_REG(PM_TX_REG1), 0); // !ctl_tx_enable

  munmap(ctx->bar, PIONIC_MMAP_END);
}

bool pionic_rx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = read64(ctx, PIONIC_CONTROL_HOST_RX_NEXT(cid));
  if (reg & 0x1) {
    uint32_t hw_desc = reg >> 1;
    uint64_t pktbuf_off = PIONIC_PKTBUF(hw_desc & PIONIC_PKT_ADDR_MASK);
    uint64_t pkt_len = (hw_desc >> PIONIC_PKT_ADDR_WIDTH) & PIONIC_PKT_LEN_MASK;
    desc->buf = (uint8_t *)(ctx->bar) + pktbuf_off;
    desc->len = pkt_len;
#ifdef DEBUG
    printf("Got packet at pktbuf %#lx len %#lx\n", pktbuf_off, pkt_len);
#endif
    return true;
  } else {
#ifdef DEBUG
    printf("Did not get packet\n");
#endif
    return false;
  }
}

void pionic_rx_ack(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = ((desc->buf - (uint8_t *)(ctx->bar)) & PIONIC_PKT_ADDR_MASK) | ((desc->len & PIONIC_PKT_LEN_MASK) << PIONIC_PKT_ADDR_WIDTH);

  write64(ctx, PIONIC_CONTROL_HOST_RX_NEXT_ACK(cid), reg);
}

void pionic_tx_get_desc(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = read64(ctx, PIONIC_CONTROL_HOST_TX(cid));
  uint32_t hw_desc = reg >> 1;

  desc->buf = (uint8_t *)(ctx->bar) + PIONIC_PKTBUF(hw_desc & PIONIC_PKT_ADDR_MASK);
  desc->len = (hw_desc >> PIONIC_PKT_ADDR_WIDTH) & PIONIC_PKT_LEN_MASK;
}

void pionic_tx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  write64(ctx, PIONIC_CONTROL_HOST_TX_ACK(cid), desc->len);
}

void dump_glb_stats(pionic_ctx_t *ctx) {
#define READ_PRINT(name) printf("%s\t: %#lx\n", #name, read64(ctx, name));
  READ_PRINT(PIONIC_GLOBAL_RX_OVERFLOW_COUNT)
#undef READ_PRINT
}

void dump_stats(pionic_ctx_t *ctx, int cid) {
#define READ_PRINT(name) printf("core %d: %s\t: %#lx\n", cid, #name, read64(ctx, name(cid)));
  READ_PRINT(PIONIC_CONTROL_RX_PACKET_COUNT)
  READ_PRINT(PIONIC_CONTROL_TX_PACKET_COUNT)
  READ_PRINT(PIONIC_CONTROL_RX_DMA_ERROR_COUNT)
  READ_PRINT(PIONIC_CONTROL_TX_DMA_ERROR_COUNT)
  READ_PRINT(PIONIC_CONTROL_RX_ALLOC_OCCUPANCY_UP_TO_128)
  READ_PRINT(PIONIC_CONTROL_RX_ALLOC_OCCUPANCY_UP_TO_1518)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__ENTRY)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_QUEUE)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__READ_START)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_DMA_WRITE)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_READ)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_COMMIT)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__ACQUIRE)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_TX_COMMIT)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_DMA_READ)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__EXIT)
#undef READ_PRINT
}

void pionic_reset_pkt_alloc(pionic_ctx_t *ctx, int cid) {
  write64(ctx, PIONIC_CONTROL_ALLOC_RESET(cid), 1);
  usleep(1); // arbitrary
  write64(ctx, PIONIC_CONTROL_ALLOC_RESET(cid), 0);
}
