/* SPDX-License-Identifier: BSD-3-Clause */
/* No packet input: exercise the same public deadline ABI on VM and ECI. */
#include <lauberhorn/tcp_transport.h>
#include <lauberhorn/deadline.h>
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

static lauberhorn_t app;
static atomic_uint deliveries;
static atomic_uint_fast64_t observed;
static uint64_t now_ns(void)
{
    struct timespec now;
    assert(!clock_gettime(CLOCK_MONOTONIC, &now));
    return (uint64_t)now.tv_sec * 1000000000 + now.tv_nsec;
}
static void *worker(void *index)
{
    assert(!lauberhorn_tcp_attach(&app, (uintptr_t)index));
    for (;;) {
        struct lauberhorn_tcp_event event;
        int ret = lauberhorn_tcp_receive(&event);
        assert(ret >= 0);
        if (!ret) continue;
        assert(!event.data && event.deadline_generation);
        atomic_store(&observed, event.deadline_generation);
        atomic_fetch_add(&deliveries, 1);
    }
}
int main(void)
{
    assert(!lauberhorn_init(&app));
    assert(!lauberhorn_set_worker_count(&app, 4));
    pthread_t threads[4];
    for (uintptr_t i = 0; i < 4; ++i)
        assert(!pthread_create(&threads[i], NULL, worker, (void *)i));
    struct lauberhorn_deadline_state state;
    assert(!ioctl(app.fd, LAUBERHORN_IOCTL_GET_DEADLINE, &state));
    uint64_t generation = state.generation;
    struct lauberhorn_deadline_set too_soon = {
        .expected_generation = generation, .next_deadline_ns = now_ns()
    };
    assert(ioctl(app.fd, LAUBERHORN_IOCTL_SET_DEADLINE, &too_soon) == -1 && errno == ETIME);
    assert(!ioctl(app.fd, LAUBERHORN_IOCTL_GET_DEADLINE, &state));
    assert(state.generation == generation && !state.pending);
    for (unsigned int round = 1; round <= 3; ++round) {
        struct lauberhorn_deadline_set cmd = {
            .expected_generation = generation,
            .next_deadline_ns = now_ns() + TEST_DEADLINE_ARM_MS * 1000000ULL
        };
        assert(!ioctl(app.fd, LAUBERHORN_IOCTL_SET_DEADLINE, &cmd));
        generation = cmd.generation;
        uint64_t end = now_ns() + TEST_DEADLINE_TIMEOUT_MS * 1000000ULL;
        while (atomic_load(&deliveries) < round && now_ns() < end) usleep(1000);
        assert(atomic_load(&deliveries) == round);
        assert(atomic_load(&observed) == generation);
        assert(!ioctl(app.fd, LAUBERHORN_IOCTL_GET_DEADLINE, &state));
        assert(state.generation == generation && state.pending && state.delivered);
        struct lauberhorn_deadline_set stale = { .expected_generation = generation - 1 };
        assert(ioctl(app.fd, LAUBERHORN_IOCTL_SET_DEADLINE, &stale) == -1 && errno == ESTALE);
        usleep(TEST_DEADLINE_ARM_MS * 1000);
        assert(atomic_load(&deliveries) == round); /* sticky expiry, one delivery */
    }
    struct lauberhorn_deadline_set cancel = { .expected_generation = generation };
    assert(!ioctl(app.fd, LAUBERHORN_IOCTL_SET_DEADLINE, &cancel));
    assert(!ioctl(app.fd, LAUBERHORN_IOCTL_GET_DEADLINE, &state));
    assert(!state.pending && !state.delivered && !state.next_deadline_ns);
    puts("TRANSPORT_DEADLINE_PASS: no packets, three generations, single delivery, stale ACK, cancellation");
    return 0;
}
