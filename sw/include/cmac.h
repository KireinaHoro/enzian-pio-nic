#ifndef __PIONIC_CMAC_H__
#define __PIONIC_CMAC_H__

#include "hal.h"

#define PIONIC_CMAC_REG(base, off) ((off) + (base))
#define PM_RX_REG1             0x014
#define PM_TX_REG1             0x00c
#define PM_STAT_RX_STATUS_REG  0x204
#define PM_TICK_REG            0x2b0
#define PM_GT_LOOPBACK_REG     0x090

static inline void start_cmac(pionic_ctx_t *ctx, uint64_t base, bool loopback) {
  uint32_t status;

  write32(ctx, PIONIC_CMAC_REG(base, PM_RX_REG1), 1); // ctl_rx_enable
  write32(ctx, PIONIC_CMAC_REG(base, PM_TX_REG1), 0x10); // ctl_tx_send_rfi

  while (true) {
    write32(ctx, PIONIC_CMAC_REG(base, PM_TICK_REG), 1);
    status = read32(ctx, PIONIC_CMAC_REG(base, PM_STAT_RX_STATUS_REG));
    if (status & 2) break; // RX_aligned
    usleep(1000);
  }

  write32(ctx, PIONIC_CMAC_REG(base, PM_TX_REG1), 1); // ctl_tx_enable, !ctl_tx_send_rfi

  // FIXME: why do we need this?
  while (true) {
    write32(ctx, PIONIC_CMAC_REG(base, PM_TICK_REG), 1);
    status = read32(ctx, PIONIC_CMAC_REG(base, PM_STAT_RX_STATUS_REG));
    if (status == 3) break; // RX_aligned && RX_status
    usleep(1000);
  }

  write32(ctx, PIONIC_CMAC_REG(base, PM_GT_LOOPBACK_REG), loopback);

  printf("Loopback enabled: %s\n", loopback ? "true" : "false");

  // flow control disabled - skipping regs
}

static inline void stop_cmac(pionic_ctx_t *ctx, uint64_t base) {
  write32(ctx, PIONIC_CMAC_REG(base, PM_RX_REG1), 0); // !ctl_rx_enable
  write32(ctx, PIONIC_CMAC_REG(base, PM_TX_REG1), 0); // !ctl_tx_enable
}

#endif // __PIONIC_CMAC_H__
