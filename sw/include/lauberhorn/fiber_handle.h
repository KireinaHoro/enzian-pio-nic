#ifndef LAUBERHORN_RT_FIBER_HANDLE_H
#define LAUBERHORN_RT_FIBER_HANDLE_H

#include <stdint.h>

// represents an instance of a fiber at a current generation
struct fiber_handle {
	struct fiber *f;
	 uint32_t gen; // vgen_t
};


#endif // LAUBERHORN_RT_FIBER_COMMON_H