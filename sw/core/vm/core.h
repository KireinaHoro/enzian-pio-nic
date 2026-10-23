/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LH_VM_CORE_H
#define LH_VM_CORE_H
#include "config.h"
#include "core-common.h"
#include "vm/abi.h"
#include <stdlib.h>
extern int lauberhorn_fd;
typedef uint64_t (*lauberhorn_trace_now_fn_t)(void);
enum { CORE_TX_ACCEPTED = 1, CORE_TX_RETRY = 2, CORE_TX_ERROR = 3 };
_Static_assert(LAUBERHORN_ECI_CORE_OFFSET >= LH_VM_TX_OFFSET + LAUBERHORN_MTU,
               "VM RX/TX storage must fit the worker alias");
static inline bool core_vm_rx_acquire_traced(void *base, lauberhorn_core_state_t *ctx,
    lauberhorn_pkt_desc_t *desc, uint64_t *stamp, lauberhorn_trace_now_fn_t clock)
{
    if (ctx->rx_view.pending || ctx->tx_reserved) return false;
    int ret = ioctl(lauberhorn_fd, LH_VM_RX, desc);
    if (stamp && clock) *stamp = clock();
    if (ret < 0) abort();
    *ctx->rx_next_cl ^= 1;
    if (!ret) return false;
    if (desc->type != TY_DESCHED) {
        ctx->rx_view.data = base;
        ctx->rx_view.length = desc->payload_len;
        ctx->rx_view.pending = true;
    }
    return true;
}
static inline bool core_vm_rx_acquire(void *base, lauberhorn_core_state_t *ctx,
                                      lauberhorn_pkt_desc_t *desc)
{ return core_vm_rx_acquire_traced(base, ctx, desc, NULL, NULL); }
static inline void core_vm_rx_release(lauberhorn_core_state_t *ctx)
{
    if (!ctx->rx_view.pending) return;
    if (ioctl(lauberhorn_fd, LH_VM_RX_RELEASE)) abort();
    memset(&ctx->rx_view, 0, sizeof(ctx->rx_view));
}
static inline void *core_vm_tx_get_buffer(void *base, lauberhorn_core_state_t *ctx,
                                         bool inlined)
{
    if (ctx->tx_reserved || ioctl(lauberhorn_fd, LH_VM_TX_RESERVE)) return NULL;
    ctx->tx_reserved = true;
    ctx->tx_capacity = inlined ? 64 : LAUBERHORN_MTU;
    ctx->tx_reservation_base = base;
    ++ctx->tx_generation;
    return (char *)base + LH_VM_TX_OFFSET;
}
static inline void core_vm_tx_cancel(lauberhorn_core_state_t *ctx)
{
    if (!ctx->tx_reserved) return;
    if (ioctl(lauberhorn_fd, LH_VM_TX_CANCEL)) abort();
    ctx->tx_reserved = false;
    ctx->tx_reservation_base = NULL;
}
static inline int core_vm_tx_commit_traced(void *base, lauberhorn_core_state_t *ctx,
    lauberhorn_pkt_desc_t *desc, uint64_t *stamp, lauberhorn_trace_now_fn_t clock)
{
    if (!ctx->tx_reserved || ctx->tx_reservation_base != base ||
        desc->payload_len > ctx->tx_capacity) return CORE_TX_ERROR;
    if (stamp && clock) *stamp = clock();
    int ret = ioctl(lauberhorn_fd, LH_VM_TX, desc);
    ctx->tx_reserved = false;
    ctx->tx_reservation_base = NULL;
    *ctx->tx_next_cl ^= 1;
    return ret < 0 ? CORE_TX_ERROR : ret;
}
static inline int core_vm_tx_commit(void *base, lauberhorn_core_state_t *ctx,
                                    lauberhorn_pkt_desc_t *desc)
{ return core_vm_tx_commit_traced(base, ctx, desc, NULL, NULL); }
#endif
