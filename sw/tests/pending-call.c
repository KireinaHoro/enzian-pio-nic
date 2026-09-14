#include "pending_call_table.h"
#include <assert.h>
static struct pending_call_table table;
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
    for (int i = 0; i < PENDING_CALL_TABLE_SIZE; ++i) assert(pending_call_acquire(&table));
    assert(!pending_call_acquire(&table));
}
