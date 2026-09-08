#ifndef FIBER_H
#define FIBER_H

#include <pthread.h>
#include <stdatomic.h>
#include <ucontext.h>
#include "lauberhorn.h"

#include "vstate.h"

/**
 * Fibers can be run on any worker thread, and may be rescheduled on a
 * different worker thread after yielding.
 *
 * Any RPC handler running in a fiber that that yields must
 * be reentrant and thread-safe
 */

typedef enum {
    FIBER_RUNNING = 0, 
    FIBER_PARKING, 
    FIBER_PARKED,
    FIBER_NOTIFY,
	FIBER_FREE,
	FIBER_DONE,
} fiber_state_t;

typedef uint32_t fiber_gen_t; 


struct fiber {
	vstate_t vstate; 

	ucontext_t ctx;
	int id;

	// called in fiber context, on start
	void (*on_start)(void *ctx);

	// called in scheduler, after exit
	void(*on_done)(void* ctx);

	void* args; 

	void* stack;
	int stack_size;

	void (*trampoline)(void);
};

// initialize a fiber with the given stack size
int fiber_init(struct fiber* f, size_t stack_size);

// free previously allocated fiber
void fiber_free(struct fiber* f);

// check if a handler still points to a valid fiber
bool fiber_handle_is_stale(struct fiber_handle *h);

#endif	// FIBER_H