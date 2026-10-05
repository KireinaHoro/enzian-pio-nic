#ifndef LAUBERHORN_PMU_H
#define LAUBERHORN_PMU_H
#include <stdint.h>
/* Task-bound, pinned Linux perf group; user-only counts, never multiplexed. */
#define LH_PMU_EVENTS(X) X(instructions) X(cycles) X(l1d_refill) X(l2d_refill)
#define LH_PMU_COUNT 4
struct lh_pmu_snapshot { uint64_t value[LH_PMU_COUNT]; };
void lh_pmu_init(void);
void lh_pmu_read(struct lh_pmu_snapshot *snapshot);
#endif
