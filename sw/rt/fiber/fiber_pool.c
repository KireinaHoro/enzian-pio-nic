#include "fiber_pool.h"

struct fiber* fiber_acquire(struct fiber_pool* pool) {
	// acquires a free fiber slot
	// slot is guaranteed not to be reused until fiber_pool_release it
	int idx = bitmap_acquire(&pool->bitmap);
	if (idx < 0) {
		return NULL;
	}
	return &pool->fibers[idx];
}

void fiber_release(struct fiber_pool* pool, struct fiber* f) {
	int idx = f - pool->fibers;
	bitmap_release(&pool->bitmap, idx);
}

void fiber_pool_init(struct fiber_pool* pool) {
	bitmap_init(&pool->bitmap, NUM_FIBERS / 64);
	for (int i = 0; i < NUM_FIBERS; i++) {
		fiber_init(&pool->fibers[i], FIBER_STACK_SIZE);
	}
}