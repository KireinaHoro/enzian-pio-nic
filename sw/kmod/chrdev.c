// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu, Zikai Liu

#include "common.h"
#define CREATE_TRACE_POINTS
#include "trace.h"
#include <linux/net.h>
#include <linux/sched/signal.h>
#include <linux/nsproxy.h>
#include <net/net_namespace.h>
#include <net/inet_sock.h>
#include "lauberhorn_eci_OncRpcReplyDecoder_dev.h"
#include "ioctl.h"

#include "lauberhorn_eci_sched_dev.h"
#include "lauberhorn_eci_OncRpcCallDecoder_dev.h"
#include "lauberhorn_eci_UdpDecoder_dev.h"
#include "lauberhorn_eci_OncRpcReplyEncoder_dev.h"
#include "lauberhorn_eci_dev.h"
#include "regblock_bases.h"

static dev_t devt = 0;
static struct class *dev_class;

static struct srv_def srv_defs[LAUBERHORN_NUM_SERVICES];
static struct proc_def proc_defs[LAUBERHORN_NUM_PROCS + 1];
static DEFINE_MUTEX(control_lock);
static u64 next_process_lifetime;
static bool listen_occupied[LAUBERHORN_NUM_LISTEN_PORTS];
static unsigned int listen_users[LAUBERHORN_NUM_LISTEN_PORTS];

struct worker_dev {
	struct cdev cdev;

	lauberhorn_eci_OncRpcCallDecoder_t OncRpcCallDecoder_dev;
	lauberhorn_eci_UdpDecoder_t UdpDecoder_dev;
	lauberhorn_eci_OncRpcReplyEncoder_t OncRpcReplyEncoder_dev;
	lauberhorn_eci_sched_t sched_dev;
};

struct endpoint_reservation;
static void recovery_monitor(struct work_struct *work);
static DECLARE_DELAYED_WORK(recovery_work, recovery_monitor);
struct file_priv {
	struct proc_def *pd;
	struct worker_dev *dev;
	struct endpoint_reservation *endpoints[LAUBERHORN_NUM_LISTEN_PORTS];
};

static struct file_priv *app_files[LAUBERHORN_NUM_PROCS + 1];

#include "stats/worker.h"
#include "endpoints.h"
#include <linux/iopoll.h>
#include "recovery.h"

struct proc_def *find_proc(pid_t tgid)
{
	int i;
	for (i = 1; i <= LAUBERHORN_NUM_PROCS; ++i) {
		if (READ_ONCE(proc_defs[i].enabled) && READ_ONCE(proc_defs[i].tgid) == tgid) {
			return &proc_defs[i];
		}
	}
	return NULL;
}

