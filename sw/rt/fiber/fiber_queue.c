#include "fiber_pool.h"
#include "fiber_queue.h"

struct fiber* fiber_queue_pop(struct fiber_queue* queue) {
	if (fiber_queue_is_empty(queue)) {
		return NULL;
	}
	struct fiber* f = queue->fibers[queue->head];
	queue->fibers[queue->head] = NULL; 
	queue->head = (queue->head + 1) % NUM_FIBERS;
	return f;
}

void fiber_queue_init(struct fiber_queue* queue) {
	queue->head = 0;
	queue->tail = 0;
}

bool fiber_queue_push(struct fiber_queue* queue, struct fiber* f) {
	int next_tail = (queue->tail + 1) % NUM_FIBERS;
	if (next_tail == queue->head) {
		return false;
	}
	queue->fibers[queue->tail] = f;
	queue->tail = next_tail;
	return true;
}

bool fiber_queue_is_empty(struct fiber_queue* queue) {
	return queue->head == queue->tail;
}
