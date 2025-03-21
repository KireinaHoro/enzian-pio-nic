#ifndef __PIONIC_PROFILE_H__
#define __PIONIC_PROFILE_H__

#include "api.h"

typedef struct {
  // TX timestamps
  uint32_t acquire;
  uint32_t after_tx_commit;
  uint32_t after_dma_read;
  uint32_t exit;
  // RX timestamps
  uint32_t entry;
  uint32_t after_rx_queue;
  uint32_t after_dma_write;
  uint32_t read_start;
  uint32_t after_read;
  uint32_t after_rx_commit; // we don't need this
} pionic_core_ctrl_timestamps_t;

uint64_t pionic_get_cycles(pionic_ctx_t ctx);
uint64_t pionic_us_to_cycles(double us);
double pionic_cycles_to_us(uint64_t cycles);

void pionic_read_timestamps_core_ctrl(pionic_ctx_t ctx, int cid,
    pionic_core_ctrl_timestamps_t *ts);

#endif // __PIONIC_PROFILE_H__