static int register_service(struct worker_dev *dev, u16 port, u32 prog_num,
			    u32 prog_ver, u32 proc_num, void __user *func_ptr,
			    struct proc_def *proc)
{
	int i, proc_srv_idx, listen_idx = -1, srv_idx = -1;
	struct srv_def *srv;

	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		if (!srv_defs[i].enabled) {
			if (srv_idx < 0) srv_idx = i;
		} else if (srv_defs[i].port == port &&
			   srv_defs[i].prog_num == prog_num &&
			   srv_defs[i].prog_ver == prog_ver &&
			   srv_defs[i].proc_num == proc_num) {
			pr_err("Service prog=%d ver=%d proc=%d on UDP port %d already registered as #%d!\n",
			       prog_num, prog_ver, proc_num, port, i);
			return -1;
		}
	}

	if (srv_idx < 0) {
		pr_err("No more free service slots in HW: %d already registered\n",
		       i);
		return -1;
	}

	if (endpoint_port_reserved(port)) return -EADDRINUSE;
	srv = &srv_defs[srv_idx];
	/* Multiple RPC procedures on one UDP port share one classifier entry. */
    for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i)
        if (srv_defs[i].enabled && srv_defs[i].port == port) {
            listen_idx = srv_defs[i].listen_idx;
            break;
        }
    if (listen_idx < 0) {
        for (i = 0; i < LAUBERHORN_NUM_LISTEN_PORTS; ++i)
            if (!listen_occupied[i]) { listen_idx = i; break; }
        if (listen_idx < 0) return -ENOSPC;
        listen_occupied[listen_idx] = true;
    }
    listen_users[listen_idx]++;

	*srv = (struct srv_def){
		.idx = srv_idx,
		.port = port,
		.prog_num = prog_num,
		.prog_ver = prog_ver,
		.proc_num = proc_num,
		.func_ptr = func_ptr,
		.listen_idx = listen_idx,
		// Record owner of process
		.proc = proc,
	};

	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		if (proc->srvs[i] == NULL) {
			// found a free slot
			proc_srv_idx = i;
			proc->srvs[i] = srv;
			break;
		}
	}
	BUG_ON(i == LAUBERHORN_NUM_SERVICES);

	// Program into HW
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_enabled_wr(
		&dev->OncRpcCallDecoder_dev, 1);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_prog_num_wr(
		&dev->OncRpcCallDecoder_dev, prog_num);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_prog_ver_wr(
		&dev->OncRpcCallDecoder_dev, prog_ver);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_proc_wr(
		&dev->OncRpcCallDecoder_dev, proc_num);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_func_ptr_wr(
		&dev->OncRpcCallDecoder_dev, (u64)func_ptr);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_listen_port_wr(
		&dev->OncRpcCallDecoder_dev, port);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_pid_wr(
		&dev->OncRpcCallDecoder_dev, proc->tgid);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_idx_wr(
		&dev->OncRpcCallDecoder_dev, srv->idx);

	// Set UDP next proto for listen port to RPC
	lauberhorn_eci_UdpDecoder_ctrl_listen_port_wr(&dev->UdpDecoder_dev,
						      port);
	lauberhorn_eci_UdpDecoder_ctrl_listen_next_proto_wr(
		&dev->UdpDecoder_dev, lauberhorn_eci_listen_onc_rpc_call);
	lauberhorn_eci_UdpDecoder_ctrl_listen_idx_wr(&dev->UdpDecoder_dev,
						     listen_idx);

	srv->enabled = true;
	pr_info("Registered service #%d under TGID %d\n", srv->idx, proc->tgid);
	return proc_srv_idx;
}

static void deregister_service(struct worker_dev *dev, struct srv_def *srv)
{
	pid_t tgid;
	if (!srv->enabled) {
		pr_err("Service #%d not registered, bug?\n", srv->idx);
		return;
	}

	tgid = srv->proc->tgid;

	// Program into HW
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_enabled_wr(
		&dev->OncRpcCallDecoder_dev, 0);
	lauberhorn_eci_OncRpcCallDecoder_ctrl_service_idx_wr(
		&dev->OncRpcCallDecoder_dev, srv->idx);

	// Keep shared call listeners enabled until their final service leaves.
    if (--listen_users[srv->listen_idx] == 0) {
        lauberhorn_eci_UdpDecoder_ctrl_listen_next_proto_wr(
            &dev->UdpDecoder_dev, lauberhorn_eci_listen_disabled);
        lauberhorn_eci_UdpDecoder_ctrl_listen_idx_wr(
            &dev->UdpDecoder_dev, srv->listen_idx);
        listen_occupied[srv->listen_idx] = false;
    }
	srv->enabled = false;
	pr_info("Deregistered service #%d (was with TGID %d)\n", srv->idx,
		tgid);
}

static void update_proc_hw(struct worker_dev *dev, struct proc_def *proc)
{
	lauberhorn_eci_sched_ctrl_proc_enabled_wr(&dev->sched_dev,
						  proc->enabled && !proc->stopping && !atomic_read(&proc->stop_requested));
	lauberhorn_eci_sched_ctrl_proc_pid_wr(&dev->sched_dev, proc->tgid);
	lauberhorn_eci_sched_ctrl_proc_max_threads_wr(&dev->sched_dev,
						      proc->num_rdy_thrs);

	BUG_ON(proc->idx == 0);
	lauberhorn_eci_sched_ctrl_proc_idx_wr(&dev->sched_dev, proc->idx);
}

static struct proc_def *register_app(struct worker_dev *dev, pid_t tgid)
{
	int i;
	struct proc_def *proc;

	if (find_proc(tgid)) return ERR_PTR(-EALREADY);
	// procDb starts with 1 -- index 0 is the special IDLE process
	for (i = 1; i <= LAUBERHORN_NUM_PROCS; ++i) {
		if (!proc_defs[i].enabled) {
			proc = &proc_defs[i];
			proc->idx = i;
			break;
		} else if (proc_defs[i].tgid == tgid) {
			pr_err("Process %d already registered, bug?\n", tgid);
			return ERR_PTR(-EINVAL);
		}
	}

