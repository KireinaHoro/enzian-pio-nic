/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#include "common.h"
#include "activation_backend.h"
#include "vm.h"
#include <linux/completion.h>
#include <linux/ktime.h>
#include "lauberhorn/deadline.h"
#include <linux/netfilter.h>
#include <linux/netfilter_ipv4.h>
#include <linux/ip.h>
#include <linux/udp.h>
#include <linux/tcp.h>
#include <linux/nsproxy.h>
#include <net/route.h>
#include "lauberhorn/tcp_transport.h"
#include <linux/sched/signal.h>
#include <net/ip.h>
#include <net/udp.h>
#include <net/net_namespace.h>

/* All device state below is owned by emulator, except the explicitly published
 * hook port bitmap and notification flags. Callers submit synchronous commands;
 * receive commands remain queued until packet, DESCHED or bounded NACK. */
enum command { SERVICE, ENDPOINT, BUSY, RETIRE, ACTIVATE, TRANSPORT, DEADLINE_SET, DEADLINE_GET, SCHEDULE_STATE, TCP_ENDPOINT };
struct vm_cmd {
    struct list_head link;
    struct completion done;
    enum command kind;
    struct proc_def *proc;
    struct srv_def *srv;
    struct socket *socket;
    struct lh_device_activation activation;
    struct lauberhorn_deadline_set deadline_set;
    struct lauberhorn_deadline_state deadline_state;
    struct lh_vm_schedule_state schedule_state;
    struct lauberhorn_tcp_endpoint tcp_endpoint;
    lauberhorn_pkt_desc_t desc;
    unsigned int op, worker;
    unsigned long expires;
    u32 handle, cookie;
    u16 port;
    bool native, enable;
    long result;
};
struct vm_packet {
    struct list_head link;
    lauberhorn_pkt_desc_t desc;
    u8 bytes[LAUBERHORN_MTU];
};
struct vm_domain {
    struct list_head packets;
    unsigned int count;
    bool packet_due;
    unsigned int last_deadline_donor_fill;
    struct lauberhorn_deadline_state deadline;
    u64 arrival_idle, arrival_ready, background, deadline_activations;
};
struct vm_worker {
    struct worker_fpi_data shared;
    u32 assigned_slot, pending;
    bool borrowed, reserved;
    u8 rx_next, tx_next;
    struct vm_cmd *rx;
};
struct vm_endpoint { struct proc_def *proc; u32 handle; u16 port; bool native; struct socket *socket; };
struct vm_service { struct srv_def value; struct socket *socket; };
struct vm_context {
    struct proc_def *proc;
    struct socket *socket;
    u32 token, xid, cookie, remote;
    u16 local_port, remote_port;
};
static struct vm_worker workers[LAUBERHORN_NUM_WORKER_CORES];
static struct vm_domain domains[LAUBERHORN_NUM_PROCS + 1];
static struct vm_endpoint endpoints[LAUBERHORN_NUM_LISTEN_PORTS];
static struct vm_service services[LAUBERHORN_NUM_SERVICES];
/* A service socket outlives deregistration while accepted requests can reply. */
static struct { struct socket *socket; unsigned int refs; } service_sockets[LAUBERHORN_NUM_SERVICES];
static void service_socket_get(struct socket *socket)
{
    unsigned int i;
    for (i = 0; i < ARRAY_SIZE(service_sockets); ++i)
        if (service_sockets[i].socket == socket) { ++service_sockets[i].refs; return; }
    BUG();
}
static void service_socket_put(struct socket *socket)
{
    unsigned int i;
    for (i = 0; i < ARRAY_SIZE(service_sockets); ++i)
        if (service_sockets[i].socket == socket) {
            if (!--service_sockets[i].refs) { service_sockets[i].socket = NULL; sock_release(socket); }
            return;
        }
    BUG();
}
static struct vm_context incoming[LAUBERHORN_NUM_SESSIONS], outgoing[LAUBERHORN_NUM_SESSIONS];
static u8 hooked_ports[65536];
static u32 hooked_tcp_addresses[65536];
static struct {
    struct proc_def *proc;
    struct lauberhorn_tcp_endpoint endpoint;
    struct socket *reservation;
} tcp_endpoints[LAUBERHORN_NUM_LISTEN_PORTS];
static u32 next_token, next_domain;
static unsigned int deadline_cursor;
static void packet_arrival(struct proc_def *proc, unsigned int fill);
static struct task_struct *emulator;
static DECLARE_WAIT_QUEUE_HEAD(device_wait);
static DEFINE_SPINLOCK(command_lock);
static LIST_HEAD(commands);
static struct sk_buff_head ingress;
static atomic_t work_pending = ATOMIC_INIT(0);

