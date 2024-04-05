#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

#include "pionic.h"
#include "../include/cmac.h"

void write64(pionic_ctx_t *ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  printf("W %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->bar)[addr / 8] = reg;
}

uint64_t read64(pionic_ctx_t *ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("R %#lx -> ", addr);
  fflush(stdout);
#endif
  uint64_t reg = ((volatile uint64_t *)ctx->bar)[addr / 8];
#ifdef DEBUG_REG
  printf("%#lx\n", reg);
#endif
  return reg;
}

void write32(pionic_ctx_t *ctx, uint64_t addr, uint32_t reg) {
#ifdef DEBUG_REG
  printf("W %#lx <- %#x\n", addr, reg);
#endif
  ((volatile uint32_t *)ctx->bar)[addr / 4] = reg;
}

uint32_t read32(pionic_ctx_t *ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("R %#lx -> ", addr);
  fflush(stdout);
#endif
  uint32_t reg = ((volatile uint32_t *)ctx->bar)[addr / 4];
#ifdef DEBUG_REG
  printf("%#x\n", reg);
#endif
  return reg;
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
  start_cmac(ctx, PIONIC_CMAC_BASE, loopback);

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
  write32(ctx, PIONIC_GLOBAL_RX_BLOCK_CYCLES, cycles);

  printf("Rx block cycles: %d\n", cycles);
}

void pionic_set_core_mask(pionic_ctx_t *ctx, uint64_t mask) {
  write64(ctx, PIONIC_GLOBAL_DISPATCH_MASK, mask);

  printf("Dispatcher mask: %#lx\n", mask);
}

void pionic_fini(pionic_ctx_t *ctx) {
  // disable CMAC
  stop_cmac(ctx, PIONIC_CMAC_BASE);

  munmap(ctx->bar, PIONIC_MMAP_END);
}

bool pionic_rx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = read64(ctx, PIONIC_CONTROL_HOST_RX_NEXT(cid));
  if (reg & 0x1) {
    uint64_t hw_desc = reg >> 1;
    uint64_t read_addr = PIONIC_PKTBUF_OFF_TO_ADDR(hw_desc & PIONIC_PKT_ADDR_MASK);
    uint64_t pkt_len = (hw_desc >> PIONIC_PKT_ADDR_WIDTH) & PIONIC_PKT_LEN_MASK;
    desc->buf = (uint8_t *)(ctx->bar) + read_addr;
    desc->len = pkt_len;
#ifdef DEBUG
    printf("Got packet at pktbuf %#lx len %#lx\n", PIONIC_ADDR_TO_PKTBUF_OFF(read_addr), pkt_len);
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
  uint64_t read_addr = desc->buf - (uint8_t *)(ctx->bar);
  uint64_t reg = (PIONIC_ADDR_TO_PKTBUF_OFF(read_addr) & PIONIC_PKT_ADDR_MASK) | ((desc->len & PIONIC_PKT_LEN_MASK) << PIONIC_PKT_ADDR_WIDTH);

  write64(ctx, PIONIC_CONTROL_HOST_RX_NEXT_ACK(cid), reg);
}

void pionic_tx_get_desc(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = read64(ctx, PIONIC_CONTROL_HOST_TX(cid));
  uint64_t hw_desc = reg >> 1;

  desc->buf = (uint8_t *)(ctx->bar) + PIONIC_PKTBUF_OFF_TO_ADDR(hw_desc & PIONIC_PKT_ADDR_MASK);
  desc->len = (hw_desc >> PIONIC_PKT_ADDR_WIDTH) & PIONIC_PKT_LEN_MASK;
}

void pionic_tx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc) {
  write64(ctx, PIONIC_CONTROL_HOST_TX_ACK(cid), desc->len);
}

void pionic_dump_glb_stats(pionic_ctx_t *ctx) {
#define READ_PRINT(name) printf("%s\t: %#lx\n", #name, read64(ctx, name));
  READ_PRINT(PIONIC_GLOBAL_RX_OVERFLOW_COUNT)
#undef READ_PRINT
}

void pionic_dump_core_stats(pionic_ctx_t *ctx, int cid) {
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
