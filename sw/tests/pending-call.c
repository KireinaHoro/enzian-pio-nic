#include "pending_call_table.h"
#include <assert.h>
static struct pending_call_table table;
static pthread_barrier_t acquired, release_calls;
static void *race_acquire(void *unused) {
    struct pending_call *p = pending_call_acquire(&table);
    pthread_barrier_wait(&acquired);
    pthread_barrier_wait(&release_calls);
    if (p) pending_call_release(&table, p);
    return NULL;
}

int main(void)
{
    pending_call_table_init(&table);
    struct pending_call *p = pending_call_acquire(&table);
    uint32_t cookie = pending_call_generate_xid(p);
    p->xid = 0x19283746; /* deliberately different protocol identity */
    pending_call_arm_timeout(p, 0);
    assert(pending_call_get_by_cookie(&table, cookie) == p);
    assert(!pending_call_get_by_cookie(&table, 0xffff));
    assert(!pending_call_try_claim_reply(p, cookie + 0x10000, p->xid));
    assert(!pending_call_try_claim_reply(p, cookie, p->xid + 1));
    assert(pending_call_try_claim_reply(p, cookie, p->xid));
    assert(!pending_call_try_claim_reply(p, cookie, p->xid));
    pending_call_release(&table, p);
    struct pending_call *q = pending_call_acquire(&table);
    assert(q == p);
    pending_call_arm_timeout(q, 0);
    assert(!pending_call_try_claim_reply(q, cookie, 0x19283746));
    pending_call_start_timeout(q, cookie, 1000);
    assert(!atomic_load(&q->timeout_ms));
    uint32_t next = pending_call_generate_xid(q);
    pending_call_start_timeout(q, next, 1000);
    assert(atomic_load(&q->timeout_ms) == 1000);
    lauberhorn_rpc_endpoint_t ep = {0};
    ep._transport.unresolved = 1;
    q->endpoint = &ep;
    q->hardware_done = false; /* a timeout cannot free the reservation */
    pending_call_release(&table, q);
    assert(ep._transport.unresolved == 1);
    q = pending_call_acquire(&table);
    q->endpoint = &ep;
    q->hardware_done = true;
    pending_call_release(&table, q);
    assert(ep._transport.unresolved == 0);
    q = pending_call_acquire(&table);
    q->gen = UINT16_MAX;
    pending_call_release(&table, q);
    struct pending_call *held[PENDING_CALL_LIMIT];
    for (unsigned i = 0; i < PENDING_CALL_LIMIT; ++i) {
        held[i] = pending_call_acquire(&table);
        assert(held[i] && held[i] != q); /* exhausted generations stay retired */
        held[i]->hardware_done = true;
        atomic_store(&held[i]->state, SLOT_READY);
    }
    /* Even received replies retain credit until software consumes them. */
    assert(!pending_call_acquire(&table));
    assert(atomic_load(&table.in_use) == PENDING_CALL_LIMIT);
    pending_call_release(&table, held[0]);
    held[0] = pending_call_acquire(&table);
    assert(held[0] && !pending_call_acquire(&table));
    for (unsigned i = 0; i < PENDING_CALL_LIMIT; ++i)
        pending_call_release(&table, held[i]);
    assert(!atomic_load(&table.in_use));
    /* Simultaneous worker submissions must share the same process-wide cap. */
    enum { N = PENDING_CALL_LIMIT * 2 };
    pthread_t threads[N];
    pthread_barrier_init(&acquired, NULL, N + 1);
    pthread_barrier_init(&release_calls, NULL, N + 1);
    for (unsigned trial = 0; trial < 8; ++trial) {
        for (unsigned i = 0; i < N; ++i)
            assert(!pthread_create(&threads[i], NULL, race_acquire, NULL));
        pthread_barrier_wait(&acquired);
        assert(atomic_load(&table.in_use) == PENDING_CALL_LIMIT);
        assert(!pending_call_acquire(&table));
        pthread_barrier_wait(&release_calls);
        for (unsigned i = 0; i < N; ++i) pthread_join(threads[i], NULL);
        assert(!atomic_load(&table.in_use));
    }
    pthread_barrier_destroy(&acquired);
    pthread_barrier_destroy(&release_calls);
}
