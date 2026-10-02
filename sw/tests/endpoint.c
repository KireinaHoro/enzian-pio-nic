/* Host control-API tests; no ECI or kernel-hardware behavior is emulated. */
#include "lauberhorn.h"
#include "../kmod/ioctl.h"
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <string.h>
static int calls, failure;
int ioctl(int fd, unsigned long cmd, ...)
{
    assert(fd == 42);
    calls++;
    if (failure) { errno = failure; return -1; }
    va_list args;
    va_start(args, cmd);
    void *arg = va_arg(args, void *);
    va_end(args);
    if (cmd == LAUBERHORN_IOCTL_REG_ENDPOINT) {
        lauberhorn_reg_endpoint_t *p = arg;
        assert(!p->reserved && !p->handle && !p->port && !p->pid);
        p->handle = 123; p->port = 54321; p->pid = 456;
    } else {
        assert(cmd == LAUBERHORN_IOCTL_DEREG_ENDPOINT);
        assert(((lauberhorn_dereg_endpoint_t *)arg)->handle == 123);
    }
    return 0;
}
static int marshal(void *a, void *b, size_t n, void *p) { return 0; }
static int encode(void *a, void *b, size_t n, size_t cap, void *p) { return 0; }
static void *alloc(rpc_alloc_kind_t k, void *p) { return NULL; }
static void release(void *p, rpc_free_kind_t k, void *c) {}
int main(void)
{
    _Static_assert(sizeof(lauberhorn_outcome_t) == 48, "Rust outcome ABI");
    _Static_assert(offsetof(lauberhorn_outcome_t, endpoint) == 40, "Rust outcome endpoint");
    _Static_assert(sizeof(lauberhorn_handler_t) == 16, "Rust handler ABI");
    _Static_assert(sizeof(lauberhorn_t) == 32, "Rust context ABI");
    _Static_assert(sizeof(lauberhorn_rpc_endpoint_t) == 72, "Rust endpoint ABI");
    _Static_assert(offsetof(lauberhorn_rpc_endpoint_t, _transport) == 32, "Rust transport offset");
    _Static_assert(sizeof(lauberhorn_reg_endpoint_t) == 16, "registration ABI");
    _Static_assert(sizeof(lauberhorn_dereg_endpoint_t) == 8, "deregistration ABI");
    lauberhorn_t ctx = {.fd = 42}, other = {.fd = 43};
    rpc_ops_t ops = {.marshal_call = encode, .unmarshal_resp = marshal,
                    .alloc = alloc, .free = release};
    rpc_codec_t codec = {.ops = &ops};
    lauberhorn_rpc_endpoint_t ep = {.daddr = "192.168.129.131", .dport = 1234,
                                    .rpc_codec = &codec};
    assert(!lauberhorn_rpc_endpoint_destroy(&ctx, &ep) && !calls);
    ep.daddr = "not-an-address";
    assert(lauberhorn_rpc_endpoint_init(&ctx, &ep) == -1 && errno == EINVAL && !calls);
    ep.daddr = "192.168.129.131";
    ctx._workers = 1;
    assert(lauberhorn_rpc_endpoint_init(&ctx, &ep) == -1 && errno == EBUSY && !calls);
    ctx._workers = 0;
    failure = ENOSPC;
    assert(lauberhorn_rpc_endpoint_init(&ctx, &ep) == -1 && errno == ENOSPC);
    assert(!ep._transport.self);
    failure = 0;
    assert(!lauberhorn_rpc_endpoint_init(&ctx, &ep));
    assert(ep._transport.daddr == 0xc0a88183 && ep._transport.sport == 54321);
    assert(ep._transport.pid == 456 && ep._transport.owner == &ctx);
    assert(lauberhorn_rpc_endpoint_init(&ctx, &ep) == -1 && errno == EALREADY);
    lauberhorn_rpc_endpoint_t copy = ep;
    assert(lauberhorn_rpc_endpoint_destroy(&ctx, &copy) == -1 && errno == EPERM);
    assert(lauberhorn_rpc_endpoint_destroy(&other, &ep) == -1 && errno == EPERM);
    ep._transport.unresolved = 1;
    assert(lauberhorn_rpc_endpoint_destroy(&ctx, &ep) == -1 && errno == EBUSY);
    ep._transport.unresolved = 0;
    failure = EBUSY;
    assert(lauberhorn_rpc_endpoint_destroy(&ctx, &ep) == -1 && ep._transport.self);
    failure = 0;
    assert(!lauberhorn_rpc_endpoint_destroy(&ctx, &ep));
    assert(!ep._transport.self && ep.dport == 1234);
    assert(!lauberhorn_rpc_endpoint_destroy(&ctx, &ep));
}