	if (i > LAUBERHORN_NUM_PROCS) {
		pr_err("No more free process slots in HW: %d already registered\n",
		       i);
		return ERR_PTR(-ENOMEM);
	}

	proc->tgid = tgid;
	proc->lifetime = ++next_process_lifetime;
	proc->stopping = proc->stopped = false;
	atomic_set(&proc->stop_requested, 0);
	memset(proc->parity_page, 0, PAGE_SIZE);

	// All threads start disabled
	for (i = 0; i < LAUBERHORN_NUM_WORKER_CORES; ++i) {
		proc->thr_defs[i].enabled = false;
	}
	proc->num_rdy_thrs = 0;

	// No services registered just yet
	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		proc->srvs[i] = NULL;
	}

	proc->enabled = true;
	lauberhorn_eci_sched_ctrl_watchdog_cycles_wr(&dev->sched_dev, 0);
	lauberhorn_eci_sched_ctrl_watchdog_idx_wr(&dev->sched_dev, proc->idx);
	update_proc_hw(dev, proc);

	pr_info("Registered app #%d with TGID %d\n", proc->idx, tgid);

	return proc;
}

/* control_lock serializes the lifecycle; IRQs only set stop_requested.
 * A disabled hardware process stays reserved in proc_defs until file release,
 * so stale VMAs and deferred scheduling work cannot alias a new application. */
void request_process_stop(struct proc_def *proc)
{
	atomic_cmpxchg(&proc->stop_requested, 0, 2);
	mod_delayed_work(system_wq, &recovery_work, 0);
}

static unsigned long process_draining(struct file_priv *fp)
{
	lauberhorn_eci_sched_stat_readback_idx_wr(&fp->dev->sched_dev, fp->pd->idx);
	return lauberhorn_eci_sched_stat_readback_draining_rd(&fp->dev->sched_dev);
}

static int stop_app(struct file_priv *fp, bool kill)
{
	struct proc_def *proc = fp->pd;
	lauberhorn_eci_OncRpcReplyDecoder_t replies;
	unsigned long busy;
	int i, ret;
	int reason = atomic_read(&proc->stop_requested) ?: (kill ? 1 : 0);
	if (proc->stopped) return 0;
	if (!proc->stopping) {
		proc->stopping = true;
		trace_process_recovery(proc->lifetime, proc->tgid, proc->idx, 0, reason);
		update_proc_hw(fp->dev, proc);
		for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i)
			if (proc->srvs[i] && proc->srvs[i]->enabled)
				deregister_service(fp->dev, proc->srvs[i]);
		pr_info("Stopping TGID %d reason=%s\n", proc->tgid, kill ? "kill" : "close");
	}
	/* First drain every physical worker, including committed outgoing TX.
	 * Table retirement then prevents further late completion admission. */
	ret = read_poll_timeout(process_draining, busy, !busy, 10, 1000000, false, fp);
	if (ret) return ret;
	flush_worker_schedules();
	trace_process_recovery(proc->lifetime, proc->tgid, proc->idx, 1, reason);
	lauberhorn_eci_OncRpcReplyDecoder_initialize(&replies,
		LAUBERHORN_ECI__ONC_RPC_REPLY_DECODER_BASE);
	ret = read_poll_timeout(lauberhorn_eci_OncRpcReplyDecoder_stat_retire_busy_rd,
		busy, !busy, 10, 1000000, false, &replies);
	if (ret) return ret;
	lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_pid_wr(&replies, proc->tgid);
	lauberhorn_eci_OncRpcReplyDecoder_ctrl_retire_wr(&replies, 2);
	ret = read_poll_timeout(lauberhorn_eci_OncRpcReplyDecoder_stat_retire_busy_rd,
		busy, !busy, 10, 1000000, false, &replies);
	if (ret) return ret;
	/* A completion can have left the lookup before the retirement command. */
	ret = read_poll_timeout(process_draining, busy, !busy, 10, 1000000, false, fp);
	if (ret) return ret;
	lauberhorn_eci_OncRpcReplyEncoder_ctrl_context_reclaim_pid_wr(
		&fp->dev->OncRpcReplyEncoder_dev, proc->tgid);
	for (i = 0; i < LAUBERHORN_NUM_LISTEN_PORTS; ++i)
		if (fp->endpoints[i]) release_endpoint(fp, i);
	for (i = 0; i < LAUBERHORN_NUM_WORKER_CORES; ++i) {
		struct thr_def *thr = &proc->thr_defs[i];
		if (!thr->enabled) continue;
		if (kill) send_sig(SIGKILL, thr->task, 1);
		clean_worker_thread(thr);
	}
	proc->stopped = true;
	trace_process_recovery(proc->lifetime, proc->tgid, proc->idx, 2, reason);
	pr_info("Retired hardware ownership for TGID %d\n", proc->tgid);
	return 0;
}

