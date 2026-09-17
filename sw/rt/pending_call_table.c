#include "pending_call_table.h"

#include "log.h"
#include "oncrpc.h"
#include "utils/clock.h"
#include <stdatomic.h>

// atomically acquire a free packet slot
// must be released with pending_call_table_release
struct pending_call * pending_call_acquire(struct pending_call_table *tbl) {
    struct pending_call *c;
    int idx;
    for (;;) {
        idx = bitmap_acquire(&tbl->bitmap);
        if (idx < 0) return NULL;
        c = &tbl->pkts[idx];
        pthread_spin_lock(&c->lock);
        if (c->gen != UINT16_MAX) break;
        /* Never wrap into a cookie which an old packet could still carry.
         * Retire this bitmap slot for the lifetime of the table. */
        pthread_spin_unlock(&c->lock);
    }
    ++c->gen;

    atomic_store_explicit(&c->state, SLOT_RESERVED, memory_order_relaxed);
    c->idx = idx;
    c->xid = pending_call_generate_xid(c);
    c->endpoint = NULL;
    c->hardware_done = false;
    pthread_spin_unlock(&c->lock);
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
    if (p->endpoint && p->hardware_done)
        __atomic_fetch_sub(&p->endpoint->_transport.unresolved, 1, __ATOMIC_RELEASE);
    p->endpoint = NULL;
    p->codec = NULL;
    atomic_store(&p->timeout_ms, 0);
    atomic_store(&p->state, SLOT_FREE);
    bitmap_release(&tbl->bitmap, p->idx);
}

struct pending_call *pending_call_get_by_cookie(struct pending_call_table *table, uint32_t xid) {
    uint32_t slot = XID_IDX(xid);

    if (slot >= PENDING_CALL_TABLE_SIZE) {
        return NULL;
    }
    return &table->pkts[slot];
}

// Called under the slot lock, including the timeout deadline timestamp.
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
    pthread_spin_lock(&c->lock);
    c->sent_at = now();
    atomic_store_explicit(&c->timeout_ms, timeout_ms, memory_order_relaxed);
    atomic_store_explicit(&c->state, SLOT_PENDING, memory_order_release);
    pthread_spin_unlock(&c->lock);
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
/* Validate both identities while holding the slot lock: cookie selects the
 * generation, XID verifies the protocol transaction expected by that slot. */
bool pending_call_try_claim_reply(struct pending_call *c, uint32_t cookie, uint32_t xid)
{
    bool ok;
    pthread_spin_lock(&c->lock);
    ok = cookie == pending_call_generate_xid(c) && xid == c->xid &&
         atomic_load_explicit(&c->state, memory_order_relaxed) == SLOT_PENDING;
    if (ok) {
        c->hardware_done = true;
        atomic_store_explicit(&c->state, SLOT_FULFILLED, memory_order_relaxed);
    }
    pthread_spin_unlock(&c->lock);
    return ok;
}

/* Sending can race an immediate reply. Revalidate the generation before
 * arming a deadline, and never touch a slot already consumed or reused. */
void pending_call_start_timeout(struct pending_call *c, uint32_t cookie, uint32_t timeout_ms)
{
    pthread_spin_lock(&c->lock);
    if (pending_call_generate_xid(c) == cookie && atomic_load(&c->state) == SLOT_PENDING) {
        c->sent_at = now();
        atomic_store(&c->timeout_ms, timeout_ms);
    }
    pthread_spin_unlock(&c->lock);
}

/* Housekeeping only observes deadlines. Settlement and fiber execution remain
 * on runtime workers, where forwarding can use the mapped datapath. */
bool pending_call_table_has_expired(struct pending_call_table *tbl) {
    struct timespec t = now();
    for (int i = 0; i < PENDING_CALL_TABLE_SIZE; ++i) {
        struct pending_call *c = &tbl->pkts[i];
        pthread_spin_lock(&c->lock);
        bool expired = atomic_load(&c->state) == SLOT_PENDING && pending_call_has_expired(c, &t);
        pthread_spin_unlock(&c->lock);
        if (expired) return true;
    }
    return false;
}
