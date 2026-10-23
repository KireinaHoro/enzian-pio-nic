/* SPDX-License-Identifier: BSD-3-Clause */
#define _GNU_SOURCE
#include <lauberhorn.h>
#include <lauberhorn/udp.h>
#include <lauberhorn/buffer.h>
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "vm/abi.h"
static lauberhorn_t app;
static lauberhorn_udp_endpoint_t endpoint;
static void *worker(void *arg)
{
    unsigned int id = (unsigned long)arg;
    assert(!lauberhorn_udp_attach(&app, id));
    for (;;) {
        const lauberhorn_udp_datagram_t *packet;
        lauberhorn_tx_buffer_t tx, duplicate;
        struct lh_activation activation;
        int ret = lauberhorn_udp_receive_view(&packet);
        assert(ret >= 0);
        if (!ret) continue;
        errno = 0;
        assert(lauberhorn_udp_receive_view(&packet) == -1 && errno == EBUSY);
        assert(lh_yield(&activation) == -1 && errno == EBUSY);
        lauberhorn_pkt_desc_t raw;
        assert(ioctl(app.fd, LH_VM_RX, &raw) == -1 && errno == EBUSY);
        assert(!lauberhorn_tx_get_buffer(false, &tx));
        assert(lauberhorn_tx_get_buffer(false, &duplicate) == -1 && errno == EBUSY);
        assert(ioctl(app.fd, LH_VM_TX_RESERVE) == -1 && errno == EBUSY);
        assert(lh_yield(&activation) == -1 && errno == EBUSY);
        assert(!lauberhorn_tx_cancel(&tx));
        assert(!lauberhorn_tx_get_buffer(false, &tx));
        assert(packet->length <= tx.capacity);
        memcpy(tx.data, packet->data, packet->length);
        if (packet->length && ((unsigned char *)packet->data)[0] == 0xd0) {
            int cpu = sched_getcpu();
            printf("SLEEP %u %d\n", id, cpu); fflush(stdout);
            usleep(200000);
            assert(sched_getcpu() == cpu);
        }
        assert(!lauberhorn_udp_send_buffer(&endpoint, packet->saddr, packet->sport,
                                          &tx, packet->length, packet->trace_ref));
        printf("RX %u %zu\n", id, packet->length); fflush(stdout);
        assert(!lauberhorn_udp_release());
        assert(lauberhorn_udp_release() == -1 && errno == EINVAL);
    }
}
int main(int argc, char **argv)
{
    pthread_t threads[4];
    unsigned int n = argc > 2 ? atoi(argv[2]) : 4;
    assert(n && n <= 4);
    assert(!lauberhorn_init(&app));
    assert(!lauberhorn_udp_bind(&app, &endpoint, argc > 1 ? atoi(argv[1]) : 19000));
    assert(!lauberhorn_set_worker_count(&app, n));
    lauberhorn_pkt_desc_t raw;
    assert(ioctl(app.fd, LH_VM_RX, &raw) == -1 && errno == EPERM);
    for (unsigned int i = 0; i < n; ++i)
        assert(!pthread_create(&threads[i], NULL, worker, (void *)(unsigned long)i));
    puts("READY"); fflush(stdout);
    for (unsigned int i = 0; i < n; ++i) pthread_join(threads[i], NULL);
}
