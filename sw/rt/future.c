#include <stdatomic.h>
#include <errno.h>
#include "lauberhorn/future.h"
#include <string.h>
#include "pending_call_table.h"
#include "fiber/scheduler.h"
#include "lauberhorn_state.h"

lauberhorn_future_t *future_set_await_any(lauberhorn_future_set_t *s) {
    if (!s || s->n <= 0) {
        return NULL;
    }

    int i = lauberhorn_future_select(s->f, s->n);
    if (i < 0) return NULL;
    lauberhorn_future_t *done = s->f[i];
    // remove element
    s->f[i] = s->f[--s->n]; 
    return done;
}

static int validate_wait(lauberhorn_future_t **f, int n) {
    if (!fiber_in_context()) { errno = EPERM; return -1; }
    if (!f || n <= 0) { errno = EINVAL; return -1; }
    struct fiber_handle owner = fiber_sched_current_handle();
    for (int i = 0; i < n; ++i) {
        if (!f[i] || !f[i]->c || f[i]->owner.f != owner.f ||
            f[i]->owner.gen != owner.gen ||
            f[i]->xid != pending_call_generate_xid(f[i]->c)) {
            errno = EINVAL; return -1;
        }
        for (int j = 0; j < i; ++j)
            if (f[j]->c == f[i]->c) { errno = EINVAL; return -1; }
    }
    return 0;
}

// copy result ptr from pending call table into future and release table slot
static void lauberhorn_future_take(lauberhorn_future_t *f) {
    f->result = f->c->result; 
    f->c->result.data = NULL; 

    pending_call_release(&pending_call_table, f->c);
    f->c = NULL; 
}

int lauberhorn_future_await(lauberhorn_future_t *f) {
    if (validate_wait(&f, 1)) return -1;

    for (;;) {
        fiber_prepare_wait();
        if (pending_call_is_ready(f->c)) {
           fiber_cancel_wait();
           break; 
        } else {
            fiber_commit_wait();
        }
    }
    lauberhorn_future_take(f);
    return 0;
}

int lauberhorn_future_select(lauberhorn_future_t **f, int n) {
    if (validate_wait(f, n)) return -1;

    for (;;) {
        fiber_prepare_wait();
        for (int i = 0; i < n; i++) {
            if (pending_call_is_ready(f[i]->c)) {
                fiber_cancel_wait();
                // copy result into fiber's stack
                lauberhorn_future_take(f[i]);
                return i;
            }
        }
        fiber_commit_wait();
    }
}

int lauberhorn_future_join(lauberhorn_future_t **f, int n) {
    if (validate_wait(f, n)) return -1;

    for (;;) {
        fiber_prepare_wait();

        bool all_ready = true; 
        for (int i = 0; i < n; i++) {
            if (!pending_call_is_ready(f[i]->c)) {
                all_ready = false; 
                break; 
            }
        }
        if (all_ready) {
            // extract results 
            // TODO: suboptimal as all call slots will stay reserved
            // until all futures are ready
            // Add flag to future if it already has result or not
            for (int i = 0; i < n; i++) {
                lauberhorn_future_take(f[i]);
            }
            fiber_cancel_wait();
            break; 
        } 
        fiber_commit_wait();
    }

    return 0;
}

bool lauberhorn_future_cancel(lauberhorn_future_t *f) {
    /* No cancellation/retirement protocol yet. Do not inspect an untrusted or
     * consumed future as though cancellation were implemented. */
    (void)f;
    errno = ENOTSUP;
    return false;
}
