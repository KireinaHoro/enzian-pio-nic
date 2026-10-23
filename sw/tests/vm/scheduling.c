/* SPDX-License-Identifier: BSD-3-Clause */
#define _GNU_SOURCE
#include <lauberhorn.h>
#include <lauberhorn/udp.h>
#include <lauberhorn/buffer.h>
#include <lauberhorn/deadline.h>
#include "vm/abi.h"
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Parent orchestrates two actual applications through private command sockets.
 * Shared gates model long handlers without altering device scheduling state. */
struct shared {
    _Atomic unsigned hold[2], packets[2], deadlines[2];
    _Atomic unsigned long long generation[2];
};
static struct shared *shared;
static lauberhorn_t app;
static lauberhorn_udp_endpoint_t endpoint;
static unsigned which;
static unsigned short peer_port;
static uint64_t ns(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC, &t));
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
static void *worker(void *arg) {
    assert(!lauberhorn_udp_attach(&app, (unsigned long)arg));
    for (;;) {
        while (atomic_load(&shared->hold[which])) usleep(1000);
        lauberhorn_pkt_desc_t d;
        int r = ioctl(app.fd, LH_VM_RX, &d);
        assert(r >= 0);
        if (!r) continue;
        if (d.type == TY_DESCHED) {
            struct lh_activation activation;
            assert(!lh_yield(&activation));
        } else if (d.type == TY_DEADLINE) {
            assert(!d.payload_len);
            assert(ioctl(app.fd, LH_VM_RX_RELEASE) == -1 && errno == EINVAL);
            /* Timer-only TX must work without an RX borrow or RPC context. */
            lauberhorn_tx_buffer_t tx;
            assert(!lauberhorn_tx_get_buffer(true, &tx));
            *(char *)tx.data = 'D';
            assert(!lauberhorn_udp_send_buffer(&endpoint, 0x7f000001, peer_port, &tx, 1, 0));
            atomic_store(&shared->generation[which], d.deadline.generation);
            atomic_fetch_add(&shared->deadlines[which], 1);
        } else {
            assert(d.type == TY_NATIVE_UDP_RX);
            assert(!ioctl(app.fd, LH_VM_RX_RELEASE));
            atomic_fetch_add(&shared->packets[which], 1);
        }
    }
}
struct command {
    unsigned op;
    int error;
    struct lh_vm_schedule_state schedule;
    struct lauberhorn_deadline_set set;
    struct lauberhorn_deadline_state state;
};
enum { STATS, SET, GET };
static void child(int fd, unsigned id) {
    which = id;
    assert(!lauberhorn_init(&app));
    assert(!lauberhorn_udp_bind(&app, &endpoint, 19100 + id));
    assert(!lauberhorn_set_worker_count(&app, 4));
    pthread_t threads[4];
    for (unsigned i = 0; i < 4; ++i) assert(!pthread_create(&threads[i], NULL, worker, (void *)(unsigned long)i));
    for (;;) {
        struct command c;
        ssize_t received = recv(fd, &c, sizeof(c), MSG_WAITALL);
        if (!received) for (;;) pause(); /* Parent finished; retain domain until reboot. */
        assert(received == sizeof(c));
        int r;
        if (c.op == STATS) r = ioctl(app.fd, LH_VM_SCHEDULE_STATE, &c.schedule);
        else if (c.op == SET) r = ioctl(app.fd, LAUBERHORN_IOCTL_SET_DEADLINE, &c.set);
        else r = ioctl(app.fd, LAUBERHORN_IOCTL_GET_DEADLINE, &c.state);
        c.error = r < 0 ? errno : 0;
        assert(send(fd, &c, sizeof(c), 0) == sizeof(c));
    }
}
static int control[2], traffic;
static struct command command(unsigned id, struct command c) {
    assert(send(control[id], &c, sizeof(c), 0) == sizeof(c));
    assert(recv(control[id], &c, sizeof(c), MSG_WAITALL) == sizeof(c));
    return c;
}
static struct lh_vm_schedule_state stats(unsigned id) {
    struct command c = command(id, (struct command){.op = STATS});
    assert(!c.error); return c.schedule;
}
static struct lauberhorn_deadline_state deadline(unsigned id) {
    struct command c = command(id, (struct command){.op = GET});
    assert(!c.error); return c.state;
}
static uint64_t arm(unsigned id, uint64_t gen, uint64_t next, int error) {
    struct command c = command(id, (struct command){.op = SET,
        .set = {.expected_generation = gen, .next_deadline_ns = next}});
    assert(c.error == error); return c.set.generation;
}
static void packets(unsigned id, unsigned n) {
    struct sockaddr_in to = {.sin_family = AF_INET, .sin_port = htons(19100 + id),
        .sin_addr.s_addr = htonl(0x7f000001)};
    for (unsigned i = 0; i < n; ++i) assert(sendto(traffic, "P", 1, 0, (void *)&to, sizeof(to)) == 1);
}
#define WAIT(condition) do { \
    uint64_t end = ns() + (uint64_t)TEST_TIMEOUT_MS * 1000000; \
    while (!(condition)) { \
        if (ns() >= end) { fprintf(stderr, "timeout: %s at %d\n", #condition, __LINE__); abort(); } \
        usleep(1000); \
    } \
} while (0)
static void settle(void) { usleep(TEST_SETTLE_MS * 1000); }
static uint64_t soon(void) { return ns() + (uint64_t)TEST_ARM_MS * 1000000; }
int main(void) {
    setbuf(stdout, NULL);
    shared = mmap(NULL, sizeof(*shared), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    assert(shared != MAP_FAILED);
    shared->hold[0] = shared->hold[1] = 1;
    traffic = socket(AF_INET, SOCK_DGRAM, 0); assert(traffic >= 0);
    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(0x7f000001)};
    assert(!bind(traffic, (void *)&addr, sizeof(addr)));
    socklen_t len = sizeof(addr); assert(!getsockname(traffic, (void *)&addr, &len));
    peer_port = ntohs(addr.sin_port);
    struct timeval timeout = {.tv_sec = TEST_TIMEOUT_MS / 1000};
    assert(!setsockopt(traffic, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)));
    for (unsigned i = 0; i < 2; ++i) {
        int pair[2]; assert(!socketpair(AF_UNIX, SOCK_SEQPACKET, 0, pair));
        pid_t pid = fork(); assert(pid >= 0);
        if (!pid) { close(pair[0]); child(pair[1], i); _exit(0); }
        close(pair[1]); control[i] = pair[0];
        WAIT(stats(i).registered == 4);
    }
    unsigned cap = stats(0).capacity, half = cap / 2;
    assert(cap >= 8 && !(cap % 2));
    packets(0, 1); WAIT(stats(0).assigned == 1);
    assert(stats(0).arrival_idle == 1 && !stats(0).arrival_ready);
    packets(0, half - 1); WAIT(stats(0).queued == half); settle();
    assert(stats(0).assigned == 1); /* pre-enqueue watermark */
    packets(0, 3); WAIT(stats(0).assigned == 4);
    assert(stats(0).arrival_idle == 4);
    packets(1, 1); WAIT(stats(1).queued == 1); settle();
    assert(stats(1).assigned == 0); /* no idle and no ready donor */
    shared->hold[0] = 0;
    WAIT(stats(1).assigned == 1);
    assert(stats(0).queued == 0);
    assert(stats(1).background == 1); /* local packets precede discovery */
    WAIT(stats(0).ready_workers == 3);
    packets(1, half - 1); WAIT(stats(1).queued == half); settle();
    assert(stats(1).assigned == 1); /* no round-robin rotation */
    packets(1, 3); WAIT(stats(1).assigned == 4);
    WAIT(stats(0).assigned == 0);
    assert(stats(1).arrival_ready == 3);
    puts("VM_SCHEDULER_POLICY_PASS");
    packets(1, cap - half - 3); WAIT(stats(1).queued == cap);
    uint64_t bgen = arm(1, 0, soon(), 0);
    WAIT(deadline(1).pending);
    assert(!deadline(1).delivered && stats(1).assigned == 4);

    struct lauberhorn_deadline_state d = deadline(0);
    assert(!d.generation && d.minimum_lead_ns && !d.pending);
    uint64_t gen = arm(0, 0, soon(), 0); assert(gen == 1);
    WAIT(deadline(0).pending); settle();
    assert(!deadline(0).delivered && stats(0).assigned == 0);
    assert(stats(1).queued > half); /* protect busy donor, expiry stays sticky */
    arm(0, 0, 0, ESTALE);
    arm(0, gen, 1, ETIME);
    assert(deadline(0).generation == gen && deadline(0).pending);
    shared->hold[1] = 0;
    WAIT(atomic_load(&shared->deadlines[0]) == 1);
    assert(stats(0).assigned == 1 && stats(0).deadline == 1);
    /* Workers drain concurrently; the first RX-ready donor may appear after
     * fill has fallen below half. The policy prohibits only above-half reclaim. */
    assert(stats(0).last_deadline_donor_fill <= half);
    assert(atomic_load(&shared->generation[0]) == gen);
    WAIT(atomic_load(&shared->deadlines[1]) == 1);
    assert(stats(1).deadline == 0); /* four workers share one notification */
    char response;
    for (unsigned i = 0; i < 2; ++i) assert(recv(traffic, &response, 1, 0) == 1 && response == 'D');
    arm(1, bgen, 0, 0);
    settle();
    assert(atomic_load(&shared->deadlines[1]) == 1); assert(atomic_load(&shared->deadlines[0]) == 1);
    assert(deadline(0).pending && deadline(0).delivered);
    /* Existing core: no second allocation, single delivery across four threads. */
    gen = arm(0, gen, soon(), 0);
    WAIT(atomic_load(&shared->deadlines[0]) == 2);
    assert(stats(0).assigned == 1 && stats(0).deadline == 1);
    assert(recv(traffic, &response, 1, 0) == 1 && response == 'D');
    /* Cancellation and an old acknowledgement cannot clear a newer arm. */
    gen = arm(0, gen, soon(), 0);
    gen = arm(0, gen, 0, 0);
    usleep(2 * TEST_ARM_MS * 1000);
    assert(atomic_load(&shared->deadlines[0]) == 2 && !deadline(0).pending);
    uint64_t old = gen;
    gen = arm(0, gen, ns() + (uint64_t)TEST_TIMEOUT_MS * 1000000, 0);
    arm(0, old, 0, ESTALE);
    gen = arm(0, gen, soon(), 0); /* earlier rearm */
    WAIT(atomic_load(&shared->deadlines[0]) == 3);
    assert(atomic_load(&shared->generation[0]) == gen);
    assert(recv(traffic, &response, 1, 0) == 1 && response == 'D');
    gen = arm(0, gen, 0, 0);
    puts("VM_DEADLINE_PASS");
    /* Children retain live domains until guest reboot; no unsupported teardown. */
    return 0;
}
