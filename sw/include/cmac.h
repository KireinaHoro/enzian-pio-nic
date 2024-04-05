#ifndef __PIONIC_CMAC_H__
#define __PIONIC_CMAC_H__

#include "hal.h"

#define PIONIC_CMAC_REG(base, off) ((off) + (base))
#define PM_RX_REG1             0x014
#define PM_TX_REG1             0x00c
#define PM_STAT_RX_STATUS_REG  0x204
#define PM_TICK_REG            0x2b0
#define PM_GT_LOOPBACK_REG     0x090

void start_cmac(pionic_ctx_t *ctx, uint64_t base, bool loopback);
void stop_cmac(pionic_ctx_t *ctx, uint64_t base);

#endif // __PIONIC_CMAC_H__
