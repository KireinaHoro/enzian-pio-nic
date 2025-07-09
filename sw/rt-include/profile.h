#ifndef __PIONIC_PROFILE_H__
#define __PIONIC_PROFILE_H__

#include <stdint.h>

#include "hal.h"

typedef struct {
  uint64_t rx_cmac_entry;
  uint64_t rx_after_cdc_queue;
  uint64_t rx_enqueue_to_host;
  uint64_t rx_core_read_start;
  uint64_t rx_core_read_finish;
  uint64_t rx_core_commit;
  uint64_t tx_core_acquire;
  uint64_t tx_core_commit;
  uint64_t tx_after_dma_read;
  uint64_t tx_before_cdc_queue;
  uint64_t tx_cmac_exit;
} pionic_core_ctrl_timestamps_t;

uint64_t pionic_get_cycles(pionic_global_t *dev);
uint64_t pionic_us_to_cycles(double us);
double pionic_cycles_to_us(uint64_t cycles);

void pionic_read_timestamps_core_ctrl(pionic_global_t *dev, pionic_core_ctrl_timestamps_t *ts);

#endif // __PIONIC_PROFILE_H__
