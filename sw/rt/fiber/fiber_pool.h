#ifndef LAUBERHORN_RT_FIBER_POOL_H
#define LAUBERHORN_RT_FIBER_POOL_H

#include <stdatomic.h>

#include "fiber.h"
#include "utils/bitmap.h"

#define NUM_FIBERS (64 * 5)
#define FIBER_STACK_SIZE (64 * 1024) 

/**
 * pool with fixed number of preallocated fibers
 * uses a bitmap to track free/used fibers
 */
struct fiber_pool {
	struct fiber fibers[NUM_FIBERS];
	bitmap_t bitmap; 
};

/** acquire a fiber from the pool, returns NULL if no free fibers */
struct fiber* fiber_acquire(struct fiber_pool* pool);

/**
 * release a fiber back to the pool
 *
 * every released fiber must have been acquired before with
 * `fiber_pool_acquire`
 * */
void fiber_release(struct fiber_pool* pool, struct fiber* f);

void fiber_pool_init(struct fiber_pool* pool);

#endif	// FIBER_POOL_H