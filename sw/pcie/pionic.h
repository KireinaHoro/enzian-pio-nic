#ifndef __PIONIC_H__
#define __PIONIC_H__

#include <stdint.h>
#include <stdbool.h>

#include "../../hw/gen/pcie/regs.h"
#include "../../hw/gen/pcie/config.h"

#include "../include/api.h"
#include "../include/hal.h"

#define PIONIC_PKTBUF_OFF_TO_ADDR(off)   ((off)  + PIONIC_PKT_BUFFER)
#define PIONIC_ADDR_TO_PKTBUF_OFF(addr)  ((addr) - PIONIC_PKT_BUFFER)

#define PIONIC_CMAC_BASE 0x200000UL
#define PIONIC_MMAP_END 0x300000UL

#define US_TO_CYCLES(us) ((uint64_t)(us) * PIONIC_CLOCK_FREQ / 1000 / 1000)
#define CYCLES_TO_US(cycles) ((double)(cycles) / (PIONIC_CLOCK_FREQ / 1000 / 1000))

struct pionic_ctx {
  void *bar;
};

#endif
