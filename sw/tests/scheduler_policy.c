/* Differential vectors for the actual VM allocator and SchedulerAllocation RTL. */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include "scheduler_policy.h"
static uint32_t rng = 0x6e624eb7;
static unsigned int random_word(void)
{
    rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5;
    return rng;
}
int main(void)
{
    struct lh_scheduler_cursor cursor = {0};
    unsigned int iteration, d, c;
    for (iteration = 0; iteration < 4096; ++iteration) {
        struct lh_scheduler_input s = {0};
        struct lh_scheduler_grant g;
        unsigned int enabled = 0, deadline = 0, available = 0, ready = 0;
        s.arrival = random_word() % (LAUBERHORN_NUM_PROCS + 1);
        s.arrival_fill = random_word() % (LAUBERHORN_RX_PKTS_PER_PROC + 1);
        for (d = 1; d <= LAUBERHORN_NUM_PROCS; ++d) {
            s.enabled[d] = random_word() % 4 != 0;
            s.deadline[d] = random_word() % 3 == 0;
            s.maximum[d] = random_word() % (LAUBERHORN_NUM_WORKER_CORES + 1);
            s.fill[d] = random_word() % (LAUBERHORN_RX_PKTS_PER_PROC + 1);
            enabled |= s.enabled[d] << d;
            deadline |= s.deadline[d] << d;
        }
        for (c = 0; c < LAUBERHORN_NUM_WORKER_CORES; ++c) {
            s.owner[c] = random_word() % (LAUBERHORN_NUM_PROCS + 1);
            s.available[c] = random_word() % 4 != 0;
            s.ready[c] = random_word() % 4 != 0;
            available |= s.available[c] << c;
            ready |= s.ready[c] << c;
        }
        g = lh_scheduler_allocate(&s, &cursor);
        printf("%u %u %u %u %u %u", s.arrival, s.arrival_fill, enabled, deadline, available, ready);
        for (d = 0; d <= LAUBERHORN_NUM_PROCS; ++d) printf(" %u", s.maximum[d]);
        for (d = 0; d <= LAUBERHORN_NUM_PROCS; ++d) printf(" %u", s.fill[d]);
        for (c = 0; c < LAUBERHORN_NUM_WORKER_CORES; ++c) printf(" %u", s.owner[c]);
        printf(" %u %d %u\n", g.slot, g.core, g.reason);
    }
    return 0;
}
