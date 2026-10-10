/* Test-only socket transport for exercising the real memcached adapter on the
 * build host. This is never part of a deployment and does not model ECI or
 * scheduling. Native UDP RTL and board gates remain independent. */
#include <arpa/inet.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <assert.h>
#include <sys/mman.h>
#include <lauberhorn/udp.h>
#include <lauberhorn/trace.h>
static __thread lauberhorn_t *current;
static __thread unsigned char *payload;
static __thread unsigned char *tx;
static __thread lauberhorn_udp_datagram_t view;
static __thread bool borrowed, reserved, reject_next, nack_next;
static __thread uint64_t generation;
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
    current=ctx;
    payload=mmap(NULL,4096,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    tx=mmap(NULL,4096,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(payload!=MAP_FAILED && tx!=MAP_FAILED);
    reject_next=true; nack_next=true;
    return 0;
}
int lauberhorn_udp_receive_view(const lauberhorn_udp_datagram_t **packet) {
    assert(!borrowed && !reserved);
    if (nack_next) { nack_next=false; return 0; }
    nack_next=true;
    assert(!mprotect(payload,4096,PROT_READ|PROT_WRITE));
    struct sockaddr_in addr;
    socklen_t length=sizeof(addr);
    ssize_t n=recvfrom(current->fd,payload,LAUBERHORN_UDP_MAX_PAYLOAD,0,(struct sockaddr *)&addr,&length);
    if (n<0) return -1;
    borrowed=true;
    view=(lauberhorn_udp_datagram_t){.endpoint=1,.saddr=ntohl(addr.sin_addr.s_addr),
        .sport=ntohs(addr.sin_port),.daddr=INADDR_LOOPBACK,.dport=local_port,.data=payload,.length=n};
    *packet=&view;
    return 1;
}
int lauberhorn_udp_release(void) {
    assert(borrowed && !reserved);
    memset(payload,0xa5,4096);
    assert(!mprotect(payload,4096,PROT_NONE));
    memset(&view,0,sizeof(view));
    borrowed=false;
    return 0;
}
int lauberhorn_tx_get_buffer(bool inline_payload,lauberhorn_tx_buffer_t *buffer) {
    size_t capacity = inline_payload ? 64 : LAUBERHORN_UDP_MAX_PAYLOAD;
    assert(!reserved);
    assert(!mprotect(tx,4096,PROT_READ|PROT_WRITE));
    reserved=true;
    *buffer=(lauberhorn_tx_buffer_t){.data=tx,.capacity=capacity,
        ._generation=++generation,._worker=current};
    return 0;
}
int lauberhorn_tx_cancel(lauberhorn_tx_buffer_t *buffer) {
    assert(reserved && buffer->_generation==generation);
    reserved=false;
    memset(tx,0xcc,4096);
    assert(!mprotect(tx,4096,PROT_NONE));
    memset(buffer,0,sizeof(*buffer));
    return 0;
}
int lauberhorn_udp_send_buffer(const lauberhorn_udp_endpoint_t *ep,uint32_t address,
        uint16_t port,lauberhorn_tx_buffer_t *buffer,size_t length) {
    assert(borrowed && reserved && buffer->_generation==generation);
    assert(ep->owner==current && buffer->_worker==current && length<=buffer->capacity);
    struct sockaddr_in addr={.sin_family=AF_INET,.sin_port=htons(port),.sin_addr.s_addr=htonl(address)};
    /* Every datagram first gets a definite rejection. Its reservation is still
     * consumed and poisoned: retry must rebuild bytes and preserve framing. */
    bool reject=reject_next;
    reject_next=!reject_next;
    ssize_t sent=reject ? -1 : sendto(current->fd,tx,length,0,(struct sockaddr *)&addr,sizeof(addr));
    int error=reject ? EAGAIN : errno;
    lauberhorn_tx_cancel(buffer);
    errno=error;
    return sent<0 ? -1 : 0;
}
void lauberhorn_trace_request_id(uint64_t id) { (void)id; }
