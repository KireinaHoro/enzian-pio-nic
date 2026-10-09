/* Test-only socket transport for exercising the real memcached adapter on the
 * build host. This is never part of a deployment and does not model ECI or
 * scheduling. Native UDP RTL and board gates remain independent. */
#include <arpa/inet.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <lauberhorn/udp.h>
#include <lauberhorn/trace.h>
static __thread lauberhorn_t *current;
static __thread unsigned char payload[LAUBERHORN_UDP_MAX_PAYLOAD];
static uint16_t local_port;
int lauberhorn_init(lauberhorn_t *ctx) {
    memset(ctx,0,sizeof(*ctx));
    ctx->fd=socket(AF_INET,SOCK_DGRAM,0);
    return ctx->fd < 0 ? -1 : 0;
}
int lauberhorn_set_worker_count(lauberhorn_t *ctx,uint32_t count) {
    ctx->_declared_workers=count;return count ? 0 : -1;
}
int lauberhorn_udp_bind(lauberhorn_t *ctx,lauberhorn_udp_endpoint_t *ep,uint16_t port) {
    struct sockaddr_in addr={.sin_family=AF_INET,.sin_port=htons(port),.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    if (bind(ctx->fd,(struct sockaddr *)&addr,sizeof(addr))) return -1;
    local_port=port;
    *ep=(lauberhorn_udp_endpoint_t){.owner=ctx,.handle=1,.port=port};
    return 0;
}
int lauberhorn_udp_attach(lauberhorn_t *ctx,uint32_t index) {
    if (index>=ctx->_declared_workers) return -1;
    current=ctx;return 0;
}
int lauberhorn_udp_receive(lauberhorn_udp_datagram_t *packet) {
    struct sockaddr_in addr;
    socklen_t length=sizeof(addr);
    ssize_t n=recvfrom(current->fd,payload,sizeof(payload),0,(struct sockaddr *)&addr,&length);
    if (n<0) return -1;
    *packet=(lauberhorn_udp_datagram_t){.endpoint=1,.saddr=ntohl(addr.sin_addr.s_addr),
        .sport=ntohs(addr.sin_port),.daddr=INADDR_LOOPBACK,.dport=local_port,.data=payload,.length=n};
    return 1;
}
int lauberhorn_udp_sendv(const lauberhorn_udp_endpoint_t *ep,uint32_t address,uint16_t port,const struct iovec *iov,size_t count) {
    struct sockaddr_in addr={.sin_family=AF_INET,.sin_port=htons(port),.sin_addr.s_addr=htonl(address)};
    struct msghdr msg={.msg_name=&addr,.msg_namelen=sizeof(addr),.msg_iov=(struct iovec *)iov,.msg_iovlen=count};
    return sendmsg(current->fd,&msg,0)<0 ? -1 : 0;
}
void lauberhorn_trace_request_id(uint64_t id) { (void)id; }
