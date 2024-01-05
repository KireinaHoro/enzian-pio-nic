#ifndef __PIONIC_H__
#define __PIONIC_H__

#include <stdint.h>
#include <stdbool.h>

// TODO: generate from spinal generator
#define PIONIC_GLB_RX_BLOCK_CYCLES 0x0
#define PIONIC_GLB_DISPATCH_MASK   0x8
#define PIONIC_GLB_RX_OVERFLOW_COUNT 0x10

#define PIONIC_NUM_CORES 4
#define PIONIC_CORE_REG(id, off) (0x1000UL * ((id) + 1) + (off))

#define PC_RX_NEXT          0x0
#define PC_RX_NEXT_ACK      0x8
#define PC_TX               0x10
#define PC_TX_ACK           0x18

#define PC_STAT_RX_COUNT   0x20
#define PC_STAT_TX_COUNT   0x28
#define PC_STAT_RX_DMA_ERR_COUNT   0x30
#define PC_STAT_TX_DMA_ERR_COUNT   0x38
#define PC_STAT_RX_ALLOC_OCCUPANCY_0   0x40
#define PC_STAT_RX_ALLOC_OCCUPANCY_1   0x48

#define PIONIC_PKTBUF(off)   ((off) + 0x100000UL)

#define PIONIC_CMAC_REG(off) ((off) + 0x200000UL)
#define PM_RX_REG1             0x014
#define PM_TX_REG1             0x00c
#define PM_STAT_RX_STATUS_REG  0x204
#define PM_TICK_REG            0x2b0
#define PM_GT_LOOPBACK_REG     0x090

#define PIONIC_MMAP_END 0x300000UL

// TODO: generate!
#define PKT_ADDR_WIDTH 16
#define PKT_ADDR_MASK ((1 << PKT_ADDR_WIDTH) - 1)
#define PKT_LEN_WIDTH 16
#define PKT_LEN_MASK ((1 << PKT_LEN_WIDTH) - 1)

typedef struct {
  void *bar;
} pionic_ctx_t;

typedef struct {
  uint8_t *buf;
  size_t len;
} pionic_pkt_desc_t;

int pionic_init(pionic_ctx_t *ctx, const char *dev, bool loopback);
void pionic_fini(pionic_ctx_t *ctx);
void pionic_set_rx_block_cycles(pionic_ctx_t *ctx, int cycles);
void pionic_set_core_mask(pionic_ctx_t *ctx, uint64_t mask);

bool pionic_rx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);
bool pionic_rx_ack(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);

void pionic_tx_get_desc(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);
void pionic_tx(pionic_ctx_t *ctx, int cid, pionic_pkt_desc_t *desc);

void dump_glb_stats(pionic_ctx_t *ctx);
void dump_stats(pionic_ctx_t *ctx, int cid);

#endif