enum { TX_ACCEPTED = 1, TX_RETRY = 2, TX_ERROR = 3 };
void lh_vm_notify(void) { atomic_set(&work_pending, 1); wake_up(&device_wait); }
static void finish(struct vm_cmd *cmd, long result)
{ cmd->result = result; complete(&cmd->done); }
static long submit(struct vm_cmd *cmd)
{
    unsigned long flags;
    init_completion(&cmd->done);
    spin_lock_irqsave(&command_lock, flags);
    list_add_tail(&cmd->link, &commands);
    spin_unlock_irqrestore(&command_lock, flags);
    lh_vm_notify();
    /* Stack command lifetime must outlive execution, even on SIGKILL. Device
     * processing is bounded and never waits for an application handler. */
    wait_for_completion(&cmd->done);
    return cmd->result;
}
struct worker_fpi_data *worker_by_index(unsigned int index)
{ return index < ARRAY_SIZE(workers) ? &workers[index].shared : NULL; }
u32 allocate_domain_code(u32 slot)
{ return next_domain < (1U << 19) ? next_domain++ : U32_MAX; }
void lh_device_fail_domain(struct proc_def *proc) { lh_vm_notify(); }
struct lh_device_activation lh_device_activate(struct worker_fpi_data *worker)
{
    struct vm_cmd cmd = { .kind = ACTIVATE, .worker = worker->index };
    submit(&cmd);
    return cmd.activation;
}
bool lh_device_idle_pending(struct worker_fpi_data *worker)
{ return READ_ONCE(workers[worker->index].pending) != 0; }
bool lh_device_yield_requested(struct worker_fpi_data *worker)
{ return READ_ONCE(workers[worker->index].pending) != 0; }
int lh_vm_service(struct srv_def *srv, bool enable)
{
    struct vm_cmd cmd = { .kind = SERVICE, .srv = srv, .enable = enable };
    return submit(&cmd);
}
void lh_vm_endpoint(struct proc_def *proc, u32 handle, u16 port,
                    struct socket *socket, bool native, bool enable)
{
    struct vm_cmd cmd = { .kind = ENDPOINT, .proc = proc, .handle = handle,
        .port = port, .socket = socket, .native = native, .enable = enable };
    submit(&cmd);
}
bool lh_vm_endpoint_busy(u16 port)
{ struct vm_cmd cmd = { .kind = BUSY, .port = port }; return submit(&cmd); }
long lh_vm_retire(struct proc_def *proc, u32 cookie)
{ struct vm_cmd cmd = { .kind = RETIRE, .proc = proc, .cookie = cookie }; return submit(&cmd); }

