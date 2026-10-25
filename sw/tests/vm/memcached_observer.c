/* SPDX-License-Identifier: BSD-3-Clause */
#define _GNU_SOURCE
#include <lauberhorn/tcp_transport.h>
#include <assert.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include "vm/abi.h"
/* Test-only preload. This ordinary-CPU observer reads scheduler state; it
 * never services packets, publishes a deadline or wakes an application. */
static int domain_fd;
static atomic_uint workers;
static _Thread_local unsigned int worker_index;
static void *observe(void *arg)
{
    const char *path = arg;
    char *temporary;
    assert(asprintf(&temporary, "%s.tmp", path) >= 0);
    for (;;) {
        struct lh_vm_schedule_state s;
        assert(!ioctl(domain_fd, LH_VM_SCHEDULE_STATE, &s));
        FILE *f = fopen(temporary, "w");
        assert(f);
        fprintf(f, "{\"assigned\":%u,\"reserved\":%u,\"queued\":%u,"
            "\"capacity\":%u,\"registered\":%u,\"workers\":%u,\"deadline_activations\":%llu}\n",
            s.assigned, s.reserved, s.queued, s.capacity, s.registered,
            atomic_load(&workers), (unsigned long long)s.deadline);
        assert(!fclose(f));
        assert(!rename(temporary, path));
        usleep(TEST_MC_OBSERVER_US);
    }
}
int lauberhorn_init(lauberhorn_t *context)
{
    int (*next)(lauberhorn_t *) = dlsym(RTLD_NEXT, "lauberhorn_init");
    assert(next);
    int result = next(context);
    const char *path = getenv("LH_VM_STATE_PATH");
    if (!result && path) {
        domain_fd = context->fd;
        pthread_t thread;
        assert(!pthread_create(&thread, NULL, observe, (void *)path));
        assert(!pthread_detach(thread));
    }
    return result;
}
int lauberhorn_tcp_attach(lauberhorn_t *context, uint32_t index)
{
    int (*next)(lauberhorn_t *, uint32_t) = dlsym(RTLD_NEXT, "lauberhorn_tcp_attach");
    assert(next);
    worker_index = index;
    return next(context, index);
}
int lauberhorn_tcp_receive(struct lauberhorn_tcp_event *event)
{
    int (*next)(struct lauberhorn_tcp_event *) = dlsym(RTLD_NEXT, "lauberhorn_tcp_receive");
    assert(next);
    int result = next(event);
    if (result > 0 && event->data) atomic_fetch_or(&workers, 1u << worker_index);
    return result;
}