static void recovery_monitor(struct work_struct *work)
{
	int i, j;
	bool active = false;
	mutex_lock(&control_lock);
	for (i = 1; i <= LAUBERHORN_NUM_PROCS; ++i) {
		struct file_priv *fp = app_files[i];
		if (!fp || fp->pd->stopped) continue;
		active = true;
		for (j = 0; j < LAUBERHORN_NUM_WORKER_CORES; ++j) {
			struct thr_def *thr = &fp->pd->thr_defs[j];
			if (thr->task && fatal_signal_pending(thr->task))
				atomic_cmpxchg(&fp->pd->stop_requested, 0, 3);
		}
		if (atomic_read(&fp->pd->stop_requested) && stop_app(fp, true))
			pr_err_ratelimited("TGID %d recovery still waiting for hardware drain\n", fp->pd->tgid);
	}
	mutex_unlock(&control_lock);
	if (active) schedule_delayed_work(&recovery_work, msecs_to_jiffies(10));
}

static long app_dev_ioctl_locked(struct file *f, unsigned int cmd, unsigned long arg)
{
	struct file_priv *fp = f->private_data;

	lauberhorn_reg_srv_t reg_cmd;
	lauberhorn_reg_srv_t __user *reg_cmd_usr = (void __user *)arg;
	lauberhorn_srv_id_t reg_ret;
	int proc_srv_idx;

	lauberhorn_dereg_srv_t dereg_cmd;
	lauberhorn_dereg_srv_t __user *dereg_cmd_usr = (void __user *)arg;
	struct srv_def *srv;
	void *dereg_ret;

	struct proc_def *proc = fp->pd;
	if (current->tgid != proc->tgid) return -EPERM;
	if (cmd == LAUBERHORN_IOCTL_STOP_PROCESS || cmd == LAUBERHORN_IOCTL_WAKE_ALL_WORKERS)
		return stop_app(fp, true);
	if (proc->stopping) return -ESHUTDOWN;

	switch (cmd) {
	case LAUBERHORN_IOCTL_KICK_PROCESS:
		if (!proc->num_rdy_thrs) return -EAGAIN;
		lauberhorn_eci_sched_ctrl_wake_idx_wr(&fp->dev->sched_dev, proc->idx);
		return 0;
	case LAUBERHORN_IOCTL_WATCHDOG: {
		lauberhorn_watchdog_t cfg;
		u64 cycles;
		if (proc->num_rdy_thrs) return -EBUSY;
		if (copy_from_user(&cfg, (void __user *)arg, sizeof(cfg))) return -EFAULT;
		if (cfg.reserved) return -EINVAL;
		cycles = DIV_ROUND_UP_ULL((u64)cfg.limit_us * LAUBERHORN_CLOCK_FREQ, 1000000);
		if (cycles >= (1ULL << 31)) return -ERANGE;
		lauberhorn_eci_sched_ctrl_watchdog_cycles_wr(&fp->dev->sched_dev, cycles);
		lauberhorn_eci_sched_ctrl_watchdog_idx_wr(&fp->dev->sched_dev, proc->idx);
		return 0;
	}
	case LAUBERHORN_IOCTL_RETIRE_CALL:
		return retire_call(fp, (void __user *)arg);
	case LAUBERHORN_IOCTL_REG_ENDPOINT:
		return register_endpoint(fp, (void __user *)arg);
	case LAUBERHORN_IOCTL_DEREG_ENDPOINT:
		return deregister_endpoint(fp, (void __user *)arg);
	case LAUBERHORN_IOCTL_REG_SRV:
		if (copy_from_user(&reg_cmd, reg_cmd_usr, sizeof(reg_cmd))) {
			return -EFAULT;
		}
		proc_srv_idx = register_service(fp->dev, reg_cmd.port,
						reg_cmd.prog_num,
						reg_cmd.prog_ver,
						reg_cmd.proc_num,
						reg_cmd.func_ptr, proc);
		if (proc_srv_idx < 0) {
			return proc_srv_idx;
		}
		reg_ret = proc_srv_idx;

		if (copy_to_user(&reg_cmd_usr->id, &reg_ret, sizeof(reg_ret))) {
			deregister_service(fp->dev, proc->srvs[proc_srv_idx]);
			proc->srvs[proc_srv_idx] = NULL;
			return -EFAULT;
		}
		break;

	case LAUBERHORN_IOCTL_DEREG_SRV:
		if (copy_from_user(&dereg_cmd, dereg_cmd_usr,
				   sizeof(dereg_cmd))) {
			return -EFAULT;
		}
		if (dereg_cmd.id >= LAUBERHORN_NUM_SERVICES ||
		    !proc->srvs[dereg_cmd.id] ||
		    !proc->srvs[dereg_cmd.id]->enabled) {
			return -EINVAL;
		}
		srv = proc->srvs[dereg_cmd.id];
		dereg_ret = srv->func_ptr;

		if (copy_to_user(&dereg_cmd_usr->func_ptr, &dereg_ret,
				 sizeof(dereg_ret))) {
			return -EFAULT;
		}

		deregister_service(fp->dev, srv);
		proc->srvs[dereg_cmd.id] = NULL;

		break;


	default:
		pr_err("Unknown ioctl command %u\n", cmd);
		return -EINVAL;
	}
	return 0;
}

