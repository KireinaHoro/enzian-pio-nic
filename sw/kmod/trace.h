/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#undef TRACE_SYSTEM
#define TRACE_SYSTEM lauberhorn
#if !defined(_LAUBERHORN_TRACE_H) || defined(TRACE_HEADER_MULTI_READ)
#define _LAUBERHORN_TRACE_H
#include <linux/tracepoint.h>
#include "activation_timing.h"

/* One record after a successful yield, with cheap raw-clock reads at each
 * boundary. Enable this event before activation; disabled paths read no clocks.
 * All ns fields use CLOCK_MONOTONIC_RAW, matching runtime probes.
 */
TRACE_EVENT(activation,
    TP_PROTO(const struct lh_activation_timing *t, u32 domain, int tid),
    TP_ARGS(t, domain, tid),
    TP_STRUCT__entry(
        __field(int, worker) __field(u64, sequence) __field(u32, domain)
        __field(int, tid) __field(bool, cold)
        __array(u64, ns, LH_ACT_STAMPS)
    ),
    TP_fast_assign(
        __entry->worker = t->worker; __entry->sequence = t->sequence;
        __entry->domain = domain; __entry->tid = tid; __entry->cold = t->cold;
        memcpy(__entry->ns, t->ns, sizeof(__entry->ns));
    ),
    TP_printk("worker=%d sequence=%llu domain=%u tid=%d cold=%d irq=%llu dispatch=%llu read_begin=%llu read_end=%llu claim=%llu affinity=%llu wake=%llu resume=%llu return=%llu",
        __entry->worker, __entry->sequence, __entry->domain, __entry->tid, __entry->cold,
        __entry->ns[0], __entry->ns[1], __entry->ns[2], __entry->ns[3],
        __entry->ns[4], __entry->ns[5], __entry->ns[6], __entry->ns[7], __entry->ns[8])
);

/* Legacy event: only phase 2 (activation published, before wake_up) is emitted. */
TRACE_EVENT(worker_switch,
    TP_PROTO(int cpu, u64 old_lifetime, int old_pid, int old_tid,
             u64 next_lifetime, int next_pid, int next_tid, int phase, bool killed),
    TP_ARGS(cpu, old_lifetime, old_pid, old_tid, next_lifetime, next_pid, next_tid, phase, killed),
    TP_STRUCT__entry(
        __field(int, cpu) __field(u64, old_lifetime) __field(int, old_pid) __field(int, old_tid)
        __field(u64, next_lifetime) __field(int, next_pid) __field(int, next_tid)
        __field(int, phase) __field(bool, killed)
    ),
    TP_fast_assign(
        __entry->cpu = cpu; __entry->old_lifetime = old_lifetime;
        __entry->old_pid = old_pid; __entry->old_tid = old_tid;
        __entry->next_lifetime = next_lifetime; __entry->next_pid = next_pid;
        __entry->next_tid = next_tid; __entry->phase = phase; __entry->killed = killed;
    ),
    TP_printk("cpu=%d old=%llu:%d/%d next=%llu:%d/%d phase=%d killed=%d",
        __entry->cpu, __entry->old_lifetime, __entry->old_pid, __entry->old_tid,
        __entry->next_lifetime, __entry->next_pid, __entry->next_tid, __entry->phase, __entry->killed)
);
/* phase: 0 stopping, 1 workers/queues drained, 2 all ownership retired.
 * reason: 0 close, 1 explicit/unmap, 2 hardware kill, 3 fatal signal. */
TRACE_EVENT(process_recovery,
    TP_PROTO(u64 lifetime, int pid, int slot, int phase, int reason),
    TP_ARGS(lifetime, pid, slot, phase, reason),
    TP_STRUCT__entry(__field(u64, lifetime) __field(int, pid) __field(int, slot)
                     __field(int, phase) __field(int, reason)),
    TP_fast_assign(__entry->lifetime = lifetime; __entry->pid = pid; __entry->slot = slot;
                   __entry->phase = phase; __entry->reason = reason;),
    TP_printk("lifetime=%llu pid=%d slot=%d phase=%d reason=%d",
              __entry->lifetime, __entry->pid, __entry->slot, __entry->phase, __entry->reason)
);
#endif
#undef TRACE_INCLUDE_PATH
#define TRACE_INCLUDE_PATH .
#undef TRACE_INCLUDE_FILE
#define TRACE_INCLUDE_FILE trace
#include <trace/define_trace.h>
