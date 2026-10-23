/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LAUBERHORN_ACTIVATION_TIMING_H
#define LAUBERHORN_ACTIVATION_TIMING_H
#include <linux/types.h>
enum lh_activation_stamp {
    LH_ACT_IRQ, LH_ACT_DISPATCH, LH_ACT_READ_BEGIN, LH_ACT_READ_END,
    LH_ACT_CLAIM, LH_ACT_AFFINITY, LH_ACT_WAKE, LH_ACT_RESUME,
    LH_ACT_RETURN, LH_ACT_YIELD_ENTER, LH_ACT_STAMPS
};
/* Private instrumentation only: never copied through the activation ABI. */
struct lh_activation_timing {
    u64 ns[LH_ACT_STAMPS], sequence;
    int worker, outgoing_tid;
    bool enabled, cold;
};
#endif
