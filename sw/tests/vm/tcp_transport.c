/* SPDX-License-Identifier: BSD-3-Clause */
#include <lauberhorn/tcp_transport.h>
#include <assert.h>
#include <errno.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
static lauberhorn_t app;
static struct lauberhorn_tcp_endpoint endpoint = { .address = 0x7f000001, .port = 19500 };
static unsigned int sum(const unsigned char *p, unsigned int n)
{ unsigned int s = 0; for (unsigned int i = 0; i < n; ++i) s += p[i] << (i % 2 ? 0 : 8); return s; }
static void put16(unsigned char *p, unsigned int n) { p[0] = n >> 8; p[1] = n; }
static void checksum(unsigned char *p, unsigned int value)
{ while (value >> 16) value = (value & 65535) + (value >> 16); put16(p, ~value); }
static void *worker(void *unused)
{
    assert(!lauberhorn_tcp_attach(&app, 0));
    for (;;) {
        struct lauberhorn_tcp_event event;
        int ret = lauberhorn_tcp_receive(&event); assert(ret >= 0);
        if (!ret) continue;
        assert(event.data && event.length >= 40 && event.endpoint == endpoint.handle);
        const unsigned char *in = event.data;
        assert(in[0] == 0x45 && in[9] == 6 && (in[33] & 2));
        unsigned char packet[40] = {0};
        packet[0] = 0x45; put16(packet + 2, 40); packet[8] = 64; packet[9] = 6;
        memcpy(packet + 12, in + 16, 4); memcpy(packet + 16, in + 12, 4);
        memcpy(packet + 20, in + 22, 2); memcpy(packet + 22, in + 20, 2);
        memcpy(packet + 28, in + 24, 4);
        for (int i = 31; i >= 28; --i) if (++packet[i]) break;
        packet[32] = 0x50; packet[33] = 0x14; /* RST/ACK: transport probe, not TCP stack. */
        checksum(packet + 36, sum(packet + 12, 8) + 6 + 20 + sum(packet + 20, 20));
        checksum(packet + 10, sum(packet, 20));
        assert(lauberhorn_tcp_receive(&event) == -1 && errno == EBUSY);
        assert(!lauberhorn_tcp_release());
        assert(lauberhorn_tcp_release() == -1 && errno == EINVAL);
        lauberhorn_tx_buffer_t tx;
        assert(!lauberhorn_tx_get_buffer(false, &tx));
        memcpy(tx.data, packet, sizeof(packet));
        struct lauberhorn_tcp_endpoint bad = endpoint; ++bad.handle;
        assert(lauberhorn_tcp_send(&bad, &tx, sizeof(packet)) == -1 && errno == EIO);
        assert(!lauberhorn_tx_get_buffer(false, &tx));
        memcpy(tx.data, packet, sizeof(packet));
        assert(!lauberhorn_tcp_send(&endpoint, &tx, sizeof(packet)));
        puts("TCP_TRANSPORT_PASS"); fflush(stdout);
    }
}
int main(int argc, char **argv)
{
    assert(argc == 1 || argc == 2);
    if (argc == 2) {
        struct in_addr address;
        assert(inet_pton(AF_INET, argv[1], &address) == 1);
        endpoint.address = ntohl(address.s_addr);
    }
    assert(!lauberhorn_init(&app));
    assert(!ioctl(app.fd, LAUBERHORN_IOCTL_REG_TCP_ENDPOINT, &endpoint));
    struct lauberhorn_tcp_endpoint again = endpoint; again.handle = 0;
    assert(!ioctl(app.fd, LAUBERHORN_IOCTL_REG_TCP_ENDPOINT, &again));
    assert(again.handle == endpoint.handle);
    assert(!lauberhorn_set_worker_count(&app, 1));
    pthread_t thread; assert(!pthread_create(&thread, NULL, worker, NULL));
    puts("READY"); fflush(stdout); pthread_join(thread, NULL);
}
