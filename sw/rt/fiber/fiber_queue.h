#ifndef LAUBERHORN_RT_FIBER_QUEUE_H
#define LAUBERHORN_RT_FIBER_QUEUE_H

#include "fiber.h"
#include "fiber_pool.h" // included for NUM_FIBERS...

// queue is as long as max. number of fibers
struct fiber_queue {
	struct fiber* fibers[NUM_FIBERS];
	int head;
	int tail;
};

struct fiber* fiber_queue_pop(struct fiber_queue* queue);
void fiber_queue_init(struct fiber_queue* queue);
bool fiber_queue_push(struct fiber_queue* queue, struct fiber* f);
bool fiber_queue_is_empty(struct fiber_queue* queue);

#endif	// FIBER_QUEUE_H