static unsigned int receive_hook(void *priv, struct sk_buff *skb, const struct nf_hook_state *state)
{
    struct iphdr *ip;
    struct udphdr *udp;
    unsigned long flags;
    unsigned int ihl;
    if (!pskb_may_pull(skb, sizeof(*ip))) return NF_ACCEPT;
    ip = ip_hdr(skb);
    if ((ip->protocol != IPPROTO_UDP && ip->protocol != IPPROTO_TCP) ||
        ip->ihl != 5 || ip_is_fragment(ip)) return NF_ACCEPT;
    ihl = ip->ihl * 4;
    if (!pskb_may_pull(skb, ihl + sizeof(*udp))) return NF_ACCEPT;
    ip = ip_hdr(skb);
    udp = (void *)ip + ihl;
    if (ip->protocol == IPPROTO_TCP) {
        if (READ_ONCE(hooked_tcp_addresses[ntohs(udp->dest)]) != ntohl(ip->daddr)) return NF_ACCEPT;
    } else if (!READ_ONCE(hooked_ports[ntohs(udp->dest)])) return NF_ACCEPT;
    spin_lock_irqsave(&ingress.lock, flags);
    if (skb_queue_len(&ingress) >= LH_VM_QUEUE_DEPTH) {
        spin_unlock_irqrestore(&ingress.lock, flags);
        return NF_DROP;
    }
    __skb_queue_tail(&ingress, skb);
    spin_unlock_irqrestore(&ingress.lock, flags);
    lh_vm_notify();
    return NF_STOLEN;
}
static struct nf_hook_ops hook = {
    .hook = receive_hook, .pf = NFPROTO_IPV4, .hooknum = NF_INET_LOCAL_IN,
    .priority = NF_IP_PRI_FILTER,
};
static int send_udp(struct socket *socket, u32 remote, u16 port, void *data, size_t length)
{
    struct sockaddr_in addr = { .sin_family = AF_INET, .sin_addr.s_addr = htonl(remote),
                               .sin_port = htons(port) };
    struct msghdr msg = { .msg_name = &addr, .msg_namelen = sizeof(addr), .msg_flags = MSG_DONTWAIT };
    struct kvec vec = { .iov_base = data, .iov_len = length };
    return kernel_sendmsg(socket, &msg, &vec, 1, length);
}
static u32 word(const u8 *bytes, unsigned int n)
{ __be32 value; memcpy(&value, bytes + n * 4, 4); return ntohl(value); }
static void put_word(u8 *bytes, unsigned int n, u32 value)
{ __be32 net = htonl(value); memcpy(bytes + n * 4, &net, 4); }
static bool enqueue(struct proc_def *proc, lauberhorn_pkt_desc_t *desc, const void *bytes)
{
    struct vm_packet *packet;
    struct vm_domain *domain = &domains[proc->idx];
    if (READ_ONCE(proc->failed) || domain->count >= LAUBERHORN_RX_PKTS_PER_PROC || desc->payload_len > LAUBERHORN_MTU) return false;
    packet = kmalloc(sizeof(*packet), GFP_KERNEL);
    if (!packet) return false;
    packet->desc = *desc;
    memcpy(packet->bytes, bytes, desc->payload_len);
    list_add_tail(&packet->link, &domain->packets);
    packet_arrival(proc, domain->count);
    ++domain->count;
    return true;
}
static struct vm_context *free_context(struct vm_context *table)
{
    int i;
    for (i = 0; i < LAUBERHORN_NUM_SESSIONS; ++i) if (!table[i].proc) return &table[i];
    return NULL;
}
static void receive_packet(struct sk_buff *skb)
{
    struct iphdr *ip;
    struct udphdr *udp;
    u8 *bytes;
    unsigned int len, i;
    u16 port;
    lauberhorn_pkt_desc_t desc = {0};
    if (skb_linearize(skb)) goto out;
    ip = ip_hdr(skb);
    if (ip->protocol == IPPROTO_TCP) {
        struct tcphdr *tcp = (void *)ip + 20;
        len = ntohs(ip->tot_len);
        if (len < 40 || len > LAUBERHORN_MTU || skb->len < len ||
            ip_fast_csum((u8 *)ip, 5) || tcp->doff < 5 || 20 + tcp->doff * 4 > len) goto out;
        /* Loopback/offloaded packets may still carry a partial checksum. */
        if (skb->ip_summed == CHECKSUM_PARTIAL && skb_checksum_help(skb)) goto out;
        ip = ip_hdr(skb); tcp = (void *)ip + 20;
        if (csum_tcpudp_magic(ip->saddr, ip->daddr, len - 20, IPPROTO_TCP,
                             csum_partial(tcp, len - 20, 0))) goto out;
        for (i = 0; i < ARRAY_SIZE(tcp_endpoints); ++i) {
            if (!tcp_endpoints[i].proc || tcp_endpoints[i].endpoint.port != ntohs(tcp->dest) ||
                tcp_endpoints[i].endpoint.address != ntohl(ip->daddr)) continue;
            desc.type = TY_NATIVE_TCP_RX; desc.payload_len = len;
            desc.tcp.endpoint = tcp_endpoints[i].endpoint.handle;
            enqueue(tcp_endpoints[i].proc, &desc, (u8 *)ip);
            goto out;
        }
        goto out;
    }
    udp = (void *)ip + 20;
    len = ntohs(udp->len);
    if (len < 8 || len > LAUBERHORN_MTU - 20 || ntohs(ip->tot_len) != len + 20 ||
        skb->len < len + 20 || ip_fast_csum((u8 *)ip, ip->ihl)) goto out;
    /* Includes CHECKSUM_PARTIAL loopback traffic and CHECKSUM_UNNECESSARY NIC
     * packets; validate bytes rather than relying on offload status. */
    if (udp->check && skb->ip_summed != CHECKSUM_PARTIAL &&
        csum_tcpudp_magic(ip->saddr, ip->daddr, len, IPPROTO_UDP, csum_partial(udp, len, 0))) goto out;
    bytes = (void *)(udp + 1); len -= 8; port = ntohs(udp->dest);
    for (i = 0; i < ARRAY_SIZE(endpoints); ++i) {
        struct vm_endpoint *ep = &endpoints[i];
        if (!ep->proc || ep->port != port) continue;
        if (ep->native) {
            desc.type = TY_NATIVE_UDP_RX; desc.payload_len = len;
            desc.udp.endpoint = ep->handle; desc.udp.saddr = ntohl(ip->saddr);
            desc.udp.daddr = ntohl(ip->daddr); desc.udp.sport = ntohs(udp->source); desc.udp.dport = port;
            enqueue(ep->proc, &desc, bytes);
        } else if (len >= 24 && word(bytes, 1) == 1 && word(bytes, 2) == 0 &&
                   word(bytes, 3) == 0 && word(bytes, 4) == 0) {
            unsigned int j;
            for (j = 0; j < ARRAY_SIZE(outgoing); ++j) {
                struct vm_context *c = &outgoing[j];
                if (c->proc != ep->proc || c->local_port != port || c->xid != word(bytes, 0) ||
                    c->remote != ntohl(ip->saddr) || c->remote_port != ntohs(udp->source)) continue;
                desc.type = TY_ONCRPC_REPLY_RX; desc.payload_len = len - 24;
                desc.oncrpc_reply_rx.pid = ep->proc->tgid; desc.oncrpc_reply_rx.xid = c->xid;
                desc.oncrpc_reply_rx.cookie = c->cookie; desc.oncrpc_reply_rx.status = word(bytes, 5) <= 5 ? word(bytes, 5) : 5;
                if (enqueue(ep->proc, &desc, bytes + 24)) memset(c, 0, sizeof(*c));
                break;
            }
        }
        goto out;
    }
    if (len < 40 || word(bytes, 1) != 0 || word(bytes, 2) != 2 ||
        word(bytes, 6) || word(bytes, 7) || word(bytes, 8) || word(bytes, 9)) goto out;
    for (i = 0; i < ARRAY_SIZE(services); ++i) {
        struct vm_service *s = &services[i];
        struct vm_context *c;
        if (!s->value.enabled || s->value.port != port || s->value.prog_num != word(bytes, 3) ||
            s->value.prog_ver != word(bytes, 4) || s->value.proc_num != word(bytes, 5)) continue;
        c = free_context(incoming);
        if (!c || next_token == U32_MAX) {
            u8 error[24] = {0};
            put_word(error, 0, word(bytes, 0)); put_word(error, 1, 1); put_word(error, 5, 5);
            send_udp(s->socket, ntohl(ip->saddr), ntohs(udp->source), error, sizeof(error));
            goto out;
        }
        *c = (struct vm_context){ .proc = s->value.proc, .socket = s->socket,
            .token = ++next_token, .xid = word(bytes, 0), .remote = ntohl(ip->saddr),
            .remote_port = ntohs(udp->source), .local_port = port };
        service_socket_get(c->socket);
        desc.type = TY_ONCRPC_CALL_RX; desc.payload_len = len - 40;
        desc.oncrpc_call_rx.func_ptr = (void *)s->value.func_ptr;
        desc.oncrpc_call_rx.request_token = c->token;
        if (!enqueue(c->proc, &desc, bytes + 40)) { service_socket_put(c->socket); memset(c, 0, sizeof(*c)); }
        goto out;
    }
out:
    kfree_skb(skb);
}
/* Linux supplies IP routing/neighbour resolution only; TCP bytes and protocol
 * state belong to the application stack. No Linux TCP send/receive is used. */
