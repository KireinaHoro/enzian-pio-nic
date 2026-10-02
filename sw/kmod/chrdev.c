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
#include "eci_address_map.h"

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
static void lifetime_monitor(struct work_struct *work);
static DECLARE_DELAYED_WORK(lifetime_work, lifetime_monitor);
struct file_priv {
	struct proc_def *pd;
	struct worker_dev *dev;
	struct endpoint_reservation *endpoints[LAUBERHORN_NUM_LISTEN_PORTS];
};

static struct file_priv *app_files[LAUBERHORN_NUM_PROCS + 1];

#include "stats/worker.h"
#include "endpoints.h"
#include <linux/iopoll.h>
#include "retire_call.h"

struct proc_def *find_proc(pid_t tgid)
{
	int i;
	for (i = 1; i <= LAUBERHORN_NUM_PROCS; ++i) {
		if (smp_load_acquire(&proc_defs[i].enabled) && READ_ONCE(proc_defs[i].tgid) == tgid) {
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
						  proc->enabled && !proc->failed && proc->declared_workers &&
                          proc->num_rdy_thrs == proc->declared_workers);
	lauberhorn_eci_sched_ctrl_proc_pid_wr(&dev->sched_dev, proc->tgid);
	lauberhorn_eci_sched_ctrl_proc_max_threads_wr(&dev->sched_dev,
						      proc->num_rdy_thrs == proc->declared_workers ? proc->declared_workers : 0);

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
	proc->domain_code = allocate_domain_code(proc->idx);
    if (proc->domain_code == U32_MAX) return ERR_PTR(-ENOSPC);
    proc->lifetime = (u64)proc->domain_code + 1;
    proc->leader = current->group_leader;
    get_task_struct(proc->leader);
    init_waitqueue_head(&proc->claims);
    // Unresolved domain records cannot disappear on module unload.
    __module_get(THIS_MODULE);

	// All threads start disabled
	for (i = 0; i < LAUBERHORN_NUM_WORKER_CORES; ++i) {
		proc->thr_defs[i].enabled = false;
	}
	proc->num_rdy_thrs = 0;

	// No services registered just yet
	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		proc->srvs[i] = NULL;
	}

	smp_store_release(&proc->enabled, true);
	update_proc_hw(dev, proc);

	pr_info("Registered app #%d with TGID %d\n", proc->idx, tgid);

	return proc;
}

struct proc_def *find_proc_slot(u32 slot)
{
    if (!slot || slot > LAUBERHORN_NUM_PROCS || !smp_load_acquire(&proc_defs[slot].enabled)) return NULL;
    return &proc_defs[slot];
}

/* Exit detection fences lifetimes; it performs no retirement, reclamation or
 * normal scheduling. Records and task references remain until coherence reset. */
