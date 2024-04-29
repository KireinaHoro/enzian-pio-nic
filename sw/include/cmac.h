#ifndef __PIONIC_CMAC_H__
#define __PIONIC_CMAC_H__

#include "hal.h"

int start_cmac(pionic_ctx_t ctx, uint64_t base, bool loopback);
void stop_cmac(pionic_ctx_t ctx, uint64_t base);

#endif // __PIONIC_CMAC_H__