static long app_dev_ioctl(struct file *f, unsigned int cmd, unsigned long arg)
{
    long ret;
    mutex_lock(&control_lock);
    ret = app_dev_ioctl_locked(f, cmd, arg);
    mutex_unlock(&control_lock);
    return ret;
}

static int app_dev_open(struct inode *i, struct file *f)
{
	pid_t tgid = current->tgid;
	struct file_priv *fp;
	int err;

	if (tgid <= 0 || tgid >= (1UL << LAUBERHORN_PID_WIDTH)) return -EOVERFLOW;
	fp = kzalloc(sizeof(struct file_priv), GFP_KERNEL);
	if (!fp) return -ENOMEM;
	fp->dev = container_of(i->i_cdev, struct worker_dev, cdev);

	pr_info("Registering application TGID %d\n", tgid);
	mutex_lock(&control_lock);
	fp->pd = register_app(fp->dev, tgid);
	if (!IS_ERR(fp->pd)) app_files[fp->pd->idx] = fp;
	mutex_unlock(&control_lock);
	if (IS_ERR(fp->pd)) {
		err = PTR_ERR(fp->pd);
		pr_err("Failed to register app, err %d\n", err);
		goto free_fp;
	}

	f->private_data = fp;
	mod_delayed_work(system_wq, &recovery_work, msecs_to_jiffies(10));
	return 0;

free_fp:
	kfree(fp);
	return err;
}

static int app_dev_release(struct inode *i, struct file *f)
{
	struct file_priv *fp = f->private_data;
	pid_t tgid = fp->pd->tgid;
	BUG_ON(!fp->pd->enabled);

	pr_info("Deregistering application TGID %d\n", tgid);
	mutex_lock(&control_lock);
	/* Never release a slot or mapping while a transaction still owns it.
	 * Failure to drain is a hardware bug: retain ownership and keep reporting
	 * it, rather than pretending teardown succeeded or requiring reset. */
	while (stop_app(fp, false))
		pr_err_ratelimited("TGID %d close waiting for hardware drain\n", tgid);
	app_files[fp->pd->idx] = NULL;
	fp->pd->enabled = false;
	mutex_unlock(&control_lock);

	kfree(fp);

	return 0;
}