static int transmit_tcp(struct vm_cmd *cmd, const void *payload)
{
    u8 packet[LAUBERHORN_MTU];
    unsigned int size = cmd->desc.payload_len, i;
    struct iphdr *ip = (void *)packet;
    struct tcphdr *tcp = (void *)packet + 20;
    struct rtable *route;
    struct sk_buff *skb;
    struct flowi4 flow = { .flowi4_proto = IPPROTO_TCP };
    if (size < 40 || size > sizeof(packet)) return TX_ERROR;
    memcpy(packet, payload, size);
    if (ip->version != 4 || ip->ihl != 5 || ip->protocol != IPPROTO_TCP ||
        ip_is_fragment(ip) || ntohs(ip->tot_len) != size || tcp->doff < 5 ||
        20 + tcp->doff * 4 > size || ip_fast_csum(packet, 5) ||
        csum_tcpudp_magic(ip->saddr, ip->daddr, size - 20, IPPROTO_TCP,
                         csum_partial(tcp, size - 20, 0))) return TX_ERROR;
    for (i = 0; i < ARRAY_SIZE(tcp_endpoints); ++i)
        if (tcp_endpoints[i].proc == cmd->proc &&
            tcp_endpoints[i].endpoint.handle == cmd->desc.tcp.endpoint &&
            tcp_endpoints[i].endpoint.address == ntohl(ip->saddr) &&
            tcp_endpoints[i].endpoint.port == ntohs(tcp->source)) break;
    if (i == ARRAY_SIZE(tcp_endpoints)) return TX_ERROR;
    flow.saddr = ip->saddr; flow.daddr = ip->daddr;
    flow.fl4_sport = tcp->source; flow.fl4_dport = tcp->dest;
    route = ip_route_output_flow(&init_net, &flow, NULL);
    if (IS_ERR(route)) return TX_ERROR;
    skb = alloc_skb(LL_MAX_HEADER + size, GFP_KERNEL);
    if (!skb) { ip_rt_put(route); return TX_RETRY; }
    skb_reserve(skb, LL_MAX_HEADER);
    skb_reset_network_header(skb);
    skb_put_data(skb, packet, size);
    skb_set_transport_header(skb, 20);
    skb->protocol = htons(ETH_P_IP); skb->ip_summed = CHECKSUM_NONE;
    skb_dst_set(skb, &route->dst);
    /* net_xmit_eval may evaluate its argument twice; output consumes skb. */
    int result = ip_local_out(&init_net, NULL, skb);
    return net_xmit_eval(result) ? TX_ERROR : TX_ACCEPTED;
}
static int register_tcp_endpoint(struct vm_cmd *cmd)
{
    struct lauberhorn_tcp_endpoint *ep = &cmd->tcp_endpoint;
    struct socket *reservation;
    struct sockaddr_in addr = { .sin_family = AF_INET,
        .sin_addr.s_addr = htonl(ep->address), .sin_port = htons(ep->port) };
    unsigned int i, free_slot = ARRAY_SIZE(tcp_endpoints);
    int ret;
    if (!ep->address || !ep->port || ep->reserved || ep->handle) return -EINVAL;
    if (cmd->proc->num_rdy_thrs) return -EBUSY;
    if (inet_addr_type(&init_net, addr.sin_addr.s_addr) != RTN_LOCAL) return -EADDRNOTAVAIL;
    for (i = 0; i < ARRAY_SIZE(tcp_endpoints); ++i) {
        if (!tcp_endpoints[i].proc) { free_slot = i; continue; }
        if (tcp_endpoints[i].endpoint.port != ep->port) continue;
        if (tcp_endpoints[i].proc != cmd->proc || tcp_endpoints[i].endpoint.address != ep->address)
            return -EADDRINUSE;
        *ep = tcp_endpoints[i].endpoint; return 0;
    }
    if (free_slot == ARRAY_SIZE(tcp_endpoints)) return -ENOSPC;
    /* Bind without listen/connect reserves the host port; incoming packets
     * are stolen before the host TCP stack sees them. */
    ret = sock_create_kern(&init_net, AF_INET, SOCK_STREAM, IPPROTO_TCP, &reservation);
    if (ret) return ret;
    ret = kernel_bind(reservation, (struct sockaddr *)&addr, sizeof(addr));
    if (ret) { sock_release(reservation); return ret; }
    ep->handle = free_slot + 1;
    tcp_endpoints[free_slot].proc = cmd->proc;
    tcp_endpoints[free_slot].endpoint = *ep;
    tcp_endpoints[free_slot].reservation = reservation;
    WRITE_ONCE(hooked_tcp_addresses[ep->port], ep->address);
    return 0;
}
static int transmit(struct vm_cmd *cmd, struct vm_worker *worker)
{
    lauberhorn_pkt_desc_t *d = &cmd->desc;
    u8 packet[LAUBERHORN_MTU];
    void *payload = cmd->proc->vm_buffers + cmd->worker * LAUBERHORN_ECI_CORE_OFFSET + LH_VM_TX_OFFSET;
    struct socket *socket = NULL;
    struct vm_context *context = NULL;
    u32 remote = 0;
    u16 port = 0;
    unsigned int prefix = 0, i;
    bool call = false;
    if (d->execution || d->payload_len > LAUBERHORN_MTU) return TX_ERROR;
    switch (d->type) {
    case TY_EXECUTION_CONTROL: return d->payload_len ? TX_ERROR : TX_ACCEPTED;
    case TY_NATIVE_TCP_TX: return transmit_tcp(cmd, payload);
    case TY_NATIVE_UDP_TX:
        if (d->payload_len > LAUBERHORN_MTU - 28) return TX_ERROR;
        for (i = 0; i < ARRAY_SIZE(endpoints); ++i)
            if (endpoints[i].proc == cmd->proc && endpoints[i].native && endpoints[i].handle == d->udp.endpoint)
                socket = endpoints[i].socket;
        remote = d->udp.daddr; port = d->udp.dport;
        break;
    case TY_ONCRPC_REPLY_TX:
        for (i = 0; i < ARRAY_SIZE(incoming); ++i)
            if (incoming[i].proc == cmd->proc && incoming[i].token == d->oncrpc_reply_tx.request_token)
                context = &incoming[i];
        if (!context) return TX_ERROR;
        socket = context->socket; remote = context->remote; port = context->remote_port;
        prefix = 24; memset(packet, 0, prefix);
        put_word(packet, 0, context->xid); put_word(packet, 1, 1);
        put_word(packet, 5, d->oncrpc_reply_tx.status);
        break;
    case TY_ONCRPC_CALL_TX:
        if (d->oncrpc_call_tx.pid != cmd->proc->tgid) return TX_ERROR;
        for (i = 0; i < ARRAY_SIZE(endpoints); ++i)
            if (endpoints[i].proc == cmd->proc && !endpoints[i].native && endpoints[i].port == d->oncrpc_call_tx.sport)
                socket = endpoints[i].socket;
        context = free_context(outgoing);
        if (!context) return TX_RETRY;
        remote = d->oncrpc_call_tx.daddr; port = d->oncrpc_call_tx.dport;
        prefix = 40; call = true; memset(packet, 0, prefix);
        put_word(packet, 0, d->oncrpc_call_tx.xid); put_word(packet, 2, 2);
        put_word(packet, 3, d->oncrpc_call_tx.prog_num); put_word(packet, 4, d->oncrpc_call_tx.prog_ver);
        put_word(packet, 5, d->oncrpc_call_tx.proc_num);
        break;
    default: return TX_ERROR;
    }
    if (!socket || !port || d->payload_len + prefix > LAUBERHORN_MTU - 28) return TX_ERROR;
    memcpy(packet + prefix, payload, d->payload_len);
    if (call) *context = (struct vm_context){ .proc = cmd->proc, .socket = socket,
        .xid = d->oncrpc_call_tx.xid, .cookie = d->oncrpc_call_tx.cookie,
        .remote = remote, .remote_port = port, .local_port = d->oncrpc_call_tx.sport };
    /* The host UDP stack owns the copied bytes before completion. Peer receipt
     * is not implied. Loopback ingress is queued, never recursively processed. */
    if (send_udp(socket, remote, port, packet, prefix + d->payload_len) < 0) {
        if (call) memset(context, 0, sizeof(*context));
        return TX_ERROR;
    }
    if (context && !call) { service_socket_put(context->socket); memset(context, 0, sizeof(*context)); }
    return TX_ACCEPTED;
}
/* Match Scheduler.scala policy at device-event boundaries. This does not model
 * its lookup pipeline latency. pending reserves the next owner, just as
 * corePidMap does before coreResident changes at handoff. */
