#include "profile.h"
#include "hal.h"

#include "config.h"
#include "gen/pionic_eci_global.h"

uint64_t pionic_get_cycles(pionic_global_t *dev) {
  return pionic_global(cycles_rd)(dev);
}

uint64_t pionic_us_to_cycles(double us) {
  return (uint64_t)us * PIONIC_CLOCK_FREQ / 1000 / 1000;
}

double pionic_cycles_to_us(uint64_t cycles) {
  return (double)cycles / (PIONIC_CLOCK_FREQ / 1000 / 1000);
}

void pionic_read_timestamps_core_ctrl(pionic_global_t *dev,
                                      pionic_core_ctrl_timestamps_t *ts) {
  ts->rx_cmac_entry = pionic_global(last_profile__rx_cmac_entry_rd)(dev);
  ts->rx_after_cdc_queue =
      pionic_global(last_profile__rx_after_cdc_queue_rd)(dev);
  ts->rx_enqueue_to_host =
      pionic_global(last_profile__rx_enqueue_to_host_rd)(dev);
  ts->rx_core_read_start =
      pionic_global(last_profile__rx_core_read_start_rd)(dev);
  ts->rx_core_read_finish =
      pionic_global(last_profile__rx_core_read_finish_rd)(dev);
  ts->rx_core_commit = pionic_global(last_profile__rx_core_commit_rd)(dev);
  ts->tx_core_acquire = pionic_global(last_profile__tx_core_acquire_rd)(dev);
  ts->tx_core_commit = pionic_global(last_profile__tx_core_commit_rd)(dev);
  ts->tx_after_dma_read =
      pionic_global(last_profile__tx_after_dma_read_rd)(dev);
  ts->tx_before_cdc_queue =
      pionic_global(last_profile__tx_before_cdc_queue_rd)(dev);
  ts->tx_cmac_exit = pionic_global(last_profile__tx_cmac_exit_rd)(dev);
}

