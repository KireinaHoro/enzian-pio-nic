#ifndef __PIONIC_H__
#define __PIONIC_H__

#include <stdint.h>
#include <stdbool.h>

#include "../../hw/gen/eci/regs.h"
#include "../../hw/gen/eci/config.h"

#include "../include/api.h"
#include "../include/hal.h"

#define PIONIC_CMAC_BASE 0x200000UL

#define US_TO_CYCLES(us) ((uint64_t)(us) * PIONIC_CLOCK_FREQ / 1000 / 1000)
#define CYCLES_TO_US(cycles) ((double)(cycles) / (PIONIC_CLOCK_FREQ / 1000 / 1000))

struct pionic_ctx {
  void *regs_region;
  void *mem_region;

  struct {
    bool next_cl;

    // buffer to return a packet in a pionic_pkt_desc_t
    // these are pre-allocated and reused
    // FIXME: this precludes passing packet in the registers and forces a copy
    uint8_t *pkt_buf;
  } core_states[PIONIC_NUM_CORES];
};

#endif // __PIONIC_H__
