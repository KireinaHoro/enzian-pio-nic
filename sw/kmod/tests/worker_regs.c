/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#include <assert.h>
#include <stdint.h>
#include "worker_regs.h"

int main(void)
{
    for (unsigned int i = 0; i < LAUBERHORN_NUM_WORKER_CORES; ++i) {
        uint64_t base = worker_desched_base(i);
        /* Production composition emits each worker's descheduling block after
         * its transport register block. Bypass has a separate IRQ block. */
        assert(base == LAUBERHORN_ECI_WORKER_BASE(i + 1) + 0x100);
        assert(base != LAUBERHORN_ECI_BYPASS_IRQ_BASE);
        assert(base < 0x400000);
    }
    return 0;
}
