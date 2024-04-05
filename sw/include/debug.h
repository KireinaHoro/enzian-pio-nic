#ifndef __PIONIC_DEBUG_H__
#define __PIONIC_DEBUG_H__

#include "api.h"

void pionic_dump_glb_stats(pionic_ctx_t ctx);
void pionic_dump_core_stats(pionic_ctx_t ctx, int cid);

// there shouldn't be a need to reset the packet alloc other than debugging
void pionic_reset_pkt_alloc(pionic_ctx_t ctx, int cid);

#endif // __PIONIC_DEBUG_H__
