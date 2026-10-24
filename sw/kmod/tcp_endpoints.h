/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Included by chrdev.c. Reservations, like UDP endpoints, are retained for
 * the domain lifetime. Every operation is serialized by control_lock. */
#if !LH_BACKEND_VM
#include "lauberhorn_eci_NativeTcpDecoder_dev.h"
#include "lauberhorn_eci_IpDecoder_dev.h"

static struct {
    struct proc_def *proc;
    struct socket *socket;
    struct lauberhorn_tcp_endpoint endpoint;
} tcp_endpoints[LAUBERHORN_NUM_LISTEN_PORTS];

static long register_tcp_endpoint(struct file_priv *fp, void __user *arg)
{
    struct lauberhorn_tcp_endpoint ep;
    struct socket *reservation;
    struct sockaddr_in addr = { .sin_family = AF_INET };
    lauberhorn_eci_NativeTcpDecoder_t tcp;
    lauberhorn_eci_IpDecoder_t ip;
    unsigned int i, slot = ARRAY_SIZE(tcp_endpoints);
    int ret;

    if (!net_eq(current->nsproxy->net_ns, &init_net)) return -EXDEV;
    if (fp->pd->num_rdy_thrs) return -EBUSY;
    if (copy_from_user(&ep, arg, sizeof(ep))) return -EFAULT;
    if (!ep.address || !ep.port || ep.reserved || ep.handle) return -EINVAL;
    if (ep.port < 1024 && !capable(CAP_NET_BIND_SERVICE)) return -EACCES;
    lauberhorn_eci_IpDecoder_initialize(&ip, LAUBERHORN_ECI__IP_DECODER_BASE);
    /* The IP encoder has one NIC source address; accepting any other local
     * address would make TCP checksums and source authorization disagree. */
    if (htonl(ep.address) != lauberhorn_eci_IpDecoder_ctrl_ip_address_rd(&ip))
        return -EADDRNOTAVAIL;
    for (i = 0; i < ARRAY_SIZE(tcp_endpoints); ++i) {
        if (!tcp_endpoints[i].proc) { slot = i; continue; }
        if (tcp_endpoints[i].endpoint.port != ep.port) continue;
        if (tcp_endpoints[i].proc != fp->pd || tcp_endpoints[i].endpoint.address != ep.address)
            return -EADDRINUSE;
        ep = tcp_endpoints[i].endpoint;
        return copy_to_user(arg, &ep, sizeof(ep)) ? -EFAULT : 0;
    }
    if (slot == ARRAY_SIZE(tcp_endpoints)) return -ENOSPC;
    ret = sock_create_kern(&init_net, AF_INET, SOCK_STREAM, IPPROTO_TCP, &reservation);
    if (ret) return ret;
    addr.sin_addr.s_addr = htonl(ep.address);
    addr.sin_port = htons(ep.port);
    /* Reserve without listen/connect: the NIC owns incoming TCP segments. */
    ret = kernel_bind(reservation, (struct sockaddr *)&addr, sizeof(addr));
    if (ret) goto release;
    ep.handle = slot + 1;
    if (copy_to_user(arg, &ep, sizeof(ep))) { ret = -EFAULT; goto release; }
    tcp_endpoints[slot].proc = fp->pd;
    tcp_endpoints[slot].socket = reservation;
    tcp_endpoints[slot].endpoint = ep;
    lauberhorn_eci_NativeTcpDecoder_initialize(&tcp, LAUBERHORN_ECI__NATIVE_TCP_DECODER_BASE);
    lauberhorn_eci_NativeTcpDecoder_ctrl_listen_enabled_wr(&tcp, 1);
    lauberhorn_eci_NativeTcpDecoder_ctrl_listen_port_wr(&tcp, ep.port);
    lauberhorn_eci_NativeTcpDecoder_ctrl_listen_pid_wr(&tcp, fp->pd->tgid);
    lauberhorn_eci_NativeTcpDecoder_ctrl_listen_endpoint_wr(&tcp, ep.handle);
    lauberhorn_eci_NativeTcpDecoder_ctrl_listen_idx_wr(&tcp, slot);
    return 0;
release:
    sock_release(reservation);
    return ret;
}
#endif