static void lifetime_monitor(struct work_struct *work)
{
    int i, j;
    bool live = false;
    for (i = 1; i <= LAUBERHORN_NUM_PROCS; ++i) {
        struct proc_def *proc = find_proc_slot(i);
        if (!proc || READ_ONCE(proc->failed)) continue;
        live = true;
        if (READ_ONCE(proc->leader->exit_state) || fatal_signal_pending(proc->leader)) {
            fail_domain(proc);
            continue;
        }
        for (j = 0; j < proc->declared_workers; ++j) {
            struct thr_def *thr = &proc->thr_defs[j];
            if (smp_load_acquire(&thr->enabled) &&
                (READ_ONCE(thr->task->exit_state) || fatal_signal_pending(thr->task))) {
                fail_domain(proc);
                break;
            }
        }
    }
    if (live) schedule_delayed_work(&lifetime_work, msecs_to_jiffies(10));
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
	{ fail_domain(proc); return -ESHUTDOWN; }
	if (proc->failed) return -ESHUTDOWN;

	switch (cmd) {
    case LAUBERHORN_IOCTL_SET_WORKER_COUNT: {
        u32 count;
        if (copy_from_user(&count, (void __user *)arg, sizeof(count))) return -EFAULT;
        if (!count || count > LAUBERHORN_NUM_WORKER_CORES) return -EINVAL;
        if (proc->declared_workers || proc->num_rdy_thrs) return -EBUSY;
        proc->declared_workers = count;
        return 0;
    }
    case LAUBERHORN_IOCTL_REGISTER_THREAD: {
        u32 index;
        int ret;
        if (copy_from_user(&index, (void __user *)arg, sizeof(index))) return -EFAULT;
        ret = register_worker_thread(proc, index);
        if (!ret && proc->num_rdy_thrs == proc->declared_workers) update_proc_hw(fp->dev, proc);
        return ret;
    }
    case LAUBERHORN_IOCTL_KICK_PROCESS:
    case LAUBERHORN_IOCTL_WATCHDOG:
        return -EOPNOTSUPP;
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
    struct file_priv *fp = f->private_data;
    if (current->tgid != fp->pd->tgid) return -EPERM;
    // Yield must never hold the control/lifetime mutex across blocking MMIO.
    if (cmd == LAUBERHORN_IOCTL_YIELD)
        return worker_yield(fp->pd, (struct lh_activation __user *)arg);
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
	mod_delayed_work(system_wq, &lifetime_work, msecs_to_jiffies(10));
	return 0;

free_fp:
	kfree(fp);
	return err;
}

static int app_dev_release(struct inode *i, struct file *f)
{
    struct file_priv *fp = f->private_data;
    fail_domain(fp->pd);
    // Retain endpoint reservations, task references, slot and lifetime.
    return 0;
}

static void vma_close(struct vm_area_struct *vma)
{
    fail_domain(vma->vm_private_data);
}
static const char *vma_name(struct vm_area_struct *vma)
{
    return "Lauberhorn domain worker aliases";
}
static const struct vm_operations_struct vm_ops = {
    .close = vma_close,
    .name = vma_name,
};

static int app_dev_mmap_locked(struct file *f, struct vm_area_struct *vma)
{
    struct file_priv *fp = f->private_data;
    struct proc_def *proc = fp->pd;
    unsigned long size = vma->vm_end - vma->vm_start;
    unsigned long offset = vma->vm_pgoff << PAGE_SHIFT;
    unsigned long first = offset / LH_MAP_BLOCK_BYTES, count = size / LH_MAP_BLOCK_BYTES;
    unsigned int w;
    int ret;
    u64 address;
    if (current->tgid != proc->tgid) return -EPERM;
    if (proc->failed) return -ESHUTDOWN;
    if (PAGE_SIZE > LH_MAP_BLOCK_BYTES || !(vma->vm_flags & VM_SHARED) ||
        vma->vm_pgoff > (ULONG_MAX >> PAGE_SHIFT) ||
        !size || size % LH_MAP_BLOCK_BYTES || offset % LH_MAP_BLOCK_BYTES ||
        first >= LAUBERHORN_NUM_WORKER_CORES || count > LAUBERHORN_NUM_WORKER_CORES - first)
        return -EINVAL;
    vm_flags_set(vma, VM_DONTEXPAND | VM_DONTDUMP | VM_DONTCOPY | VM_PFNMAP);
    vm_flags_clear(vma, VM_MAYEXEC | VM_EXEC);
    vma->vm_page_prot = pgprot_nx(vma->vm_page_prot);
    for (w = 0; w < count; ++w) {
        if (lh_map_encode(LAUBERHORN_NUM_WORKER_CORES, first + w, proc->domain_code, 0, &address)) return -EINVAL;
        ret = remap_pfn_range(vma, vma->vm_start + w * LH_MAP_BLOCK_BYTES,
            mem_node1_off_to_phys(address) >> PAGE_SHIFT, LH_MAP_BLOCK_BYTES, vma->vm_page_prot);
        if (ret) return ret;
    }
    vma->vm_ops = &vm_ops;
    vma->vm_private_data = proc;
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
	if (!dev) {
        unregister_chrdev_region(devt, 1);
        return -ENOMEM;
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

	cdev_init(&dev->cdev, &fops);
    dev->cdev.owner = THIS_MODULE;
	if (cdev_add(&dev->cdev, devt, 1) < 0) {
		pr_err("cdev_add failed\n");
        kfree(dev);
        unregister_chrdev_region(devt, 1);
		return -1;
	}
	dev->cdev.owner = THIS_MODULE;

	if (IS_ERR(dev_class = class_create("lauberhorn_class"))) {
		pr_err("class_create failed\n");
        cdev_del(&dev->cdev);
        kfree(dev);
        unregister_chrdev_region(devt, 1);
		return -1;
	}
	dev_class->dev_uevent = app_dev_uevent;

	// allocate our private data
	if (IS_ERR(device_create_with_groups(dev_class, NULL, devt, dev,
					     worker_attr_groups,
					     "lauberhorn"))) {
		pr_err("device_create failed\n");
        class_destroy(dev_class);
        cdev_del(&dev->cdev);
        kfree(dev);
        unregister_chrdev_region(devt, 1);
		return -1;
	}


	pr_info("Device created at /dev/lauberhorn\n");
	return 0;
}

void remove_devices(void)
{
	cancel_delayed_work_sync(&lifetime_work);
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
