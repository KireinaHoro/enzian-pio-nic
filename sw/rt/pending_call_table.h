#ifndef PKT_TABLE_H
#define PKT_TABLE_H

#include <pthread.h>
#include <stdatomic.h>

#include "utils/bitmap.h"

#include "lauberhorn.h"
#include "utils/clock.h"
#include "worker.h"
#include "fiber/fiber.h"
#include "lauberhorn/future.h"
#include "vstate.h"

#define PENDING_CALL_TABLE_SIZE (64 * 10)

#define pending_call_table_for_each(tbl, entry)                                \
    for (int __i = 0;                                                          \
         __i < PENDING_CALL_TABLE_SIZE && ((entry) = &(tbl)->pkts[__i], 1);    \
         __i++)

#define XID_SLOT_BITS 16
#define XID_IDX(xid) ((uint32_t)(xid) & ((1 << XID_SLOT_BITS) - 1))

/**
 * State transitions:
 * pending_call_initialze: SLOT_FREE
 * pending_call_acquire: SLOT_FREE -> SLOT_PENDING (+ bump generation)
 * pending_call_try_claim: SLOT_PENDING -> SLOT_FULFILLED (under lock)
 * pending_call_settle: SLOT_FULFILLED -> SLOT_READY
 * pending_call_release: SLOT_FREE
 *
 * timeout routine and writing results to slot is only allowed
 * if we successfully called pending_call_try_claim
 */
typedef enum {
    SLOT_FREE = 0,
    SLOT_RESERVED,
    SLOT_PENDING,
    SLOT_FULFILLED,
    SLOT_READY
} slot_state_t;

struct pending_call {

    // used to derive xid
    // xid = ([ gen | idx ])
    uint16_t gen;
    uint16_t idx;
    uint32_t xid;
    lauberhorn_rpc_endpoint_t *endpoint;
    bool hardware_done;
    _Atomic slot_state_t state;

    // all transitions from PENDING -> FULFILLED must be made while holding lock
    // as both the timeout check and incoming replies can try to fulfill replies
    // all other transitions have a single owner and can be made with atomic stores
    pthread_spinlock_t lock;

    enum { COMPLETION_FIBER, COMPLETION_FORWARD } kind;
    union {
        // COMPLETION_FUTURE
        struct fiber_handle fiber_handle;
        // COMPLETION_FORWARD: independent of the parent handler stack.
        lauberhorn_pkt_desc_t parent;
    } completion;

    rpc_codec_t *codec;

    // filled by runtime
    rpc_result_t result;

    // timeout info
    // timeout_ms is read without the lock by the
    // timeout sweep to avoid unnecessary locking
    struct timespec sent_at;
    _Atomic uint32_t timeout_ms;
};


struct pending_call_table {
    struct pending_call pkts[PENDING_CALL_TABLE_SIZE];
    bitmap_t bitmap;
};

void pending_call_release(struct pending_call_table *tbl,
                                struct pending_call *p);

void pending_call_table_init(struct pending_call_table *tbl);
bool pending_call_table_has_expired(struct pending_call_table *tbl);

/* Check expiry with the slot lock held; sent_at is not atomic. */
bool pending_call_has_expired(struct pending_call *entry, struct timespec *now);

/* copy call result into result*/
void pending_call_extract_result(struct pending_call *entry,
                                 rpc_result_t *result);

bool pending_call_try_claim(struct pending_call *c, uint32_t xid);
bool pending_call_try_claim_reply(struct pending_call *c, uint32_t cookie, uint32_t xid);

bool pending_call_is_ready(struct pending_call *c);

void pending_call_arm_timeout(struct pending_call *c, uint32_t timeout_ms);
void pending_call_start_timeout(struct pending_call *c, uint32_t cookie, uint32_t timeout_ms);

uint32_t pending_call_generate_xid(struct pending_call *c);

struct pending_call * pending_call_acquire(struct pending_call_table *tbl);


struct pending_call *pending_call_get_by_cookie(struct pending_call_table *table, uint32_t xid);

#endif // PKT_TABLE_H
