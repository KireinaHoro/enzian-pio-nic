
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>

#include "fiber.h"
#include "fiber_queue.h"
#include "scheduler.h"	

// per thread main fiber (scheduler), currently running fiber and run queue
__thread struct fiber* current_fiber;
__thread struct fiber* main_fiber;
__thread struct fiber_queue ready_fiber_queue;

// global fiber pool
static struct fiber_pool fiber_pool;
static pthread_once_t fiber_pool_once = PTHREAD_ONCE_INIT;


static void fiber_pool_init_once(void) {
	fiber_pool_init(&fiber_pool);
}

// acquire a fiber
struct fiber *fiber_sched_acquire() {
	return fiber_acquire(&fiber_pool);
}

void switch_to(struct fiber* f) {
	struct fiber* prev = current;
	current = f;
	swapcontext(&prev->ctx, &f->ctx);
}

void fiber_exit() {
	vstate_set(&current->vstate, FIBER_DONE);
	switch_to(main_fiber);
}

void fiber_sched_init() {
	// initialize state
	pthread_once(&fiber_pool_once, fiber_pool_init_once);
	fiber_queue_init(&ready_fiber_queue);

	// should only be called once per thread
	assert(!current && "scheduler already initialized for this thread");
	main_fiber = malloc(sizeof(struct fiber));
	fiber_init(main_fiber, 0);
	current = main_fiber;
	getcontext(&main_fiber->ctx);
}

void fiber_sched_fini() {
	fiber_free(main_fiber);
	free(main_fiber);
	main_fiber = NULL;
}

// submit a fiber to be scheduled
void fiber_sched_submit(struct fiber *f) {
	fiber_queue_push(&ready_fiber_queue, f);
}

static void sched_trampoline() {
	current->on_start(current->args);
	fiber_exit();
	assert(0 && "should never return from fiber_exit");
}

static void run_fiber(struct fiber *f) {
    vstate_set(&f->vstate, FIBER_RUNNING);
    switch_to(f);

    // check return state of fiber after it yields back
    switch (vstate_state(&f->vstate)) {
    case FIBER_DONE:
        if (f->on_done) {
            f->on_done(f->args);
        }
		// mark as free, bump generation and release back to pool
		vstate_reinit(&f->vstate, FIBER_FREE);
        fiber_release(&fiber_pool, f);
        return;

	case FIBER_PARKING:
		if (vstate_cas_state(&f->vstate, FIBER_PARKING, FIBER_PARKED)) {
			// parked published
		} else {
			// somebody set FIBER_NOTIFY
			assert(vstate_state(&f->vstate) == FIBER_NOTIFY);
			vstate_set(&f->vstate, FIBER_RUNNING);
			fiber_queue_push(&ready_fiber_queue, f);
		}
        break;
	case FIBER_NOTIFY: 
		vstate_set(&f->vstate, FIBER_RUNNING);
		fiber_queue_push(&ready_fiber_queue, f);
		break; 

    default:
        assert(0 && "fiber in invalid state after switch");
    }
    assert(current == main_fiber);
}

// runs all ready fibers
void fiber_sched_run_ready() {
	struct fiber *f; 
  	while ((f = fiber_queue_pop(&ready_fiber_queue))) {
            run_fiber(f);
        }
}

void fiber_prepare(struct fiber* f, void(*on_start)(void *ctx), void(*on_done)(void* ctx), void *ctx) {
	getcontext(&f->ctx);
	f->args = ctx; 
	f->on_start = on_start;
	f->on_done = on_done;  
	f->ctx.uc_stack.ss_sp = f->stack;
	f->ctx.uc_stack.ss_size = f->stack_size;
	f->ctx.uc_link = NULL;

	// bump generation
	vstate_reinit(&f->vstate, FIBER_FREE);

	// set up trampoline to call the fiber's main function and then
	// yield back to scheduler when it finishes
	f->trampoline = sched_trampoline;
	makecontext(&f->ctx, (void (*)(void))sched_trampoline, 0);
}

struct fiber_handle fiber_sched_current_handle() {

	ASSERT_IN_FIBER_CONTEXT();

	return (struct fiber_handle) {
		.f = current, 
		.gen = vstate_gen(&current->vstate)
	};
}

void fiber_prepare_wait() {
	ASSERT_IN_FIBER_CONTEXT();
	vstate_set(&current->vstate, FIBER_PARKING);
}

void fiber_cancel_wait() {
	ASSERT_IN_FIBER_CONTEXT();
	vstate_set(&current->vstate, FIBER_RUNNING); 
}

void fiber_commit_wait() {
	ASSERT_IN_FIBER_CONTEXT();
    switch_to(main_fiber);
}

bool fiber_wake(struct fiber_handle *h) {
	
	while (true) {

		vgen_t gen_cur = vstate_gen(&h->f->vstate);
		vgen_t gen_handle = h->gen; 

		fiber_state_t state_cur = vstate_state(&h->f->vstate);

		if (gen_cur != gen_handle) {
			return false; 
		}

		// fiber was parked -> try to set it to running
		if (state_cur == FIBER_PARKED) {
			if (vstate_cas(&h->f->vstate, gen_handle, FIBER_PARKED, FIBER_RUNNING)) {
				fiber_queue_push(&ready_fiber_queue, h->f);
				return true; 
			}
		} else if (state_cur == FIBER_PARKING || state_cur == FIBER_RUNNING) {
			if (vstate_cas(&h->f->vstate, gen_handle, state_cur, FIBER_NOTIFY)) {
				return true;
			}
		} else {
			// return without waking
			return false; 
		}
	}
}
