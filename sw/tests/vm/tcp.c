/* SPDX-License-Identifier: BSD-3-Clause */
#define _GNU_SOURCE
#include <lauberhorn/tcp_runtime.h>
#include <arpa/inet.h>
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include "vm/abi.h"
static atomic_uint worker_mask, holding, gate_requested, gate_released;
static atomic_uint_fast64_t readiness_generation;
static int controlled;
static void hold_handler(bool gated)
{
    struct timespec now, end;
    assert(!clock_gettime(CLOCK_MONOTONIC, &end));
    unsigned int duration = gated ? TEST_TCP_GATE_MS : TEST_TCP_HOLD_MS;
    unsigned int ticket = gated ? atomic_fetch_add(&gate_requested, 1) + 1 : 0;
    end.tv_nsec += (duration % 1000) * 1000000L;
    end.tv_sec += duration / 1000 + end.tv_nsec / 1000000000L;
    end.tv_nsec %= 1000000000L;
    atomic_fetch_add(&holding, 1);
    do {
        if (gated && atomic_load(&gate_released) >= ticket) break;
        assert(!clock_gettime(CLOCK_MONOTONIC, &now));
    } while (now.tv_sec < end.tv_sec ||
             (now.tv_sec == end.tv_sec && now.tv_nsec < end.tv_nsec));
    assert(!gated || atomic_load(&gate_released) >= ticket);
    atomic_fetch_sub(&holding, 1);
}
static lauberhorn_t app;
static lh_tcp_handle listener;
static pthread_mutex_t accepting = PTHREAD_MUTEX_INITIALIZER;
struct connection {
    pthread_mutex_t lock;
    lh_tcp_handle handle;
    unsigned int length, offset;
    unsigned char data[TEST_TCP_BUFFER];
};
static struct connection connections[TEST_TCP_CONNECTIONS];
static void service(void)
{
    if (!pthread_mutex_trylock(&accepting)) {
        for (unsigned int i = 0; i < TEST_TCP_CONNECTIONS; ++i) {
            struct connection *c = &connections[i];
            if (pthread_mutex_trylock(&c->lock)) continue;
            enum lh_tcp_result r = LH_TCP_OK;
            if (!c->handle) {
                r = lh_tcp_accept(listener, &c->handle);
                if (r == LH_TCP_RESET) { puts("TCP_ACCEPT_RESET"); fflush(stdout); }
                assert(r == LH_TCP_OK || r == LH_TCP_AGAIN ||
                       r == LH_TCP_RESET || r == LH_TCP_NO_MEMORY);
                c->length = c->offset = 0;
            }
            pthread_mutex_unlock(&c->lock);
            if (r == LH_TCP_AGAIN || r == LH_TCP_NO_MEMORY) break;
        }
        pthread_mutex_unlock(&accepting);
    }
    for (unsigned int i = 0; i < TEST_TCP_CONNECTIONS; ++i) {
        struct connection *c = &connections[i];
        if (pthread_mutex_trylock(&c->lock)) continue;
        while (c->handle) {
            unsigned int count;
            enum lh_tcp_result r;
            if (c->offset < c->length) {
                r = lh_tcp_write(c->handle, c->data + c->offset, c->length - c->offset, &count);
                if (r == LH_TCP_AGAIN) break;
                if (r != LH_TCP_OK) goto close;
                assert(count); c->offset += count;
                continue;
            }
            c->length = c->offset = 0;
            r = lh_tcp_read(c->handle, c->data, sizeof(c->data), &count);
            if (r == LH_TCP_AGAIN) break;
            if (r != LH_TCP_OK || !count) goto close;
            c->length = count;
            /* Deliberately slow application work, outside all TCP locks. */
            if (controlled && (c->data[0] == 0xd0 || c->data[0] == 0xd1))
                hold_handler(c->data[0] == 0xd1);
            continue;
close:
            assert(lh_tcp_close(c->handle) == LH_TCP_OK);
            c->handle = 0;
        }
        pthread_mutex_unlock(&c->lock);
    }
}
static void *worker(void *arg)
{
    unsigned int index = (unsigned long)arg;
    assert(!lh_tcp_runtime_attach(index));
    for (;;) {
        uint64_t observed = atomic_load(&readiness_generation);
        service();
        /* Input skipped by a busy connection owner must be rescanned before
         * that owner can park, just as in the memcached adapter. */
        if (observed != atomic_load(&readiness_generation)) continue;
        int result = lh_tcp_runtime_step();
        if (result < 0) { perror("TCP runtime step"); abort(); }
        if (result) {
            atomic_fetch_add(&readiness_generation, 1);
            unsigned int previous = atomic_fetch_or(&worker_mask, 1u << index);
            if (!(previous & (1u << index))) {
                printf("TCP_WORK %u\n", index); fflush(stdout);
            }
        }
    }
}
int main(int argc, char **argv)
{
    assert(argc == 3);
    controlled = getenv("LH_VM_STATE_PATH") != NULL;
    struct lh_tcp_config config;
    assert(inet_pton(AF_INET, argv[1], &config.address) == 1);
    assert(inet_pton(AF_INET, "255.255.255.0", &config.netmask) == 1);
    unsigned short port = atoi(argv[2]);
    assert(!lauberhorn_init(&app));
    assert(!lauberhorn_set_worker_count(&app, lh_tcp_worker_count()));
    assert(!lh_tcp_runtime_init(&app, &config, port));
    if (getenv("LH_VM_TCP_RESOURCE_CHECK")) {
        lh_tcp_handle *held = calloc(TEST_TCP_SOCKET_LIMIT, sizeof(*held));
        assert(held && TEST_TCP_SOCKET_LIMIT < 50000);
        unsigned int count = 0;
        for (; count < TEST_TCP_SOCKET_LIMIT; ++count) {
            enum lh_tcp_result result = lh_tcp_listen(10000 + count, 1, &held[count]);
            if (result == LH_TCP_NO_MEMORY) break;
            assert(result == LH_TCP_OK);
        }
        assert(count);
        lh_tcp_handle overflow;
        assert(lh_tcp_listen(port, 1, &overflow) == LH_TCP_NO_MEMORY && !overflow);
        for (unsigned int i = 0; i < count; ++i)
            assert(lh_tcp_close(held[i]) == LH_TCP_OK);
        free(held);
        puts("TCP_RESOURCE_RECOVERY_PASS");
    }
    assert(lh_tcp_listen(port, TEST_TCP_CONNECTIONS, &listener) == LH_TCP_OK);
    for (unsigned int i = 0; i < TEST_TCP_CONNECTIONS; ++i)
        assert(!pthread_mutex_init(&connections[i].lock, NULL));
    pthread_t threads[4];
    for (unsigned long i = 0; i < lh_tcp_worker_count(); ++i)
        assert(!pthread_create(&threads[i], NULL, worker, (void *)i));
    puts("TCP_READY"); fflush(stdout);
    const char *path = getenv("LH_VM_STATE_PATH");
    if (path) {
        char *temporary, *release_path;
        assert(asprintf(&temporary, "%s.tmp", path) >= 0);
        assert(asprintf(&release_path, "%s.release", path) >= 0);
        /* Observe scheduling and release test gates; never run protocol work
         * or publish timers from this ordinary-CPU thread. */
        for (;;) {
            FILE *release = fopen(release_path, "r");
            if (release) {
                unsigned int ticket;
                if (fscanf(release, "%u", &ticket) == 1)
                    atomic_store(&gate_released, ticket);
                fclose(release);
            }
            struct lh_vm_schedule_state s;
            assert(!ioctl(app.fd, LH_VM_SCHEDULE_STATE, &s));
            FILE *f = fopen(temporary, "w");
            assert(f);
            fprintf(f, "{\"assigned\":%u,\"reserved\":%u,\"queued\":%u,"
                "\"deadline_activations\":%llu,\"workers\":%u,\"holding\":%u}\n",
                s.assigned, s.reserved, s.queued, (unsigned long long)s.deadline,
                atomic_load(&worker_mask), atomic_load(&holding));
            assert(!fclose(f));
            assert(!rename(temporary, path));
            usleep(TEST_TCP_STATE_US);
        }
    }
    for (unsigned int i = 0; i < lh_tcp_worker_count(); ++i) pthread_join(threads[i], NULL);
}
