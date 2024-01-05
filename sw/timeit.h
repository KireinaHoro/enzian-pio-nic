#ifndef __TIMEIT_H__
#define __TIMEIT_H__

#include <time.h>

#define TIMEIT_START(name) \
  struct timespec __name##_start, __name##_end; \
  clock_gettime(CLOCK_REALTIME, &__name##_start);

#define TIMEIT_END(name) \
  clock_gettime(CLOCK_REALTIME, &__name##_end);

#define TIMEIT_US(name) ((__name##_end.tv_sec - __name##_start.tv_sec) * 1e6 + (__name##_end.tv_nsec - __name##_start.tv_nsec) / 1e3)

#endif
