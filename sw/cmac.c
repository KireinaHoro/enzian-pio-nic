#include "cmac.h"

#include <stdio.h>
#include <unistd.h>

#define PIONIC_CMAC_REG(base, off) ((off) + (base))
#define PM_RX_REG1             0x014
#define PM_TX_REG1             0x00c
#define PM_CORE_VERSION_REG    0x024
#define PM_STAT_RX_STATUS_REG  0x204
#define PM_TICK_REG            0x2b0
#define PM_GT_LOOPBACK_REG     0x090

#define LINE_UP_MAX_ATTEMPTS 100

int start_cmac(pionic_ctx_t ctx, uint64_t base, bool loopback) {
  uint32_t status;

  // verify version
  int ver = read32(ctx, PIONIC_CMAC_REG(base, PM_CORE_VERSION_REG));
  int ver_maj = ver & 0xff;
  int ver_min = (ver >> 8) & 0xff;
  if (ver == 0) {
    printf("CMAC version register all zero, bug?\n");
    return -1;
  }
  printf("CMAC version: %d.%d (raw %#x)\n", ver_maj, ver_min, ver);

  write32(ctx, PIONIC_CMAC_REG(base, PM_RX_REG1), 1); // ctl_rx_enable
  write32(ctx, PIONIC_CMAC_REG(base, PM_TX_REG1), 0x10); // ctl_tx_send_rfi

  int attempts = 0;

  while (++attempts < LINE_UP_MAX_ATTEMPTS) {
    write32(ctx, PIONIC_CMAC_REG(base, PM_TICK_REG), 1);
    status = read32(ctx, PIONIC_CMAC_REG(base, PM_STAT_RX_STATUS_REG));
    if (status & 2) break; // RX_aligned
    usleep(1000);
  }

  if (attempts == LINE_UP_MAX_ATTEMPTS) {
    printf("Wait for RX_aligned timed out\n");
    return -1;
  }
  attempts = 0;

  write32(ctx, PIONIC_CMAC_REG(base, PM_TX_REG1), 1); // ctl_tx_enable, !ctl_tx_send_rfi

  // FIXME: why do we need this?
  while (++attempts < LINE_UP_MAX_ATTEMPTS) {
    write32(ctx, PIONIC_CMAC_REG(base, PM_TICK_REG), 1);
    status = read32(ctx, PIONIC_CMAC_REG(base, PM_STAT_RX_STATUS_REG));
    if (status == 3) break; // RX_aligned && RX_status
    usleep(1000);
  }

  if (attempts == LINE_UP_MAX_ATTEMPTS) {
    printf("Wait for RX_aligned && RX_status timed out\n");
    return -1;
  }

  write32(ctx, PIONIC_CMAC_REG(base, PM_GT_LOOPBACK_REG), loopback);

  printf("Loopback enabled: %s\n", loopback ? "true" : "false");

  // flow control disabled - skipping regs
  return 0;
}

void stop_cmac(pionic_ctx_t ctx, uint64_t base) {
  write32(ctx, PIONIC_CMAC_REG(base, PM_RX_REG1), 0); // !ctl_rx_enable
  write32(ctx, PIONIC_CMAC_REG(base, PM_TX_REG1), 0); // !ctl_tx_enable
}

