#include "fiber.h"

#include <stdatomic.h>
#include "lauberhorn/fiber_handle.h"

bool fiber_handle_is_stale(struct fiber_handle *h) {
	vgen_t gen_cur = vstate_gen(&h->f->vstate);
	return h->gen == gen_cur; 
}

int fiber_init(struct fiber* f, size_t stack_size) {
	static atomic_int next_fiber_id = 0;
	f->stack = malloc(stack_size);
	if (!f->stack) {
		return -1;
	}
	vstate_init(&f->vstate, FIBER_FREE);
	f->stack_size = stack_size;
	f->trampoline = NULL;
	f->args = NULL;
	f->on_start = NULL; 
	f->id = atomic_fetch_add(&next_fiber_id, 1);
	return 0;
}

void fiber_free(struct fiber* f) {
	free(f->stack);
}

