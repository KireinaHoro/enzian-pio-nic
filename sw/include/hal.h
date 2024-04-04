#ifndef __PIONIC_HAL_H__
#define __PIONIC_HAL_H__

// methods to access the configuration register phase for driving components
// e.g. CMAC
void write64(pionic_ctx_t *ctx, uint64_t addr, uint64_t reg);
void write32(pionic_ctx_t *ctx, uint64_t addr, uint32_t reg);
uint64_t read64(pionic_ctx_t *ctx, uint64_t addr);
uint32_t read32(pionic_ctx_t *ctx, uint64_t addr);

#endif // __PIONIC_HAL_H__
