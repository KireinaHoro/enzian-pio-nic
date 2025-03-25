#ifndef __PIONIC_HAL_H__
#define __PIONIC_HAL_H__

#include "api.h"

// TODO: these MMIO functions are to be removed after migrating everthing (e.g. cmac) to Mackerel

// HAL functions that access regs region (over the ECI IO bridge)
void write64(pionic_ctx_t ctx, uint64_t addr, uint64_t reg);
uint64_t read64(pionic_ctx_t ctx, uint64_t addr);
void write32(pionic_ctx_t ctx, uint64_t addr, uint32_t reg);
uint32_t read32(pionic_ctx_t ctx, uint64_t addr);

#endif // __PIONIC_HAL_H__
