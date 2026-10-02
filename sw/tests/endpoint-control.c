/* Exercise the actual kernel endpoint-control implementation with deterministic
 * socket/CSR fault injection. Real namespace/bind behavior remains a HW gate. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <netinet/in.h>
#include "../kmod/ioctl.h"
#define LAUBERHORN_NUM_LISTEN_PORTS 2
#define LAUBERHORN_NUM_SERVICES 2
#define LAUBERHORN_NUM_SESSIONS 2
#define LAUBERHORN_ECI__ONC_RPC_REPLY_DECODER_BASE 0
#define GFP_KERNEL 0
#define U32_MAX UINT32_MAX
#define THIS_MODULE 0
#define pr_warn(...) ((void)0)
struct socket { int unused; };
struct endpoint_reservation;
struct worker_dev { int UdpDecoder_dev; };
struct proc { int tgid, num_rdy_thrs; };
struct file_priv {
    struct proc *pd;
    struct worker_dev *dev;
    struct endpoint_reservation *endpoints[2];
};
static struct { bool enabled; uint16_t port; } srv_defs[2];
static bool listen_occupied[2];
static int init_net;
static struct { int *net_ns; } nsproxy = { &init_net };
static struct { void *nsproxy; } task = { &nsproxy };
/* Give current the exact field type used by endpoints.h. */
static struct task_view { typeof(nsproxy) *nsproxy; } current_task = { &nsproxy };
#define current (&current_task)
static int sockets, binds, fail_copy, fail_socket, busy_port = 49152, hw_active, pins;
static bool net_eq(int *a, int *b) { return a == b; }
static void *kzalloc(size_t n, int flags) { return calloc(1, n); }
#define kfree free
static int copy_from_user(void *d, const void *s, size_t n) { memcpy(d,s,n); return 0; }
static int copy_to_user(void *d, const void *s, size_t n) {
    if (fail_copy) return 1;
    memcpy(d,s,n); return 0;
}
static int sock_create_kern(int *net, int family, int type, int proto, struct socket **s) {
    if (fail_socket) return -ENOMEM;
    *s = calloc(1, sizeof(**s)); sockets++; return 0;
}
static void sock_release(struct socket *s) { free(s); sockets--; }
static int kernel_bind(struct socket *s, struct sockaddr *addr, int n) {
    binds++;
    return ntohs(((struct sockaddr_in *)addr)->sin_port) == busy_port ? -EADDRINUSE : 0;
}
static void __module_get(int m) { pins++; }
enum { lauberhorn_eci_listen_onc_rpc_reply = 2, lauberhorn_eci_listen_disabled = 0 };
static void lauberhorn_eci_UdpDecoder_ctrl_listen_port_wr(int *d, int v) {}
static void lauberhorn_eci_UdpDecoder_ctrl_listen_next_proto_wr(int *d, int v) {}
static void lauberhorn_eci_UdpDecoder_ctrl_listen_idx_wr(int *d, int v) {}
typedef int lauberhorn_eci_OncRpcReplyDecoder_t;
static void lauberhorn_eci_OncRpcReplyDecoder_initialize(int *d, int b) {}
static void lauberhorn_eci_OncRpcReplyDecoder_stat_req_readback_idx_wr(int *d, int i) {}
static int lauberhorn_eci_OncRpcReplyDecoder_stat_req_readback_active_rd(int *d) { return hw_active; }
static int lauberhorn_eci_OncRpcReplyDecoder_stat_req_readback_local_port_rd(int *d) { return 49154; }
static u32 retire_pid, retire_cookie, retire_count;
static bool retire_busy, retire_stuck;
static void lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_pid_wr(int *d, u32 pid) { retire_pid = pid; }
static void lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_cookie_wr(int *d, u32 cookie) { retire_cookie = cookie; }
static void lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_wr(int *d, u32 mode) {
    assert(mode == 1); retire_count++; retire_busy = retire_stuck;
}
static int lauberhorn_eci_OncRpcReplyDecoder_stat_retire_busy_rd(int *d) { return retire_busy; }
#define read_poll_timeout(op, value, condition, sleep, timeout, before, ...) \
    ({ (value) = op(__VA_ARGS__); (condition) ? 0 : -ETIMEDOUT; })
#include "../kmod/endpoints.h"
#include "../kmod/retire_call.h"
int main(void) {
    struct proc proc = {.tgid = 123};
    struct worker_dev dev = {0};
    struct file_priv fp = {.pd = &proc, .dev = &dev};
    struct file_priv other = {.pd = &proc, .dev = &dev};
    lauberhorn_reg_endpoint_t cmd = {0};
    int other_net;
    nsproxy.net_ns = &other_net;
    assert(register_endpoint(&fp, &cmd) == -EXDEV);
    nsproxy.net_ns = &init_net;
    proc.num_rdy_thrs = 1;
    assert(register_endpoint(&fp, &cmd) == -EBUSY);
    proc.num_rdy_thrs = 0;
    fail_socket = 1;
    assert(register_endpoint(&fp, &cmd) == -ENOMEM && !sockets);
    fail_socket = 0;
    fail_copy = 1;
    assert(register_endpoint(&fp, &cmd) == -EFAULT && !sockets && !listen_occupied[0]);
    fail_copy = 0;
    srv_defs[0].enabled = true; srv_defs[0].port = 49153;
    assert(!register_endpoint(&fp, &cmd));
    assert(cmd.pid == 123 && cmd.port == 49154 && sockets == 1);
    lauberhorn_retire_call_t retire = {.endpoint_handle = cmd.handle, .cookie = 0x12345678};
    assert(retire_call(&other, &retire) == -ENOENT && !retire_count);
    assert(!retire_call(&fp, &retire) && retire_count == 1);
    assert(retire_pid == 123 && retire_cookie == 0x12345678 && sockets == 1);
    retire_stuck = true;
    assert(retire_call(&fp, &retire) == -ETIMEDOUT);
    assert(retire_call(&fp, &retire) == -EBUSY && retire_count == 2);
    retire_stuck = retire_busy = false;
    lauberhorn_dereg_endpoint_t dereg = {.handle = cmd.handle};
    assert(deregister_endpoint(&other, &dereg) == -ENOENT);
    hw_active = 1;
    assert(deregister_endpoint(&fp, &dereg) == -EBUSY && sockets == 1);
    hw_active = 0;
    cmd = (lauberhorn_reg_endpoint_t){0};
    assert(!register_endpoint(&fp, &cmd) && cmd.port == 49155);
    cmd = (lauberhorn_reg_endpoint_t){0};
    assert(register_endpoint(&fp, &cmd) == -ENOSPC && sockets == 2);
    assert(!deregister_endpoint(&fp, &dereg) && sockets == 1);
    for (int i = 0; i < LAUBERHORN_NUM_LISTEN_PORTS; ++i)
        if (fp.endpoints[i]) release_endpoint(&fp, i);
    assert(!sockets && !pins && !endpoint_port_reserved(49155));
}
