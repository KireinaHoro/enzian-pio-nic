/* Pure timing/accounting contract, shared by the generator and fixture tests. */
#ifndef LH_OPEN_LOOP_H
#define LH_OPEN_LOOP_H
#include <stdint.h>
#include <stdbool.h>
/* Integer rational schedule avoids cumulative rounding drift. */
static inline uint64_t ol_due(uint64_t start, uint64_t slot, uint64_t rate) {
    return start + (uint64_t)((__uint128_t)slot * 1000000000 / rate);
}
static inline bool ol_missed(uint64_t now_ns, uint64_t start, uint64_t slot,
                             uint64_t rate, unsigned cores) {
    return now_ns >= ol_due(start, slot + cores, rate);
}
enum ol_state { OL_PENDING, OL_ONTIME, OL_EXPIRED, OL_MALFORMED, OL_LATE,
                OL_MISSED, OL_TX_FAILED };
/* Arrival timestamp is captured before forwarding or payload validation. */
static inline int ol_reply(int previous, uint64_t enter, uint64_t received,
                           uint64_t deadline, bool valid) {
    if (!valid || received < enter) return OL_MALFORMED;
    if (previous == OL_ONTIME || previous == OL_LATE) return previous;
    return received - enter <= deadline ? OL_ONTIME : OL_LATE;
}
#endif
