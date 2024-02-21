#ifndef __TIMEIT_H__
#define __TIMEIT_H__

// #define TIMEIT_USE_SYSTEM
#ifdef TIMEIT_USE_SYSTEM

#include <time.h>

#define TIMEIT_START(ctx, name) \
  struct timespec __##name##_start, __##name##_end; \
  clock_gettime(CLOCK_REALTIME, &__##name##_start);

#define TIMEIT_END(ctx, name) \
  clock_gettime(CLOCK_REALTIME, &__##name##_end);

#define TIMEIT_US(name) ((__##name##_end.tv_sec - __##name##_start.tv_sec) * 1e6 + (__##name##_end.tv_nsec - __##name##_start.tv_nsec) / 1e3)

#else

#include "../../hw/gen/pcie/regs.h"

#define TIMEIT_START(ctx, name) \
  uint64_t __##name##_start, __##name##_end; \
  __##name##_start = read64(ctx, PIONIC_GLOBAL_CYCLES_COUNT);

#define TIMEIT_END(ctx, name) \
  __##name##_end = read64(ctx, PIONIC_GLOBAL_CYCLES_COUNT);

#define TIMEIT_US(name) (CYCLES_TO_US(__##name##_end - __##name##_start))

#endif // TIMEIT_USE_SYSTEM

#endif
