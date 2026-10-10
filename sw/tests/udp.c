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
    state->tx_reserved = false;
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
    memcpy(rx, "abc", 3);
    state->rx_view.data = rx;
    state->rx_view.length = desc->payload_len;
    state->rx_view.pending = true;
    return true;
}
static bool test_rx_traced(void *base, lauberhorn_core_state_t *state, lauberhorn_pkt_desc_t *desc,
                           uint64_t *stamp, lauberhorn_trace_now_fn_t clock) {
    *stamp = clock();
    return test_rx(base, state, desc);
}
static void *test_tx_get(void *base, lauberhorn_core_state_t *state, bool inline_payload) {
    if (state->tx_reserved) return NULL;
    state->tx_reserved = true;
    ++state->tx_generation;
    state->tx_capacity = inline_payload ? 64 : LAUBERHORN_MTU;
    return tx;
}
#define core_eci_tx_cancel core_eci_tx_finish
#define core_eci_tx_get_buffer test_tx_get
#define core_eci_rx_acquire_traced test_rx_traced
#define ioctl test_ioctl
#define core_eci_tx_commit test_tx
#define core_eci_rx_acquire test_rx
#include "../rt/udp.c"
#include "../rt/buffer.c"

int main(void) {
    context.fd = 17;
    worker.ctx = &context;
    worker.dp.tx_buf = tx;
    lauberhorn_udp_endpoint_t ep = {0};
    assert(!lauberhorn_udp_bind(&context, &ep, 11211));
    assert(ep.owner == &context && ep.port == 11211 && ep.handle == 55);
    assert(lauberhorn_udp_bind(&context, &ep, 11211) == -1);
    const lauberhorn_udp_datagram_t *packet = NULL;
    assert(lauberhorn_udp_receive_view(&packet) == 0);
    assert(packet == NULL);
    receives = 0; mode = 1;
    assert(lauberhorn_udp_receive_view(&packet) == 1 && yields == 1);
    assert(packet == &worker.udp_rx_view);
    assert(packet->data == rx && packet->length == 3 && packet->endpoint == 55);
    ((char *)packet->data)[0] = 'z';
    assert(rx[0] == 'z');
    assert(packet->saddr == 0xc0000201 && packet->sport == 32123);
    assert(!lauberhorn_udp_release());
    packet = NULL;
    assert(!worker.udp_rx_view.data && !worker.dp.rx_view.pending);
    mode = 2;
    assert(lauberhorn_udp_receive_view(&packet) == 1 && packet->length == 0);
    assert(worker.dp.rx_view.pending);
    assert(!lauberhorn_udp_release());
    mode = 3;
    int reads = receives;
    assert(lauberhorn_udp_receive_view(&packet) == 1 && packet->data == rx);
    assert(lauberhorn_udp_receive_view(&packet) == -1 && errno == EBUSY);
    assert(receives == reads + 1);
    assert(packet == &worker.udp_rx_view && packet->data == rx);
    assert(!lauberhorn_udp_release());
    assert(lauberhorn_udp_release() == -1);
    lauberhorn_tx_buffer_t buffer, stale;
    assert(!lauberhorn_tx_get_buffer(false, &buffer));
    stale = buffer;
    assert(lauberhorn_tx_get_buffer(true, &buffer) == -1 && errno == EBUSY);
    assert(lauberhorn_udp_send_buffer(&ep, 0xc0000201, 32123, &buffer, 1473) == -1 && errno == EMSGSIZE);
    assert(worker.dp.tx_reserved);
    memcpy(buffer.data, "short", 5);
    assert(!lauberhorn_udp_send_buffer(&ep, 0xc0000201, 32123, &buffer, 5));
    assert(sent.payload_len == 5 && !memcmp(tx, "short", 5));
    assert(!lauberhorn_tx_get_buffer(true, &buffer));
    assert(lauberhorn_tx_cancel(&stale) == -1 && errno == EINVAL);
    assert(worker.dp.tx_reserved);
    assert(!lauberhorn_tx_cancel(&buffer));
    assert(!worker.dp.tx_reserved);
    assert(!lauberhorn_tx_get_buffer(false, &buffer));
    tx_status = CORE_TX_RETRY;
    assert(lauberhorn_udp_send_buffer(&ep, 1, 2, &buffer, 0) == -1 && errno == EAGAIN);
    assert(!worker.dp.tx_reserved && !buffer.data);
    tx_status = CORE_TX_ACCEPTED;
    sends = 0;
    assert(!lauberhorn_tx_get_buffer(true, &buffer));
    memcpy(buffer.data, "hello world", 11);
    assert(!lauberhorn_udp_send_buffer(&ep, 0xc0000201, 32123, &buffer, 11));
    assert(sent.type == TY_NATIVE_UDP_TX && sent.payload_len == 11 && sent.udp.endpoint == 55);
    assert(sent.udp.daddr == 0xc0000201 && sent.udp.dport == 32123 && !memcmp(tx,"hello world",11));
    assert(!lauberhorn_tx_get_buffer(true, &buffer));
    assert(!lauberhorn_udp_send_buffer(&ep, 1, 2, &buffer, 0));
    assert(sent.payload_len == 0);
    assert(!lauberhorn_tx_get_buffer(true, &buffer));
    tx_status = CORE_TX_ERROR;
    assert(lauberhorn_udp_send_buffer(&ep, 1, 2, &buffer, 11) == -1 && errno == EIO);
    assert(!worker.dp.tx_reserved && !buffer.data);
    int before = sends;
    assert(!lauberhorn_tx_get_buffer(true, &buffer));
    assert(buffer.capacity == 64);
    assert(lauberhorn_udp_send_buffer(&ep, 1, 2, &buffer, 65) == -1 && errno == EMSGSIZE);
    assert(worker.dp.tx_reserved);
    ep.owner = NULL;
    assert(lauberhorn_udp_send_buffer(&ep, 1, 2, &buffer, 11) == -1 && errno == EINVAL);
    assert(sends == before && worker.dp.tx_reserved);
    assert(!lauberhorn_tx_cancel(&buffer));
}
