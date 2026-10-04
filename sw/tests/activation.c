/* Test the real runtime activation boundary with a controlled ioctl provider. */
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>
#include "worker.h"
#include "ioctl.h"

static struct lauberhorn_worker worker;
static lauberhorn_t context;
static struct lh_activation offered;
static int ioctl_error, calls;
bool server_trace_enabled;
__thread struct server_activation_trace server_activation;
static uint64_t tick;
uint64_t server_trace_clock(void) { return ++tick; }
lauberhorn_worker_t worker_get_current(void) { return &worker; }
int lauberhorn_stop(lauberhorn_t *ctx) { assert(ctx == &context); return 0; }
int ioctl(int fd, unsigned long command, ...)
{
    assert(fd == 42 && command == LAUBERHORN_IOCTL_YIELD);
    ++calls;
    if (ioctl_error) { errno = ioctl_error; return -1; }
    va_list args;
    va_start(args, command);
    *va_arg(args, struct lh_activation *) = offered;
    va_end(args);
    return 0;
}
int main(void)
{
    context.fd = 42;
    context._aliases = calloc(LAUBERHORN_NUM_WORKER_CORES, LAUBERHORN_ECI_CORE_OFFSET);
    assert(context._aliases);
    worker.ctx = &context;
    struct lh_activation result = {99, 99, 99};
    offered = (struct lh_activation){LAUBERHORN_NUM_WORKER_CORES - 1, 1, 0};
    assert(!lh_yield(&result));
    assert(worker.dp_base == (char *)context._aliases + offered.worker_index * LAUBERHORN_ECI_CORE_OFFSET);
    assert(worker.rx_next == 1 && worker.tx_next == 0);
    assert(tick == 0 && server_activation.index == 0);
    server_trace_enabled = true;
    // Migration must replace both parities, even after arbitrary previous state.
    worker.rx_next = worker.tx_next = 1;
    offered = (struct lh_activation){0, 0, 1};
    assert(!lh_yield(&result));
    assert(worker.dp_base == context._aliases);
    assert(worker.rx_next == 0 && worker.tx_next == 1);
    assert(result.worker_index == 0 && result.rx_next == 0 && result.tx_next == 1);
    assert(server_activation.tid == getpid());
    assert(server_activation.worker == 0 && server_activation.index == 1);
    assert(server_activation.ioctl_exit_ns == 1 && server_activation.ready_ns == 2);
    ioctl_error = ESHUTDOWN;
    assert(lh_yield(&result) == -1 && errno == ESHUTDOWN);
    assert(worker.dp_base == context._aliases && worker.rx_next == 0 && worker.tx_next == 1);
    assert(tick == 2 && server_activation.index == 1);
    int previous = calls;
    assert(lh_yield(NULL) == -1 && errno == EINVAL && calls == previous);
    ioctl_error = 0;
    offered.worker_index = LAUBERHORN_NUM_WORKER_CORES;
    pid_t child = fork();
    assert(child >= 0);
    if (!child) { lh_yield(&result); _exit(1); }
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGABRT);
    free(context._aliases);
    return 0;
}