static void vma_close(struct vm_area_struct *vma)
{
	struct vma_priv_data *priv = vma->vm_private_data;
	if (!priv->is_parity_page) {
		mutex_lock(&control_lock);
		/* Unmapping any worker ends the application's hardware lifetime. */
		while (stop_app(app_files[priv->thr->parent->idx], true))
			pr_err_ratelimited("TGID %d unmap waiting for hardware drain\n",
				priv->thr->parent->tgid);

		// Decrement the parallelism count
		--priv->thr->parent->num_rdy_thrs;
		update_proc_hw(priv->dev, priv->thr->parent);

		// Decrement the thread task ref count
		put_task_struct(priv->thr->task);
		priv->thr->task = NULL;
		mutex_unlock(&control_lock);
	}
}

static const char *vma_name(struct vm_area_struct *vma)
{
	struct vma_priv_data *priv = vma->vm_private_data;
	if (priv->is_parity_page) {
		return "Lauberhorn parity page";
	} else {
		return priv->vma_name;
	}
}

static const struct vm_operations_struct vm_ops = {
	.close = vma_close,
	.name = vma_name,
};

static int app_dev_mmap_locked(struct file *f, struct vm_area_struct *vma)
{
	pid_t tid = current->pid;
	pid_t tgid = current->tgid;
	int thr_idx;
	struct proc_def *proc;
	struct thr_def *thr;
	int dp_num_pages = LAUBERHORN_ECI_CORE_OFFSET / PAGE_SIZE;
	struct file_priv *fp = f->private_data;

	u64 pfn;
	u32 err;

	u64 size = vma->vm_end - vma->vm_start;
	u64 pgoff = vma->vm_pgoff;

	vma->vm_ops = &vm_ops;
	vm_flags_set(vma, VM_DONTEXPAND);
	vm_flags_set(vma, VM_DONTDUMP);
	vm_flags_set(vma, VM_DONTCOPY);
	vm_flags_set(vma, VM_PFNMAP);

	vma->vm_page_prot = pgprot_nx(vma->vm_page_prot);

	proc = fp->pd;
	if (proc->tgid != tgid) return -EPERM;
	if (proc->stopping) return -ESHUTDOWN;
	if (!proc) {
		pr_err("Failed to find TGID %d for thread init, bug?\n", tgid);
		return -EINVAL;
	}

	if (pgoff == 0 && size == PAGE_SIZE) {
		// Mapping for the parity page
		pfn = virt_to_phys(proc->parity_page) >> PAGE_SHIFT;

		pr_info("Mapping parity page into application TGID %d\n", tgid);

		proc->vma_data_parity_page.is_parity_page = true;
		proc->vma_data_parity_page.proc = proc;
		proc->vma_data_parity_page.dev = fp->dev;
		vma->vm_private_data = &proc->vma_data_parity_page;

		return remap_pfn_range(vma, vma->vm_start, pfn, PAGE_SIZE,
				       vma->vm_page_prot);
	} else if (pgoff == 0 && size != PAGE_SIZE) {
		pr_err("Offset 0 is the parity page, attempted to map %lld bytes\n",
		       size);
		return -EINVAL;
	} else if (size != LAUBERHORN_ECI_CORE_OFFSET ||
		   pgoff % dp_num_pages != 1) {
		pr_err("Non-zero offsets are the pages for the per-thread datapaths\n");
		return -EINVAL;
	} else {
		thr_idx = pgoff / dp_num_pages;
		if (thr_idx >= LAUBERHORN_NUM_WORKER_CORES) {
			pr_err("Thread has datapath offset %d that is more than the %d supported worker cores\n",
			       thr_idx, LAUBERHORN_NUM_WORKER_CORES);
			return -EINVAL;
		} else if (proc->thr_defs[thr_idx].enabled) {
			pr_err("Thread datapath offset %d is already enabled\n",
			       thr_idx);
			return -EINVAL;
		}

		thr = &proc->thr_defs[thr_idx];
		thr->idx = thr_idx;
	}

	pr_info("Setting up thread PID %d (part of application TGID %d) as RPC worker\n",
		tid, tgid);
	thr->prefix = 1 + proc->idx * LAUBERHORN_NUM_WORKER_CORES + thr_idx;
	thr->dp_phys_base =
		mem_node1_off_to_phys(thr->prefix * LAUBERHORN_ECI_CORE_OFFSET);

	thr->parent = proc;
	thr->worker_idx = -1;

	// Store a reference to the current thread task
	get_task_struct(current);
	thr->task = current;

	// Map the FPGA memory backing this thread into userspace.
	pfn = thr->dp_phys_base >> PAGE_SHIFT;
	err = remap_pfn_range(vma, vma->vm_start, pfn,
			      LAUBERHORN_ECI_CORE_OFFSET, vma->vm_page_prot);
	if (err != 0) {
		pr_info("Failed to map datapath address into thread\n");
		put_task_struct(thr->task);
		thr->task = NULL;
		return err;
	}
	thr->vma_data_datapath.is_parity_page = false;
	thr->vma_data_datapath.thr = thr;
	thr->vma_data_datapath.dev = fp->dev;
	snprintf(thr->vma_data_datapath.vma_name, THR_DATAPATH_VMA_NAME_SIZE,
		 "Lauberhorn thread#%d datapath page", thr_idx);
	vma->vm_private_data = &thr->vma_data_datapath;

	/* Publish hardware parallelism only after the thread can be claimed by an
	 * IRQ. A queued request can activate it immediately after this update. */
	prepare_worker_thread(thr);
	++proc->num_rdy_thrs;
	update_proc_hw(fp->dev, proc);

	return 0;
}

