#define _GNU_SOURCE
/* Exercise the production UDP runtime using an injected transport, not an ECI
 * hardware substitute. Descriptor/packet RTL coverage lives in NativeUdpSim. */
#include <assert.h>
#include <errno.h>
#include <string.h>
#include "worker.h"
#include "lauberhorn/udp.h"
#include "../kmod/ioctl.h"
static struct lauberhorn_worker worker;
static lauberhorn_t context;
static lauberhorn_pkt_desc_t sent;
static int tx_status = CORE_TX_ACCEPTED, sends, receives, yields, mode;
static unsigned char rx[LAUBERHORN_MTU], tx[LAUBERHORN_MTU];
lauberhorn_worker_t worker_get_current(void) { return &worker; }
int lh_yield(struct lh_activation *activation) {
    assert(mode == 1 && receives == 1 && sends == 0);
    yields++;
    return 0;
}
static int test_ioctl(int fd, unsigned long request, void *arg) {
    assert(fd == 17 && request == LAUBERHORN_IOCTL_REG_UDP_ENDPOINT);
    lauberhorn_reg_endpoint_t *cmd = arg;
    assert(cmd->port == 11211 && !cmd->handle && !cmd->pid && !cmd->reserved);
    cmd->handle = 55;
    return 0;
}
static int test_tx(void *base, lauberhorn_core_state_t *state, lauberhorn_pkt_desc_t *desc) {
    sent = *desc;
    sends++;
    return tx_status;
}
static bool test_rx(void *base, lauberhorn_core_state_t *state, lauberhorn_pkt_desc_t *desc) {
    receives++;
    if (mode == 0) return false;
    if (mode == 1 && receives == 1) { desc->type = TY_PREEMPT; return true; }
    *desc = (lauberhorn_pkt_desc_t){.type = TY_NATIVE_UDP_RX,
        .udp = {.endpoint=55, .saddr=0xc0000201, .daddr=0xc0000202, .sport=32123, .dport=11211},
        .payload_len = mode == 2 ? 0 : 3};
    memcpy(state->rx_buf, "abc", 3);
    return true;
}
static bool test_rx_traced(void *base, lauberhorn_core_state_t *state, lauberhorn_pkt_desc_t *desc,
                           uint64_t *stamp, lauberhorn_trace_now_fn_t clock) {
    *stamp = clock();
    return test_rx(base, state, desc);
}
#define core_eci_rx_traced test_rx_traced
#define ioctl test_ioctl
#define core_eci_tx test_tx
#define core_eci_rx test_rx
#include "../rt/udp.c"

int main(void) {
    context.fd = 17;
    worker.ctx = &context;
    worker.dp.rx_buf = rx;
    worker.dp.tx_buf = tx;
    lauberhorn_udp_endpoint_t ep = {0};
    assert(!lauberhorn_udp_bind(&context, &ep, 11211));
    assert(ep.owner == &context && ep.port == 11211 && ep.handle == 55);
    assert(lauberhorn_udp_bind(&context, &ep, 11211) == -1);
    lauberhorn_udp_datagram_t packet;
    assert(lauberhorn_udp_receive(&packet) == 0);
    receives = 0; mode = 1;
    assert(lauberhorn_udp_receive(&packet) == 1 && yields == 1);
    assert(packet.data == rx && packet.length == 3 && packet.endpoint == 55);
    assert(packet.saddr == 0xc0000201 && packet.sport == 32123);
    mode = 2;
    assert(lauberhorn_udp_receive(&packet) == 1 && packet.length == 0);
    struct iovec iov[] = {{"hello",5},{NULL,0},{" world",6}};
    assert(!lauberhorn_udp_sendv(&ep, 0xc0000201, 32123, iov, 3));
    assert(sent.type == TY_NATIVE_UDP_TX && sent.payload_len == 11 && sent.udp.endpoint == 55);
    assert(sent.udp.daddr == 0xc0000201 && sent.udp.dport == 32123 && !memcmp(tx,"hello world",11));
    assert(!lauberhorn_udp_sendv(&ep, 0xc0000201, 32123, NULL, 0));
    assert(sent.payload_len == 0);
    tx_status = CORE_TX_RETRY;
    assert(lauberhorn_udp_sendv(&ep, 1, 2, iov, 3) == -1 && errno == EAGAIN);
    assert(!memcmp(iov[0].iov_base,"hello",5));
    tx_status = CORE_TX_ERROR;
    assert(lauberhorn_udp_sendv(&ep, 1, 2, iov, 3) == -1 && errno == EIO);
    int before = sends;
    struct iovec oversized = {.iov_len = SIZE_MAX};
    assert(lauberhorn_udp_sendv(&ep, 1, 2, &oversized, 1) == -1 && errno == EMSGSIZE);
    ep.owner = NULL;
    assert(lauberhorn_udp_sendv(&ep, 1, 2, iov, 3) == -1 && errno == EINVAL);
    assert(sends == before);
}
