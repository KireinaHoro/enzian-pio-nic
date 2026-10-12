/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LAUBERHORN_WORKER_REGS_H
#define LAUBERHORN_WORKER_REGS_H

#include "config.h"
#include "regblock_bases.h"

/* The descheduling table contains application workers only. Unlike datapath
 * indices, its zero-based index does not reserve entry zero for bypass. */
static inline uint64_t worker_desched_base(unsigned int worker_index)
{
#if LAUBERHORN_NUM_WORKER_CORES == 1
    (void)worker_index;
    return LAUBERHORN_ECI_DESCHED_BASE;
#else
    return LAUBERHORN_ECI_DESCHED_BASE(worker_index);
#endif
}

#endif
