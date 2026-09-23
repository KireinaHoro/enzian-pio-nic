#include "lauberhorn.h"
#include "ioctl.h"
#include <arpa/inet.h>
#include <errno.h>
#include <string.h>

int lauberhorn_rpc_endpoint_init(lauberhorn_t *ctx, lauberhorn_rpc_endpoint_t *ep)
{
    struct in_addr addr;
    lauberhorn_reg_endpoint_t cmd = {0};
    if (!ctx || !ep || ctx->fd < 0) { errno = EINVAL; return -1; }
    if (ep->_transport.self) { errno = EALREADY; return -1; }
    if (ctx->_workers) { errno = EBUSY; return -1; }
    if (!ep->daddr || !ep->dport || inet_pton(AF_INET, ep->daddr, &addr) != 1 ||
        !ep->rpc_codec || !ep->rpc_codec->ops ||
        !ep->rpc_codec->ops->marshal_call || !ep->rpc_codec->ops->unmarshal_resp ||
        !ep->rpc_codec->ops->alloc || !ep->rpc_codec->ops->free) {
        errno = EINVAL;
        return -1;
    }
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_REG_ENDPOINT, &cmd)) return -1;
    ep->_transport.self = ep;
    ep->_transport.owner = ctx;
    ep->_transport.handle = cmd.handle;
    ep->_transport.pid = cmd.pid;
    ep->_transport.sport = cmd.port;
    ep->_transport.daddr = ntohl(addr.s_addr);
    ep->_transport.unresolved = 0;
    return 0;
}

int lauberhorn_rpc_endpoint_destroy(lauberhorn_t *ctx, lauberhorn_rpc_endpoint_t *ep)
{
    if (!ctx || !ep) { errno = EINVAL; return -1; }
    if (!ep->_transport.self) return 0;
    if (ep->_transport.self != ep || ep->_transport.owner != ctx) {
        errno = EPERM; return -1;
    }
    if (ctx->_workers || __atomic_load_n(&ep->_transport.unresolved, __ATOMIC_ACQUIRE)) {
        errno = EBUSY; return -1;
    }
    lauberhorn_dereg_endpoint_t cmd = { .handle = ep->_transport.handle };
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_DEREG_ENDPOINT, &cmd)) return -1;
    memset(&ep->_transport, 0, sizeof(ep->_transport));
    return 0;
}
