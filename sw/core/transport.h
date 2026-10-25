/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LH_TRANSPORT_H
#define LH_TRANSPORT_H
/* Compile-time dispatch keeps one runtime worker/codec/ownership path. */
#if LH_BACKEND_VM
#include "vm/core.h"
#define LH_TRANSPORT(name) core_vm_##name
#else
#include "eci/core.h"
#define LH_TRANSPORT(name) core_eci_##name
#endif
static inline bool core_rx_acquire_traced(void *base, lauberhorn_core_state_t *ctx,
    lauberhorn_pkt_desc_t *desc, uint64_t *stamp, lauberhorn_trace_now_fn_t clock)
{ return LH_TRANSPORT(rx_acquire_traced)(base, ctx, desc, stamp, clock); }
static inline bool core_rx_acquire(void *base, lauberhorn_core_state_t *ctx,
                                   lauberhorn_pkt_desc_t *desc)
{ return LH_TRANSPORT(rx_acquire)(base, ctx, desc); }
static inline void core_rx_release(lauberhorn_core_state_t *ctx)
{ LH_TRANSPORT(rx_release)(ctx); }
static inline void *core_tx_get_buffer(void *base, lauberhorn_core_state_t *ctx, bool inlined)
{ return LH_TRANSPORT(tx_get_buffer)(base, ctx, inlined); }
static inline void core_tx_cancel(lauberhorn_core_state_t *ctx)
{ LH_TRANSPORT(tx_cancel)(ctx); }
static inline int core_tx_commit_traced(void *base, lauberhorn_core_state_t *ctx,
    lauberhorn_pkt_desc_t *desc, uint64_t *stamp, lauberhorn_trace_now_fn_t clock)
{ return LH_TRANSPORT(tx_commit_traced)(base, ctx, desc, stamp, clock); }
static inline int core_tx_commit(void *base, lauberhorn_core_state_t *ctx,
                                 lauberhorn_pkt_desc_t *desc)
{ return LH_TRANSPORT(tx_commit)(base, ctx, desc); }
#undef LH_TRANSPORT
#endif
