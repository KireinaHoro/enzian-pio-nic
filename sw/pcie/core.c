#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

#include "api.h"
#include "hal.h"
#include "cmac.h"
#include "diag.h"

#include "../../hw/gen/pcie/regs.h"
#include "../../hw/gen/pcie/config.h"

struct pionic_ctx {
  void *bar;
};

#define PIONIC_PKTBUF_OFF_TO_ADDR(off)   ((off)  + PIONIC_PKT_BUFFER)
#define PIONIC_ADDR_TO_PKTBUF_OFF(addr)  ((addr) - PIONIC_PKT_BUFFER)

#define PIONIC_CMAC_BASE 0x200000UL
#define PIONIC_MMAP_END 0x300000UL

void write64(pionic_ctx_t ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  printf("W %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->bar)[addr / 8] = reg;
}

uint64_t read64(pionic_ctx_t ctx, uint64_t addr) {
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

void write32(pionic_ctx_t ctx, uint64_t addr, uint32_t reg) {
#ifdef DEBUG_REG
  printf("W %#lx <- %#x\n", addr, reg);
#endif
  ((volatile uint32_t *)ctx->bar)[addr / 4] = reg;
}

uint32_t read32(pionic_ctx_t ctx, uint64_t addr) {
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

int pionic_init(pionic_ctx_t *usr_ctx, const char *dev, bool loopback) {
  int ret = -1;

  pionic_ctx_t ctx = *usr_ctx = malloc(sizeof(struct pionic_ctx));

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

void pionic_set_rx_block_cycles(pionic_ctx_t ctx, int cycles) {
  write32(ctx, PIONIC_GLOBAL_RX_BLOCK_CYCLES, cycles);

  printf("Rx block cycles: %d\n", cycles);
}

void pionic_set_core_mask(pionic_ctx_t ctx, uint64_t mask) {
  write64(ctx, PIONIC_GLOBAL_DISPATCH_MASK, mask);

  printf("Dispatcher mask: %#lx\n", mask);
}

void pionic_fini(pionic_ctx_t *usr_ctx) {
  pionic_ctx_t ctx = *usr_ctx;

  // disable CMAC
  stop_cmac(ctx, PIONIC_CMAC_BASE);

  munmap(ctx->bar, PIONIC_MMAP_END);

  *usr_ctx = NULL;
}

bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
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

void pionic_rx_ack(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t read_addr = desc->buf - (uint8_t *)(ctx->bar);
  uint64_t reg = (PIONIC_ADDR_TO_PKTBUF_OFF(read_addr) & PIONIC_PKT_ADDR_MASK) | ((desc->len & PIONIC_PKT_LEN_MASK) << PIONIC_PKT_ADDR_WIDTH);

  write64(ctx, PIONIC_CONTROL_HOST_RX_NEXT_ACK(cid), reg);
}

void pionic_tx_get_desc(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t reg = read64(ctx, PIONIC_CONTROL_HOST_TX(cid));
  uint64_t hw_desc = reg >> 1;

  desc->buf = (uint8_t *)(ctx->bar) + PIONIC_PKTBUF_OFF_TO_ADDR(hw_desc & PIONIC_PKT_ADDR_MASK);
  desc->len = (hw_desc >> PIONIC_PKT_ADDR_WIDTH) & PIONIC_PKT_LEN_MASK;
}

void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  write64(ctx, PIONIC_CONTROL_HOST_TX_ACK(cid), desc->len);
}