static unsigned int assigned(u32 slot)
{
    unsigned int i, count = 0;
    for (i = 0; i < ARRAY_SIZE(workers); ++i)
        count += (workers[i].pending ? workers[i].pending : workers[i].assigned_slot) == slot;
    return count;
}
static bool eligible(struct proc_def *proc)
{
    return proc && !READ_ONCE(proc->failed) && proc->declared_workers &&
        READ_ONCE(proc->num_rdy_thrs) == proc->declared_workers &&
        assigned(proc->idx) < proc->declared_workers;
}
static void reserve_worker(struct vm_worker *w, u32 slot)
{
    WRITE_ONCE(w->pending, slot);
    if (!w->assigned_slot) {
        atomic_set(&w->shared.kick, 1);
        wake_up(&w->shared.dispatch_wait);
    }
}
static void packet_arrival(struct proc_def *proc, unsigned int fill)
{
    unsigned int i;
    bool ready = 2 * fill >= LAUBERHORN_RX_PKTS_PER_PROC;
    if (!eligible(proc) || (!ready && assigned(proc->idx))) return;
    /* Idle first, then first RX-ready victim; no donor watermark restriction
     * on packet-driven reclaim in the current hardware. */
    for (i = 0; i < ARRAY_SIZE(workers); ++i)
        if (!workers[i].assigned_slot && !workers[i].pending) {
            reserve_worker(&workers[i], proc->idx); ++domains[proc->idx].arrival_idle; return;
        }
    if (!ready) return;
    for (i = 0; i < ARRAY_SIZE(workers); ++i)
        if (!workers[i].pending && workers[i].rx && !READ_ONCE(workers[i].rx->proc->failed) &&
            workers[i].assigned_slot != proc->idx) {
            reserve_worker(&workers[i], proc->idx); ++domains[proc->idx].arrival_ready; return;
        }
}
static u32 background_domain(void)
{
    u32 slot;
    /* LookupTable's non-single-match port uses firstV2, not round robin. */
    for (slot = 1; slot <= LAUBERHORN_NUM_PROCS; ++slot)
        if (domains[slot].count && !assigned(slot) && eligible(find_proc_slot(slot)))
            return slot;
    return 0;
}
static bool deadline_ready(u32 slot)
{
    return domains[slot].deadline.pending && !domains[slot].deadline.delivered;
}
static void schedule_deadlines(void)
{
    u64 now = ktime_get_ns();
    unsigned int slot, n, i;
    for (slot = 1; slot <= LAUBERHORN_NUM_PROCS; ++slot) {
        struct proc_def *p = find_proc_slot(slot);
        struct lauberhorn_deadline_state *d = &domains[slot].deadline;
        if (p && READ_ONCE(p->failed)) { d->next_deadline_ns = 0; d->pending = d->delivered = 0; }
        else if (d->next_deadline_ns && now >= d->next_deadline_ns) d->pending = 1;
    }
    /* One deadline allocation per pass, rotating eligible domains. Packet
     * arrival reservations have already run. Deadline-only work never scales
     * above one core and cannot reclaim from a donor above the watermark. */
    for (n = 0; n < LAUBERHORN_NUM_PROCS; ++n) {
        slot = 1 + ((deadline_cursor + n) % LAUBERHORN_NUM_PROCS);
        if (!deadline_ready(slot) || assigned(slot) || !eligible(find_proc_slot(slot))) continue;
        for (i = 0; i < ARRAY_SIZE(workers); ++i)
            if (!workers[i].assigned_slot && !workers[i].pending) goto grant;
        for (i = 0; i < ARRAY_SIZE(workers); ++i)
            if (!workers[i].pending && workers[i].rx && !READ_ONCE(workers[i].rx->proc->failed) &&
                2 * domains[workers[i].assigned_slot].count <= LAUBERHORN_RX_PKTS_PER_PROC)
                goto grant;
        continue;
    grant:
        domains[slot].last_deadline_donor_fill = domains[workers[i].assigned_slot].count;
        reserve_worker(&workers[i], slot);
        ++domains[slot].deadline_activations;
        deadline_cursor = slot;
        break;
    }
}
static void schedule_device(void)
{
    unsigned int i;
    schedule_deadlines();
    for (i = 0; i < ARRAY_SIZE(workers); ++i) {
        struct vm_worker *w = &workers[i];
        struct vm_cmd *rx = w->rx;
        if (rx && READ_ONCE(rx->proc->failed)) { w->rx = NULL; finish(rx, -ESHUTDOWN); continue; }
        if (!w->pending && ((!w->assigned_slot) ||
            (rx && !domains[w->assigned_slot].count && !deadline_ready(w->assigned_slot)))) {
            u32 next = background_domain();
            if (next) { reserve_worker(w, next); ++domains[next].background; }
        }
        if (!rx) continue;
        if (w->pending) {
            rx->desc = (lauberhorn_pkt_desc_t){ .type = TY_DESCHED };
        } else if (deadline_ready(w->assigned_slot) &&
            (!domains[w->assigned_slot].packet_due || !domains[w->assigned_slot].count)) {
            struct lauberhorn_deadline_state *d = &domains[w->assigned_slot].deadline;
            rx->desc = (lauberhorn_pkt_desc_t){ .type = TY_DEADLINE,
                .deadline.generation = d->generation };
            d->delivered = 1; /* Explicit SET acknowledges; activation never does. */
            domains[w->assigned_slot].packet_due = true;
        } else if (domains[w->assigned_slot].count) {
            struct vm_domain *d = &domains[w->assigned_slot];
            struct vm_packet *p = list_first_entry(&d->packets, struct vm_packet, link);
            rx->desc = p->desc;
            d->packet_due = false;
            memcpy(rx->proc->vm_buffers + i * LAUBERHORN_ECI_CORE_OFFSET, p->bytes, p->desc.payload_len);
            list_del(&p->link); --d->count; kfree(p); w->borrowed = true;
        } else if (time_before(jiffies, rx->expires)) continue;
        else { w->rx = NULL; w->rx_next ^= 1; finish(rx, 0); continue; }
        w->rx = NULL; w->rx_next ^= 1; finish(rx, 1);
    }
}
static int service_update(struct vm_cmd *cmd)
{
    struct vm_service *s = &services[cmd->srv->idx];
    struct socket *socket = NULL;
    unsigned int i;
    int ret;
    struct sockaddr_in addr = { .sin_family = AF_INET, .sin_port = htons(cmd->srv->port) };
    if (!cmd->enable) {
        struct vm_packet *packet, *next;
        struct vm_domain *domain = &domains[s->value.proc->idx];
        /* Deregistration is allowed only before worker registration. Retire
         * queued admissions before userspace releases the handler pointer. */
        list_for_each_entry_safe(packet, next, &domain->packets, link) {
            if (packet->desc.type != TY_ONCRPC_CALL_RX ||
                packet->desc.oncrpc_call_rx.func_ptr != (void *)s->value.func_ptr) continue;
            for (i = 0; i < ARRAY_SIZE(incoming); ++i)
                if (incoming[i].proc == s->value.proc &&
                    incoming[i].token == packet->desc.oncrpc_call_rx.request_token) {
                    service_socket_put(incoming[i].socket);
                    memset(&incoming[i], 0, sizeof(incoming[i]));
                }
            list_del(&packet->link); --domain->count; kfree(packet);
        }
        socket = s->socket; s->value.enabled = false; s->socket = NULL;
        bool listening = false;
        for (i = 0; i < ARRAY_SIZE(services); ++i)
            if (services[i].socket == socket) listening = true;
        if (!listening) WRITE_ONCE(hooked_ports[cmd->srv->port], 0);
        if (socket) service_socket_put(socket);
        return 0;
    }
    for (i = 0; i < ARRAY_SIZE(services); ++i)
        if (services[i].value.enabled && services[i].value.port == cmd->srv->port) socket = services[i].socket;
    if (!socket) {
        unsigned int slot;
        for (slot = 0; slot < ARRAY_SIZE(service_sockets); ++slot)
            if (!service_sockets[slot].socket) break;
        if (slot == ARRAY_SIZE(service_sockets)) return -ENOSPC;
        ret = sock_create_kern(&init_net, AF_INET, SOCK_DGRAM, IPPROTO_UDP, &socket);
        if (ret) return ret;
        ret = kernel_bind(socket, (struct sockaddr *)&addr, sizeof(addr));
        if (ret) { sock_release(socket); return ret; }
        service_sockets[slot].socket = socket;
        service_sockets[slot].refs = 0;
    }
    service_socket_get(socket);
    s->value = *cmd->srv; s->value.enabled = true; s->socket = socket;
    WRITE_ONCE(hooked_ports[cmd->srv->port], 1);
    return 0;
}
static void process_command(struct vm_cmd *cmd)
{
    unsigned int i;
    struct vm_worker *w;
    long result = 0;
    switch (cmd->kind) {
    case TCP_ENDPOINT: result = register_tcp_endpoint(cmd); break;
    case DEADLINE_SET: {
        struct lauberhorn_deadline_state *d = &domains[cmd->proc->idx].deadline;
        u64 now = ktime_get_ns(), next = cmd->deadline_set.next_deadline_ns;
        if (READ_ONCE(cmd->proc->failed)) { result = -ESHUTDOWN; break; }
        if (cmd->deadline_set.expected_generation != d->generation) { result = -ESTALE; break; }
        if (d->generation == U64_MAX) { result = -EOVERFLOW; break; }
        if (next && (next < now || next - now < LH_VM_DEADLINE_MIN_LEAD_NS)) { result = -ETIME; break; }
        d->generation++;
        d->next_deadline_ns = next;
        d->pending = d->delivered = 0;
        cmd->deadline_set.generation = d->generation;
        break;
    }
    case DEADLINE_GET:
        cmd->deadline_state = domains[cmd->proc->idx].deadline;
        cmd->deadline_state.minimum_lead_ns = LH_VM_DEADLINE_MIN_LEAD_NS;
        break;
    case SCHEDULE_STATE: {
        struct vm_domain *d = &domains[cmd->proc->idx];
        struct lh_vm_schedule_state *s = &cmd->schedule_state;
        *s = (struct lh_vm_schedule_state){ .queued = d->count,
            .capacity = LAUBERHORN_RX_PKTS_PER_PROC,
            .registered = READ_ONCE(cmd->proc->num_rdy_thrs),
            .last_deadline_donor_fill = d->last_deadline_donor_fill,
            .arrival_idle = d->arrival_idle, .arrival_ready = d->arrival_ready,
            .background = d->background, .deadline = d->deadline_activations };
        for (i = 0; i < ARRAY_SIZE(workers); ++i) {
            s->assigned += workers[i].assigned_slot == cmd->proc->idx;
            s->reserved += workers[i].pending == cmd->proc->idx;
            s->ready_workers += workers[i].assigned_slot == cmd->proc->idx && workers[i].rx != NULL;
        }
        break;
    }
    case SERVICE: result = service_update(cmd); break;
    case ENDPOINT:
        for (i = 0; i < ARRAY_SIZE(endpoints); ++i)
            if ((cmd->enable && !endpoints[i].proc) || (!cmd->enable && endpoints[i].handle == cmd->handle)) {
                endpoints[i] = cmd->enable ? (struct vm_endpoint){ .proc = cmd->proc, .handle = cmd->handle,
                    .port = cmd->port, .native = cmd->native, .socket = cmd->socket } : (struct vm_endpoint){0};
                WRITE_ONCE(hooked_ports[cmd->port], cmd->enable);
                break;
            }
        break;
    case BUSY:
        for (i = 0; i < ARRAY_SIZE(outgoing); ++i)
            if (outgoing[i].proc && outgoing[i].local_port == cmd->port) result = 1;
        break;
    case RETIRE:
        for (i = 0; i < ARRAY_SIZE(outgoing); ++i)
            if (outgoing[i].proc == cmd->proc && outgoing[i].cookie == cmd->cookie) memset(&outgoing[i], 0, sizeof(outgoing[i]));
        break;
    case ACTIVATE:
        w = &workers[cmd->worker];
        if (w->pending && !w->borrowed && !w->reserved) {
            struct proc_def *p = find_proc_slot(w->pending);
            w->assigned_slot = w->pending; WRITE_ONCE(w->pending, 0);
            cmd->activation = (struct lh_device_activation){ .domain_slot = p->idx,
                .domain_code = p->domain_code, .rx_next = w->rx_next, .tx_next = w->tx_next };
        } else result = -EPROTO;
        break;
    case TRANSPORT:
        w = &workers[cmd->worker];
        if (READ_ONCE(cmd->proc->failed)) { result = -ESHUTDOWN; break; }
        if (w->assigned_slot != cmd->proc->idx || w->pending) { result = -EPERM; break; }
        switch (cmd->op) {
        case LH_VM_RX:
            if (w->borrowed || w->reserved || w->rx) { result = -EBUSY; break; }
            cmd->expires = jiffies + msecs_to_jiffies(LH_VM_RX_WAIT_MS);
            w->rx = cmd; return;
        case LH_VM_RX_RELEASE:
            if (!w->borrowed) result = -EINVAL;
            w->borrowed = false; break;
        case LH_VM_TX_RESERVE:
            if (w->reserved) result = -EBUSY;
            else w->reserved = true;
            break;
        case LH_VM_TX_CANCEL:
            if (!w->reserved) result = -EINVAL;
            w->reserved = false; break;
        case LH_VM_TX:
            if (!w->reserved) { result = -EINVAL; break; }
            result = transmit(cmd, w); w->reserved = false; w->tx_next ^= 1; break;
        default: result = -EINVAL;
        }
        break;
    }
    finish(cmd, result);
}
static int device_thread(void *unused)
{
    while (!kthread_should_stop()) {
        struct vm_cmd *cmd, *next;
        struct sk_buff *skb;
        unsigned int count = 0;
        unsigned long flags;
        LIST_HEAD(batch);
        wait_event_interruptible_timeout(device_wait, kthread_should_stop() || atomic_read(&work_pending),
                                         msecs_to_jiffies(LH_VM_RX_WAIT_MS));
        atomic_set(&work_pending, 0);
        spin_lock_irqsave(&command_lock, flags);
        list_splice_init(&commands, &batch);
        spin_unlock_irqrestore(&command_lock, flags);
        list_for_each_entry_safe(cmd, next, &batch, link) { list_del(&cmd->link); process_command(cmd); }
        while (count++ < LH_VM_QUEUE_DEPTH && (skb = skb_dequeue(&ingress))) receive_packet(skb);
        schedule_device();
        if (!skb_queue_empty(&ingress)) lh_vm_notify();
    }
    return 0;
}
long lh_vm_control(struct proc_def *proc, unsigned int op, unsigned long arg)
{
    struct vm_cmd cmd = { .proc = proc };
    long result;
    if (op == LAUBERHORN_IOCTL_REG_TCP_ENDPOINT) {
        if (!net_eq(current->nsproxy->net_ns, &init_net)) return -EXDEV;
        cmd.kind = TCP_ENDPOINT;
        if (copy_from_user(&cmd.tcp_endpoint, (void __user *)arg, sizeof(cmd.tcp_endpoint))) return -EFAULT;
        if (cmd.tcp_endpoint.port < 1024 && !capable(CAP_NET_BIND_SERVICE)) return -EACCES;
        result = submit(&cmd);
        if (!result && copy_to_user((void __user *)arg, &cmd.tcp_endpoint, sizeof(cmd.tcp_endpoint))) return -EFAULT;
    } else if (op == LAUBERHORN_IOCTL_SET_DEADLINE) {
        cmd.kind = DEADLINE_SET;
        if (copy_from_user(&cmd.deadline_set, (void __user *)arg, sizeof(cmd.deadline_set))) return -EFAULT;
        result = submit(&cmd);
        if (!result && copy_to_user((void __user *)arg, &cmd.deadline_set, sizeof(cmd.deadline_set))) return -EFAULT;
    } else if (op == LAUBERHORN_IOCTL_GET_DEADLINE) {
        cmd.kind = DEADLINE_GET;
        result = submit(&cmd);
        if (!result && copy_to_user((void __user *)arg, &cmd.deadline_state, sizeof(cmd.deadline_state))) return -EFAULT;
    } else {
        cmd.kind = SCHEDULE_STATE;
        result = submit(&cmd);
        if (!result && copy_to_user((void __user *)arg, &cmd.schedule_state, sizeof(cmd.schedule_state))) return -EFAULT;
    }
    return result;
}
long lh_vm_ioctl(struct proc_def *proc, unsigned int op, unsigned long arg)
{
    struct vm_cmd cmd = { .kind = TRANSPORT, .proc = proc, .op = op };
    unsigned long flags;
    int i, worker = -1;
    long result;
    spin_lock_irqsave(&claim_lock, flags);
    for (i = 0; i < proc->declared_workers; ++i)
        if (proc->thr_defs[i].task == current) worker = proc->thr_defs[i].worker_idx;
    spin_unlock_irqrestore(&claim_lock, flags);
    if (worker < 0 || worker >= ARRAY_SIZE(workers)) return -EPERM;
    cmd.worker = worker;
    if (op == LH_VM_TX && copy_from_user(&cmd.desc, (void __user *)arg, sizeof(cmd.desc))) return -EFAULT;
    result = submit(&cmd);
    if (op == LH_VM_RX && result > 0 && copy_to_user((void __user *)arg, &cmd.desc, sizeof(cmd.desc))) {
        fail_domain(proc); return -EFAULT;
    }
    return result;
}
int lh_vm_init(void)
{
    unsigned int i, started = 0;
    int ret;
    if (num_online_cpus() <= ARRAY_SIZE(workers)) return -EINVAL;
    for (i = 0; i < ARRAY_SIZE(domains); ++i) INIT_LIST_HEAD(&domains[i].packets);
    skb_queue_head_init(&ingress);
    for (i = 0; i < ARRAY_SIZE(workers); ++i) {
        workers[i].shared.index = i;
        workers[i].shared.cpu = num_online_cpus() - ARRAY_SIZE(workers) + i;
        if (housekeeping_test_cpu(workers[i].shared.cpu, HK_TYPE_DOMAIN)) { ret = -EINVAL; goto stop; }
        ret = start_worker_dispatch(&workers[i].shared);
        if (ret) goto stop;
        ++started;
    }
    emulator = kthread_create(device_thread, NULL, "lh_device");
    if (IS_ERR(emulator)) { ret = PTR_ERR(emulator); goto stop; }
    kthread_bind(emulator, 0); wake_up_process(emulator);
    ret = nf_register_net_hook(&init_net, &hook);
    if (!ret) return 0;
    kthread_stop(emulator);
stop:
    while (started) stop_worker_dispatch(&workers[--started].shared);
    return ret;
}
void lh_vm_exit(void)
{
    unsigned int i;
    nf_unregister_net_hook(&init_net, &hook);
    kthread_stop(emulator);
    for (i = 0; i < ARRAY_SIZE(workers); ++i) stop_worker_dispatch(&workers[i].shared);
    skb_queue_purge(&ingress);
    for (i = 0; i < ARRAY_SIZE(tcp_endpoints); ++i)
        if (tcp_endpoints[i].reservation) sock_release(tcp_endpoints[i].reservation);
}