static int app_dev_mmap(struct file *f, struct vm_area_struct *vma)
{
    int ret;
    mutex_lock(&control_lock);
    ret = app_dev_mmap_locked(f, vma);
    mutex_unlock(&control_lock);
    return ret;
}

static const struct file_operations fops = {
	.owner = THIS_MODULE,
	.open = app_dev_open,
	.release = app_dev_release,
	.unlocked_ioctl = app_dev_ioctl,
	.mmap = app_dev_mmap,
};

static int app_dev_uevent(const struct device *dev, struct kobj_uevent_env *env)
{
	add_uevent_var(env, "DEVMODE=%#o", 0666);
	return 0;
}

/**
 * Create character device for control-path functions towards userspace.
 *  - /dev/lauberhorn: accessible to normal user, app access
 */
int create_devices(void)
{
	struct worker_dev *dev;

	if (alloc_chrdev_region(&devt, 0, 1, "lauberhorn") < 0) {
		pr_err("alloc_chrdev_region failed\n");
		return -1;
	}
	pr_info("chrdev major = %d, minor = %d \n", MAJOR(devt), MINOR(devt));

	// allocate our device
	dev = kmalloc(sizeof(struct worker_dev), GFP_KERNEL);
	cdev_init(&dev->cdev, &fops);
	if (cdev_add(&dev->cdev, devt, 1) < 0) {
		pr_err("cdev_add failed\n");
		return -1;
	}
	dev->cdev.owner = THIS_MODULE;

	if (IS_ERR(dev_class = class_create("lauberhorn_class"))) {
		pr_err("class_create failed\n");
		return -1;
	}
	dev_class->dev_uevent = app_dev_uevent;

	// allocate our private data
	if (IS_ERR(device_create_with_groups(dev_class, NULL, devt, dev,
					     worker_attr_groups,
					     "lauberhorn"))) {
		pr_err("device_create failed\n");
		return -1;
	}

	// Initialize Mackerel devices
	lauberhorn_eci_OncRpcCallDecoder_initialize(
		&dev->OncRpcCallDecoder_dev,
		LAUBERHORN_ECI__ONC_RPC_CALL_DECODER_BASE);
	lauberhorn_eci_UdpDecoder_initialize(&dev->UdpDecoder_dev,
					     LAUBERHORN_ECI__UDP_DECODER_BASE);
	lauberhorn_eci_OncRpcReplyEncoder_initialize(
		&dev->OncRpcReplyEncoder_dev,
		LAUBERHORN_ECI__ONC_RPC_REPLY_ENCODER_BASE);
	lauberhorn_eci_sched_initialize(&dev->sched_dev,
					LAUBERHORN_ECI_SCHED_BASE);

	pr_info("Device created at /dev/lauberhorn\n");
	return 0;
}

void remove_devices(void)
{
	cancel_delayed_work_sync(&recovery_work);
	// free our private data
	struct worker_dev *dev =
		dev_get_drvdata(class_find_device_by_devt(dev_class, devt));

	device_destroy(dev_class, devt);
	class_destroy(dev_class);
	cdev_del(&dev->cdev);
	kfree(dev);

	unregister_chrdev_region(devt, 1);
	pr_info("Device removed\n");
}
