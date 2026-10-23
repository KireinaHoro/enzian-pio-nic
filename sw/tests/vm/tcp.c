/* SPDX-License-Identifier: BSD-3-Clause */
#define _GNU_SOURCE
#include <lauberhorn/tcp_runtime.h>
#include <arpa/inet.h>
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
        if (result) { printf("TCP_WORK %u\n", index); fflush(stdout); }
    }
}
int main(int argc, char **argv)
{
    assert(argc == 3);
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
    for (unsigned int i = 0; i < lh_tcp_worker_count(); ++i) pthread_join(threads[i], NULL);
}
