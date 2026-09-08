#include "pending_call_table.h"

#include "log.h"
#include "oncrpc.h"
#include "utils/clock.h"
#include <stdatomic.h>

// atomically acquire a free packet slot
// must be released with pending_call_table_release
struct pending_call * pending_call_acquire(struct pending_call_table *tbl) {
    int idx = bitmap_acquire(&tbl->bitmap);
    if (idx < 0) {
        return NULL;
    }
    struct pending_call* c = &tbl->pkts[idx];
    
    //  bump generation by 1 to generate new xid
    //  under simu kmod, must restrict to 12 bits
#ifdef LAUBERHORN_SIMULATION
    c->gen = (c->gen + 1) & ((1 << 12) - 1);
#else
    c->gen = (c->gen + 1);
#endif

    atomic_store_explicit(&c->state, SLOT_PENDING, memory_order_relaxed);
    c->idx = idx; 
    return c; 
}

// try to set state from SLOT_PENDING to SLOT_FULFILLED
// this ensures that we will not be timed out anymore
// also verify that the xid matches the xid this slot awaits
bool pending_call_try_claim(struct pending_call *c, uint32_t xid) {

    bool result; 
    pthread_spin_lock(&c->lock);

    // compare full xid
    if (xid != pending_call_generate_xid(c)) {
        result = false; 
        goto unlock; 
    }
    // we can only claim a pending slot
    if (atomic_load_explicit(&c->state, memory_order_relaxed) != SLOT_PENDING) {
        result = false; 
        goto unlock; 
    }
    // ensure that we will not be timed out anymore
    atomic_store_explicit(&c->state, SLOT_FULFILLED, memory_order_relaxed);
    result = true;  
unlock:
    pthread_spin_unlock(&c->lock);

    return result; 
}

void pending_call_release(struct pending_call_table *tbl,
                                struct pending_call *p) {
                                
    if (p->result.data) {
        p->codec->ops->free(p->result.data, RPC_FREE_RESP, p->codec->priv);
        p->result.data = NULL;  
    }
    p->codec = NULL; 
    atomic_store(&p->timeout_ms, 0);
    atomic_store(&p->state, SLOT_FREE);
    bitmap_release(&tbl->bitmap, p->idx);
}

bool pending_call_try_cancel(struct pending_call *c, uint32_t xid) {
    // TODO: not implemented yet
    // should release the slot from the pending call bitmap
    return true; 
}

struct pending_call *pending_call_get_by_xid(struct pending_call_table *table, uint32_t xid) {
    uint32_t slot = XID_IDX(xid);

    if (slot >= PENDING_CALL_TABLE_SIZE) {
        return NULL;
    }
    return &table->pkts[slot];
}

// unlocked hint for the timeout sweep
// result revalidated under lock if true
bool pending_call_has_expired(struct pending_call *entry,
                              struct timespec *now) {
    
    uint32_t timeout_ms = atomic_load_explicit(&entry->timeout_ms, memory_order_relaxed);

    // timeout_ms == 0 means no deadline is armed
    // ie. the slot is free, or the call was dispatched without one
    if (timeout_ms == 0) {
        return false;
    }
    return clock_diff_ms(&entry->sent_at, now) >= timeout_ms;
}

// safe to call only after pending_call_store_reply has returned true
void pending_call_extract_result(struct pending_call *entry,
                                 rpc_result_t *result) {
    *result = entry->result;
    entry->result.data = NULL;
}

void pending_call_init(struct pending_call *c, int idx) {
    c->idx = idx; 
    c->gen = 0; 
    pthread_spin_init(&c->lock, PTHREAD_PROCESS_PRIVATE);
    atomic_store_explicit(&c->state, SLOT_FREE, memory_order_relaxed);
}

void pending_call_table_init(struct pending_call_table *tbl) {
    for (int i = 0; i < PENDING_CALL_TABLE_SIZE; i++) {
        tbl->pkts[i].idx = i;
        pending_call_init(&tbl->pkts[i], i);
    }
    bitmap_init(&tbl->bitmap, PENDING_CALL_TABLE_SIZE / 64);
}

// safe to call only after pending_call_acquire
void pending_call_arm_timeout(struct pending_call *c, uint32_t timeout_ms) {
    c->sent_at = now();
    atomic_store_explicit(&c->timeout_ms, timeout_ms, memory_order_relaxed);
}

// 32 bits (top 16 bits gen, lower 16 bits idx)
// for mock-module, only use the lower 28 bits
#define MOCK_MODULE_MASK ((1 << 28) - 1)
uint32_t pending_call_generate_xid(struct pending_call *c) {

#ifdef LAUBERHORN_SIMULATION
   return (((uint32_t)c->gen) << XID_SLOT_BITS | c->idx) & MOCK_MODULE_MASK; 
#else 
    return (((uint32_t)c->gen) << XID_SLOT_BITS | c->idx);
#endif

}

bool pending_call_is_ready(struct pending_call *c) {
    return atomic_load_explicit(&c->state, memory_order_acquire) == SLOT_READY; 
}