/* SPDX-License-Identifier: BSD-3-Clause */
#include <errno.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/syscall.h>
#include "worker.h"
#include "ioctl.h"

int lh_yield(struct lh_activation *result) {
    lauberhorn_worker_t w = worker_get_current();
    struct lh_activation activation;
    if (!w || !result) { errno = EINVAL; return -1; }
    // Resolve TID before sleeping, not on the measured activation return path.
    if (server_trace_enabled && !server_activation.tid)
        server_activation.tid = (int)syscall(SYS_gettid);
    if (ioctl(w->ctx->fd, LAUBERHORN_IOCTL_YIELD, &activation)) return -1;
    if (server_trace_enabled) server_activation.ioctl_exit_ns = server_trace_clock();
    if (activation.worker_index >= LAUBERHORN_NUM_WORKER_CORES ||
        activation.rx_next > 1 || activation.tx_next > 1) {
        lauberhorn_stop(w->ctx);
        abort();
    }
    w->dp_base = (char *)w->ctx->_aliases +
        activation.worker_index * LAUBERHORN_ECI_CORE_OFFSET;
    w->rx_next = activation.rx_next;
    w->tx_next = activation.tx_next;
    *result = activation;
    if (server_trace_enabled) {
        server_activation.worker = activation.worker_index;
        ++server_activation.index;
        server_activation.ready_ns = server_trace_clock();
    }
    return 0;
}

