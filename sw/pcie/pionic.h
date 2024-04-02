#ifndef __PIONIC_H__
#define __PIONIC_H__

#include <stdint.h>
#include <stdbool.h>

#include "../../hw/gen/pcie/regs.h"
#include "../../hw/gen/pcie/config.h"

#include "../include/hal.h"

#define PIONIC_PKTBUF_OFF_TO_ADDR(off)   ((off)  + PIONIC_PKT_BUFFER)
#define PIONIC_ADDR_TO_PKTBUF_OFF(addr)  ((addr) - PIONIC_PKT_BUFFER)

#define PIONIC_CMAC_BASE 0x200000UL
#define PIONIC_MMAP_END 0x300000UL

#define US_TO_CYCLES(us) ((uint64_t)(us) * PIONIC_CLOCK_FREQ / 1000 / 1000)
#define CYCLES_TO_US(cycles) ((double)(cycles) / (PIONIC_CLOCK_FREQ / 1000 / 1000))

struct pionic_ctx {
  void *bar;
};

typedef struct {
  uint8_t *buf;
  size_t len;
} pionic_pkt_desc_t;

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
