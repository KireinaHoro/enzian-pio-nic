#include "hal.h"
#include "profile.h"

#include "config.h"
#include "regs.h"

uint64_t pionic_get_cycles(pionic_ctx_t ctx) {
  return read64(ctx, PIONIC_GLOBAL_CYCLES);
}

uint64_t pionic_us_to_cycles(double us) {
  return (uint64_t)us * PIONIC_CLOCK_FREQ / 1000 / 1000;
}

double pionic_cycles_to_us(uint64_t cycles) {
  return (double)cycles / (PIONIC_CLOCK_FREQ / 1000 / 1000);
}

void pionic_read_timestamps_core_ctrl(pionic_ctx_t ctx, int cid,
    pionic_core_ctrl_timestamps_t *ts) {
  ts->acquire = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__ACQUIRE(cid));
  ts->after_tx_commit = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_TX_COMMIT(cid));
  ts->after_dma_read = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_DMA_READ(cid));
  ts->exit = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__EXIT(cid));

  ts->entry = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__ENTRY(cid));
  ts->after_rx_queue = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_QUEUE(cid));
  ts->read_start = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__READ_START(cid));
  ts->after_dma_write = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_DMA_WRITE(cid));
  ts->after_read = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_READ(cid));
  ts->after_rx_commit = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_COMMIT(cid));
}

