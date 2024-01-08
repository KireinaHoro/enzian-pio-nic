#ifndef __TIMEIT_H__
#define __TIMEIT_H__

#include <time.h>

#define TIMEIT_START(name) \
  struct timespec __##name##_start, __##name##_end; \
  clock_gettime(CLOCK_REALTIME, &__##name##_start);

#define TIMEIT_END(name) \
  clock_gettime(CLOCK_REALTIME, &__##name##_end);

#define TIMEIT_US(name) ((__##name##_end.tv_sec - __##name##_start.tv_sec) * 1e6 + (__##name##_end.tv_nsec - __##name##_start.tv_nsec) / 1e3)

#endif
