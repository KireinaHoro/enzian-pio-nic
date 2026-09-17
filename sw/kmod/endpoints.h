/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Included by chrdev.c; all operations hold control_lock. */
struct endpoint_reservation {
    struct socket *socket;
    u32 handle;
    u16 port;
    unsigned int listen_idx;
};
static struct endpoint_reservation *endpoint_ports[LAUBERHORN_NUM_LISTEN_PORTS];
static u32 next_endpoint_handle;

static bool endpoint_port_reserved(u16 port)
{
    int i;
    for (i = 0; i < LAUBERHORN_NUM_LISTEN_PORTS; ++i)
        if (endpoint_ports[i] && endpoint_ports[i]->port == port) return true;
    return false;
}

static void endpoint_listener(struct file_priv *fp,
                              struct endpoint_reservation *ep, bool enable)
{
    lauberhorn_eci_UdpDecoder_ctrl_listen_port_wr(&fp->dev->UdpDecoder_dev, ep->port);
    lauberhorn_eci_UdpDecoder_ctrl_listen_next_proto_wr(&fp->dev->UdpDecoder_dev,
        enable ? lauberhorn_eci_listen_onc_rpc_reply : lauberhorn_eci_listen_disabled);
    lauberhorn_eci_UdpDecoder_ctrl_listen_idx_wr(&fp->dev->UdpDecoder_dev, ep->listen_idx);
}

static void release_endpoint(struct file_priv *fp, int idx)
{
    struct endpoint_reservation *ep = fp->endpoints[idx];
    endpoint_listener(fp, ep, false);
    endpoint_ports[ep->listen_idx] = NULL;
    listen_occupied[ep->listen_idx] = false;
    sock_release(ep->socket);
    kfree(ep);
    fp->endpoints[idx] = NULL;
}

static long register_endpoint(struct file_priv *fp, void __user *arg)
{
    lauberhorn_reg_endpoint_t cmd;
    struct endpoint_reservation *ep;
    struct sockaddr_in addr = { .sin_family = AF_INET };
    int i, j, ret;
    /* The NIC currently lives in init_net. Do not bind in a caller's
     * unrelated namespace or permit moving the NIC while registered. */
    if (!net_eq(current->nsproxy->net_ns, &init_net)) return -EXDEV;
    if (fp->pd->num_rdy_thrs) return -EBUSY;
    if (copy_from_user(&cmd, arg, sizeof(cmd))) return -EFAULT;
    if (cmd.handle || cmd.pid || cmd.port || cmd.reserved) return -EINVAL;
    if (next_endpoint_handle == U32_MAX) return -ENOSPC;
    for (i = 0; i < LAUBERHORN_NUM_LISTEN_PORTS; ++i)
        if (!listen_occupied[i]) break;
    if (i == LAUBERHORN_NUM_LISTEN_PORTS) return -ENOSPC;
    ep = kzalloc(sizeof(*ep), GFP_KERNEL);
    if (!ep) return -ENOMEM;
    ret = sock_create_kern(&init_net, AF_INET, SOCK_DGRAM, IPPROTO_UDP, &ep->socket);
    if (ret) goto free_ep;
    /* Explicitly scan the dynamic range so FPGA service collisions cannot
     * repeatedly select the same ephemeral port. No SO_REUSE* is enabled. */
    for (j = 49152; j <= 65535; ++j) {
        int s;
        bool collision = endpoint_port_reserved(j);
        for (s = 0; s < LAUBERHORN_NUM_SERVICES; ++s)
            if (srv_defs[s].enabled && srv_defs[s].port == j) collision = true;
        if (collision) continue;
        addr.sin_port = htons(j);
        ret = kernel_bind(ep->socket, (struct sockaddr *)&addr, sizeof(addr));
        if (!ret) break;
        if (ret != -EADDRINUSE) goto close_socket;
    }
    if (j > 65535) { ret = -EADDRINUSE; goto close_socket; }
    ep->port = j;
    ep->listen_idx = i;
    ep->handle = ++next_endpoint_handle;
    cmd.handle = ep->handle;
    cmd.pid = fp->pd->tgid;
    cmd.port = ep->port;
    if (copy_to_user(arg, &cmd, sizeof(cmd))) { ret = -EFAULT; goto close_socket; }
    fp->endpoints[i] = ep;
    endpoint_ports[i] = ep;
    listen_occupied[i] = true;
    endpoint_listener(fp, ep, true);
    return 0;
close_socket:
    sock_release(ep->socket);
free_ep:
    kfree(ep);
    return ret;
}

static long deregister_endpoint(struct file_priv *fp, void __user *arg)
{
    lauberhorn_dereg_endpoint_t cmd;
    lauberhorn_eci_OncRpcReplyDecoder_t dev;
    int i, j;
    if (copy_from_user(&cmd, arg, sizeof(cmd))) return -EFAULT;
    if (cmd.reserved) return -EINVAL;
    if (fp->pd->num_rdy_thrs) return -EBUSY;
    for (i = 0; i < LAUBERHORN_NUM_LISTEN_PORTS; ++i)
        if (fp->endpoints[i] && fp->endpoints[i]->handle == cmd.handle) break;
    if (i == LAUBERHORN_NUM_LISTEN_PORTS) return -ENOENT;
    lauberhorn_eci_OncRpcReplyDecoder_initialize(&dev,
        LAUBERHORN_ECI__ONC_RPC_REPLY_DECODER_BASE);
    for (j = 0; j < LAUBERHORN_NUM_SESSIONS; ++j) {
        lauberhorn_eci_OncRpcReplyDecoder_stat_req_readback_idx_wr(&dev, j);
        if (lauberhorn_eci_OncRpcReplyDecoder_stat_req_readback_active_rd(&dev) &&
            lauberhorn_eci_OncRpcReplyDecoder_stat_req_readback_local_port_rd(&dev) == fp->endpoints[i]->port)
            return -EBUSY;
    }
    release_endpoint(fp, i);
    return 0;
}
