#ifndef SCHEDULER_H
#define SCHEDULER_H

#include "fiber.h"
#include "fiber_pool.h"
#include "fiber_queue.h"
#include "lauberhorn/fiber_handle.h"

#define ASSERT_IN_FIBER_CONTEXT() (assert(current && current != main_fiber && "fiber_sched_current_handle called outside fiber context"))

/* per thread pointer to the currently running fiber */
extern __thread struct fiber* current_fiber;

/* per thread pointer to the main fiber (scheduler context) */
extern __thread struct fiber* main_fiber;

/* per thread queue of ready fibers */
extern __thread struct fiber_queue ready_fiber_queue;

/** access the currently running fiber's state */
#define current (current_fiber)

void fiber_sched_init();

void fiber_sched_fini();

void fiber_sched_run_ready();

/* prepares a fiber and set its state to ready */
void fiber_prepare(struct fiber* f, void(*on_start)(void *ctx), void(*on_done)(void* ctx), void *ctx);

/* submit a fiber to the scheduler */
void fiber_sched_submit(struct fiber *f);

/* switch execution to the given fiber */
void switch_to(struct fiber* f);

/* get a handle to the currently running fiber */
struct fiber_handle fiber_sched_current_handle();

struct fiber *fiber_sched_acquire();

// called before checking a condition that could lead to a yield 
void fiber_prepare_wait();

// cancel prepared wait
void fiber_cancel_wait();

// commit wait and yield
void fiber_commit_wait();

// returns true if fiber was awoken 
// false if fiber has already exited or was not notifed for some other reason
bool fiber_wake(struct fiber_handle *h);

#endif	// SCHEDULER_H