#ifndef __PIONIC_API_H__
#define __PIONIC_API_H__

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

struct pionic_ctx;
typedef struct pionic_ctx pionic_ctx_t;

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

#endif // __PIONIC_API_H__
