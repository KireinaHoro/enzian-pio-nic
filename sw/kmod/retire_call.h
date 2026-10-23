/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
/* Included by chrdev.c; control_lock serializes command staging and completion. */
static long retire_call(struct file_priv *fp, void __user *arg)
{
    lauberhorn_retire_call_t cmd;
#if !LH_BACKEND_VM
    lauberhorn_eci_OncRpcReplyDecoder_t dev;
    unsigned long busy;
#endif
    int i;
    if (copy_from_user(&cmd, arg, sizeof(cmd))) return -EFAULT;
    for (i = 0; i < LAUBERHORN_NUM_LISTEN_PORTS; ++i)
        if (fp->endpoints[i] && fp->endpoints[i]->handle == cmd.endpoint_handle) break;
    if (i == LAUBERHORN_NUM_LISTEN_PORTS) return -ENOENT;
#if LH_BACKEND_VM
    return lh_vm_retire(fp->pd, cmd.cookie);
#else
    lauberhorn_eci_OncRpcReplyDecoder_initialize(&dev,
        LAUBERHORN_ECI__ONC_RPC_REPLY_DECODER_BASE);
    if (lauberhorn_eci_OncRpcReplyDecoder_stat_retire_busy_rd(&dev)) return -EBUSY;
    lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_pid_wr(&dev, fp->pd->tgid);
    lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_cookie_wr(&dev, cmd.cookie);
    lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_wr(&dev, 1);
    return read_poll_timeout(lauberhorn_eci_OncRpcReplyDecoder_stat_retire_busy_rd,
        busy, !busy, 10, 100000, false, &dev);
#endif
}
