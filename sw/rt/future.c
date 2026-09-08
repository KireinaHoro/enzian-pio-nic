#include <stdatomic.h>
#include "lauberhorn/future.h"
#include <string.h>
#include "pending_call_table.h"
#include "fiber/scheduler.h"
#include "lauberhorn_state.h"

lauberhorn_future_t *future_set_await_any(lauberhorn_future_set_t *s) {
    if (s->n == 0) {
        return NULL;
    }

    int i = lauberhorn_future_select(s->f, s->n);
    lauberhorn_future_t *done = s->f[i];
    // remove element
    s->f[i] = s->f[--s->n]; 
    return done;
}

// copy result ptr from pending call table into future and release table slot
static void lauberhorn_future_take(lauberhorn_future_t *f) {
    f->result = f->c->result; 
    f->c->result.data = NULL; 

    pending_call_release(&pending_call_table, f->c);
    f->c = NULL; 
}

void lauberhorn_future_await(lauberhorn_future_t *f) {

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
}

int lauberhorn_future_select(lauberhorn_future_t **f, int n) {

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

void lauberhorn_future_join(lauberhorn_future_t **f, int n) {

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

}

bool lauberhorn_future_cancel(lauberhorn_future_t *f) {
    // TODO: not implemented yet
    if (!pending_call_try_cancel(f->c, f->xid)) {
        // cancel failed
        return false; 
    }
    // cancel succeeded
    return true; 
}
