#ifndef LAUBERHORN_RT_CLOCK_H
#define LAUBERHORN_RT_CLOCK_H

#include <stdint.h>
#include <time.h>

// calculates t2 - t1
static inline int64_t clock_diff_ms(struct timespec *t1, struct timespec *t2) {
	int64_t d_ns = t2->tv_nsec - t1->tv_nsec;
	int64_t d_s = t2->tv_sec - t1->tv_sec; 
	return d_ns  / 1000000LL + d_s * 1000;
}

static inline struct timespec now() {
	struct timespec t; 
    clock_gettime(CLOCK_MONOTONIC, &t);
	return t; 
}
#endif // LAUBERHORN_RT_CLOCK_H