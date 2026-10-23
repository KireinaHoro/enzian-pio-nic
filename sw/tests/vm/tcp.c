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
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include "vm/abi.h"
static atomic_uint worker_mask, holding;
static int controlled;
static void hold_handler(void)
{
    struct timespec now, end;
    assert(!clock_gettime(CLOCK_MONOTONIC, &end));
    end.tv_nsec += (TEST_TCP_HOLD_MS % 1000) * 1000000L;
    end.tv_sec += TEST_TCP_HOLD_MS / 1000 + end.tv_nsec / 1000000000L;
    end.tv_nsec %= 1000000000L;
    atomic_fetch_add(&holding, 1);
    do {
        assert(!clock_gettime(CLOCK_MONOTONIC, &now));
    } while (now.tv_sec < end.tv_sec ||
             (now.tv_sec == end.tv_sec && now.tv_nsec < end.tv_nsec));
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
                assert(r == LH_TCP_OK || r == LH_TCP_AGAIN);
                c->length = c->offset = 0;
            }
            pthread_mutex_unlock(&c->lock);
            if (r == LH_TCP_AGAIN) break;
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
            if (controlled && c->data[0] == 0xd0) hold_handler();
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
        service();
        int result = lh_tcp_runtime_step();
        if (result < 0) { perror("TCP runtime step"); abort(); }
        if (result) { atomic_fetch_or(&worker_mask, 1u << index); printf("TCP_WORK %u\n", index); fflush(stdout); }
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
    assert(lh_tcp_listen(port, TEST_TCP_CONNECTIONS, &listener) == LH_TCP_OK);
    for (unsigned int i = 0; i < TEST_TCP_CONNECTIONS; ++i)
        assert(!pthread_mutex_init(&connections[i].lock, NULL));
    pthread_t threads[4];
    for (unsigned long i = 0; i < lh_tcp_worker_count(); ++i)
        assert(!pthread_create(&threads[i], NULL, worker, (void *)i));
    puts("TCP_READY"); fflush(stdout);
    const char *path = getenv("LH_VM_STATE_PATH");
    if (path) {
        char *temporary;
        assert(asprintf(&temporary, "%s.tmp", path) >= 0);
        /* Read-only observer: never runs protocol work or publishes timers. */
        for (;;) {
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
