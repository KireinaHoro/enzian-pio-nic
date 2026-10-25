/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LH_SCHEDULER_POLICY_H
#define LH_SCHEDULER_POLICY_H
/* Pure device-event policy, mirrored by SchedulerAllocation.scala. Owner is
 * the reserved destination during handoff. Available excludes in-flight work. */
enum lh_allocation_reason { LH_INITIAL_PACKET, LH_PACKET_BACKLOG, LH_BACKGROUND, LH_DEADLINE };
struct lh_scheduler_input {
    unsigned int owner[LAUBERHORN_NUM_WORKER_CORES];
    bool available[LAUBERHORN_NUM_WORKER_CORES], ready[LAUBERHORN_NUM_WORKER_CORES];
    bool enabled[LAUBERHORN_NUM_PROCS + 1], deadline[LAUBERHORN_NUM_PROCS + 1];
    unsigned int maximum[LAUBERHORN_NUM_PROCS + 1], fill[LAUBERHORN_NUM_PROCS + 1];
    unsigned int arrival, arrival_fill;
};
struct lh_scheduler_cursor { unsigned int deadline; bool prefer_deadline; };
struct lh_scheduler_grant { unsigned int slot; int core; enum lh_allocation_reason reason; };
static inline unsigned int lh_scheduler_count(const struct lh_scheduler_input *s, unsigned int slot)
{
    unsigned int c, count = 0;
    for (c = 0; c < LAUBERHORN_NUM_WORKER_CORES; ++c) count += s->owner[c] == slot;
    return count;
}
static inline bool lh_scheduler_eligible(const struct lh_scheduler_input *s, unsigned int slot)
{
    return slot && s->enabled[slot] && lh_scheduler_count(s, slot) < s->maximum[slot];
}
static inline int lh_scheduler_victim(const struct lh_scheduler_input *s, unsigned int slot,
                                      enum lh_allocation_reason reason)
{
    unsigned int c, donor;
    for (c = 0; c < LAUBERHORN_NUM_WORKER_CORES; ++c)
        if (s->available[c] && !s->owner[c]) return c;
    if (reason == LH_INITIAL_PACKET) return -1;
    for (c = 0; c < LAUBERHORN_NUM_WORKER_CORES; ++c) {
        donor = s->owner[c];
        if (!s->available[c] || !s->ready[c] || !s->enabled[donor] || donor == slot) continue;
        if (reason == LH_DEADLINE && 2 * s->fill[donor] > LAUBERHORN_RX_PKTS_PER_PROC) continue;
        if (reason == LH_BACKGROUND && (s->fill[donor] || s->deadline[donor])) continue;
        return c;
    }
    return -1;
}
static inline struct lh_scheduler_grant lh_scheduler_allocate(const struct lh_scheduler_input *s,
                                                               struct lh_scheduler_cursor *cursor)
{
    struct lh_scheduler_grant packet = {0, -1, LH_INITIAL_PACKET}, timer = {0, -1, LH_DEADLINE};
    unsigned int slot, n;
    if (s->arrival && lh_scheduler_eligible(s, s->arrival)) {
        bool backlog = 2 * s->arrival_fill >= LAUBERHORN_RX_PKTS_PER_PROC;
        packet.reason = backlog ? LH_PACKET_BACKLOG : LH_INITIAL_PACKET;
        if (backlog || !lh_scheduler_count(s, s->arrival)) {
            packet.slot = s->arrival;
            packet.core = lh_scheduler_victim(s, packet.slot, packet.reason);
        }
    }
    for (n = 0; n < LAUBERHORN_NUM_PROCS; ++n) {
        slot = 1 + (cursor->deadline + n) % LAUBERHORN_NUM_PROCS;
        if (!s->deadline[slot] || !lh_scheduler_eligible(s, slot) || lh_scheduler_count(s, slot)) continue;
        timer.core = lh_scheduler_victim(s, slot, LH_DEADLINE);
        if (timer.core >= 0) { timer.slot = slot; break; }
    }
    if (packet.core >= 0 && (timer.core < 0 || !cursor->prefer_deadline)) {
        cursor->prefer_deadline = true;
        return packet;
    }
    if (timer.core >= 0) {
        cursor->deadline = timer.slot % LAUBERHORN_NUM_PROCS;
        cursor->prefer_deadline = false;
        return timer;
    }
    for (slot = 1; slot <= LAUBERHORN_NUM_PROCS; ++slot) {
        struct lh_scheduler_grant background = {slot, -1, LH_BACKGROUND};
        if (!s->fill[slot] || !lh_scheduler_eligible(s, slot) || lh_scheduler_count(s, slot)) continue;
        background.core = lh_scheduler_victim(s, slot, LH_BACKGROUND);
        if (background.core >= 0) return background;
    }
    return (struct lh_scheduler_grant){0, -1, LH_BACKGROUND};
}
#endif
