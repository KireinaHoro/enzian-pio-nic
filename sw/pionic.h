#ifndef __PIONIC_H__
#define __PIONIC_H__

#include <stdint.h>
#include <stdbool.h>

#include "../hw/gen/regs.h"
#include "../hw/gen/config.h"

#define PIONIC_PKTBUF_OFF_TO_ADDR(off)   ((off)  + PIONIC_PKT_BUFFER)
#define PIONIC_ADDR_TO_PKTBUF_OFF(addr)  ((addr) - PIONIC_PKT_BUFFER)

#define PIONIC_CMAC_REG(off) ((off) + 0x200000UL)
#define PM_RX_REG1             0x014
#define PM_TX_REG1             0x00c
#define PM_STAT_RX_STATUS_REG  0x204
#define PM_TICK_REG            0x2b0
#define PM_GT_LOOPBACK_REG     0x090

#define PIONIC_MMAP_END 0x300000UL

#define US_TO_CYCLES(us) ((uint64_t)(us) * PIONIC_CLOCK_FREQ / 1000 / 1000)
#define CYCLES_TO_US(cycles) ((double)(cycles) / (PIONIC_CLOCK_FREQ / 1000 / 1000))

typedef struct {
  void *bar;
} pionic_ctx_t;

typedef struct {
  uint8_t *buf;
  size_t len;
} pionic_pkt_desc_t;

static inline void write64(pionic_ctx_t *ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  printf("W %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->bar)[addr / 8] = reg;
}

static inline uint64_t read64(pionic_ctx_t *ctx, uint64_t addr) {
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

static inline void write32(pionic_ctx_t *ctx, uint64_t addr, uint32_t reg) {
#ifdef DEBUG_REG
  printf("W %#lx <- %#x\n", addr, reg);
#endif
  ((volatile uint32_t *)ctx->bar)[addr / 4] = reg;
}

static inline uint32_t read32(pionic_ctx_t *ctx, uint64_t addr) {
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

int pionic_init(pionic_ctx_t *ctx, const char *dev, bool loopback);
void pionic_fini(pionic_ctx_t *ctx);
void pionic_set_rx_block_cycles(pionic_ctx_t *ctx, int cycles);
void pionic_set_core_mask(pionic_ctx_t *ctx, uint64_t mask);
void pionic_reset_pkt_alloc(pionic_ctx_t *ctx, int cid);

bool pionic_rx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);
void pionic_rx_ack(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);

void pionic_tx_get_desc(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);
void pionic_tx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);

void dump_glb_stats(pionic_ctx_t *ctx);
void dump_stats(pionic_ctx_t *ctx, int cid);

#endif
