// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu, Zikai Liu

#include "common.h"
#include "ioctl.h"

#include "lauberhorn_eci_sched_dev.h"

static dev_t dev = 0;
static struct cdev cdev;
static struct class *dev_class;

static struct srv_def srv_defs[LAUBERHORN_NUM_SERVICES];
static struct proc_def proc_defs[LAUBERHORN_NUM_PROCS];

struct proc_def *find_proc(pid_t tgid)
{
	int i;
	for (i = 0; i < LAUBERHORN_NUM_PROCS; ++i) {
		if (proc_defs[i].enabled && proc_defs[i].tgid == tgid) {
			return &proc_defs[i];
		}
	}
	return NULL;
}

static int register_service(u16 port, u32 prog_num, u32 prog_ver, u32 proc_num,
			    void __user *func_ptr, pid_t tgid)
{
	int i;
	struct proc_def *proc;
	struct srv_def *srv;

	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		if (!srv_defs[i].enabled) {
			srv = &srv_defs[i];
			srv->idx = i;
			break;
		} else if (srv_defs[i].port == port &&
			   srv_defs[i].prog_num == prog_num &&
			   srv_defs[i].prog_ver == prog_ver &&
			   srv_defs[i].proc_num == proc_num) {
			pr_err("Service prog=%d ver=%d proc=%d on UDP port %d already registered as #%d!\n",
			       prog_num, prog_ver, proc_num, port, i);
			return -1;
		}
	}

	if (i == LAUBERHORN_NUM_SERVICES) {
		pr_err("No more free service slots in HW: %d already registered\n",
		       i);
		return -1;
	}

	proc = find_proc(tgid);
	if (!proc) {
		pr_err("Failed to find TGID %d for service, bug?\n", tgid);
		return -1;
	}

	srv->port = port;
	srv->prog_num = prog_num;
	srv->prog_ver = prog_ver;
	srv->proc_num = proc_num;
	srv->func_ptr = func_ptr;
	srv->proc = proc;

	// TODO: program into HW

	srv->enabled = true;
	pr_info("Registered service #%d under TGID %d\n", srv->idx, tgid);
	return srv->idx;
}

static void deregister_service(u32 idx)
{
	pid_t tgid;
	if (!srv_defs[idx].enabled) {
		pr_err("Service #%d not registered, bug?\n", idx);
		return;
	}

	tgid = srv_defs[idx].proc->tgid;

	// TODO: program into HW

	srv_defs[idx].enabled = false;
	pr_info("Deregistered service #%d (was with TGID %d)\n", idx, tgid);
}

static struct proc_def *register_app(pid_t tgid)
{
	int i;
	struct proc_def *proc;

	for (i = 0; i < LAUBERHORN_NUM_PROCS; ++i) {
		if (!proc_defs[i].enabled) {
			proc = &proc_defs[i];
			proc->idx = i;
			break;
		} else if (proc_defs[i].tgid == tgid) {
			pr_err("Process %d already registered, bug?\n", tgid);
			return ERR_PTR(-EINVAL);
		}
	}

	if (i == LAUBERHORN_NUM_PROCS) {
		pr_err("No more free process slots in HW: %d already registered\n",
		       i);
		return ERR_PTR(-ENOMEM);
	}

	proc->tgid = tgid;

	// All threads start disabled
	for (i = 0; i < LAUBERHORN_NUM_THREADS; ++i) {
		proc->thr_defs[i].enabled = false;
	}

	// TODO: program into HW

	proc->enabled = true;
	pr_info("Registered app #%d with TGID %d\n", proc->idx, tgid);

	return proc;
}

static void deregister_app(pid_t tgid)
{
	int i;
	struct proc_def *proc = find_proc(tgid);
	if (!proc) {
		pr_err("Process %d not registered, bug?\n", tgid);
		return;
	}

	// Stop all threads under this app
	for (i = 0; i < LAUBERHORN_NUM_WORKER_CORES; ++i) {
		if (proc->thr_defs[i].enabled) {
			clean_worker_thread(&proc->thr_defs[i]);
		}
	}

	// Deregister all services under this app
	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		if (srv_defs[i].proc == proc) {
			deregister_service(i);
		}
	}

	// TODO: program into HW

	proc->enabled = false;
	pr_info("Deregistered app #%d with TGID %d\n", proc->idx, proc->tgid);
}

static long app_dev_ioctl(struct file *file, unsigned int cmd,
			  unsigned long arg)
{
	lauberhorn_reg_srv_t reg_cmd;
	lauberhorn_reg_srv_t __user *reg_cmd_usr = (void __user *)arg;
	lauberhorn_srv_id_t reg_ret;
	int srv_idx;

	lauberhorn_srv_id_t dereg_cmd;

	pid_t tgid = current->pid;
	switch (cmd) {
	case LAUBERHORN_IOCTL_REG_SRV:
		if (copy_from_user(&reg_cmd, reg_cmd_usr, sizeof(reg_cmd))) {
			return -EFAULT;
		}
		srv_idx = register_service(reg_cmd.port, reg_cmd.prog_num,
					   reg_cmd.prog_ver, reg_cmd.proc_num,
					   reg_cmd.func_ptr, tgid);
		if (srv_idx < 0) {
			return -EINVAL;
		}
		reg_ret = srv_idx;

		if (copy_to_user(&reg_cmd_usr->id, &reg_ret, sizeof(reg_ret))) {
			return -EFAULT;
		}
		break;

	case LAUBERHORN_IOCTL_DEREG_SRV:
		if (copy_from_user(&dereg_cmd, (void __user *)arg,
				   sizeof(dereg_cmd))) {
			return -EFAULT;
		}
		deregister_service(dereg_cmd);
		break;

	default:
		pr_err("Unknown ioctl command %u\n", cmd);
		return -EINVAL;
	}
	return 0;
}

static int app_dev_open(struct inode *i, struct file *f)
{
	pid_t tgid = current->pid;
	struct proc_def *pd;
	int err;

	pr_info("Registering application TGID %d\n", tgid);
	pd = register_app(tgid);

	if (IS_ERR(pd)) {
		err = PTR_ERR(pd);
		pr_err("Failed to register app, err %d\n", err);
		return err;
	}

	// release might not be called in the same process
	f->private_data = pd;

	return 0;
}

static int app_dev_release(struct inode *i, struct file *f)
{
	struct proc_def *pd = (struct proc_def *)f->private_data;
	pid_t tgid = pd->tgid;
	BUG_ON(!pd->enabled);

	pr_info("Deregistering application TGID %d\n", tgid);
	deregister_app(tgid);

	return 0;
}

static void vma_close(struct vm_area_struct *vma)
{
	struct vma_priv_data *priv = vma->vm_private_data;
	if (!priv->is_parity_page) {
		// A worker thread unmapped its datapath VMA, disable the thread
		clean_worker_thread(priv->thr);
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

static int app_dev_mmap(struct file *f, struct vm_area_struct *vma)
{
	pid_t tid = current->pid;
	pid_t tgid = current->tgid;
	int thr_idx;
	struct proc_def *proc;
	struct thr_def *thr;

	u64 worker_phys_base, pfn;
	u32 err;

	u64 size = vma->vm_end - vma->vm_start;
	u64 pgoff = vma->vm_pgoff;

	vma->vm_ops = &vm_ops;
	vm_flags_set(vma, VM_DONTEXPAND);
	vm_flags_set(vma, VM_DONTDUMP);
	vm_flags_set(vma, VM_DONTCOPY);
	vm_flags_set(vma, VM_PFNMAP);

	vma->vm_page_prot = pgprot_nx(vma->vm_page_prot);

	proc = find_proc(tgid);
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
		vma->vm_private_data = &proc->vma_data_parity_page;

		return remap_pfn_range(vma, vma->vm_start, pfn, PAGE_SIZE,
				       vma->vm_page_prot);
	} else if (pgoff == 0 && size != PAGE_SIZE) {
		pr_err("Offset 0 is the parity page, attempted to map %lld bytes\n",
		       size);
		return -EINVAL;
	} else if (size != LAUBERHORN_ECI_CORE_OFFSET ||
		   pgoff % LAUBERHORN_ECI_CORE_OFFSET != PAGE_SIZE) {
		pr_err("Non-zero offsets are the pages for the per-thread datapaths\n");
		return -EINVAL;
	} else {
		thr_idx = (pgoff - PAGE_SIZE) / LAUBERHORN_ECI_CORE_OFFSET;
		if (thr_idx >= LAUBERHORN_NUM_WORKER_CORES) {
			pr_err("Thread has datapath offset %d that is more than the %d supported worker cores\n",
			       thr_idx, LAUBERHORN_NUM_WORKER_CORES);
			return -EINVAL;
		} else if (proc->thr_defs[thr_idx].enabled) {
			pr_err("Thread datapath offset %d is already enabled\n",
			       thr_idx);
			return -EINVAL;
		}
	}
	thr = &proc->thr_defs[thr_idx];

	pr_info("Setting up thread PID %d (part of application TGID %d) as RPC worker\n",
		tid, tgid);
	thr->prefix = 1 + proc->idx * LAUBERHORN_NUM_WORKER_CORES + thr_idx;
	worker_phys_base =
		thr->prefix * LAUBERHORN_ECI_CORE_OFFSET + FPGA_MEM_BASE;

	// Map base into userspace
	pfn = virt_to_phys((void *)worker_phys_base) >> PAGE_SHIFT;
	err = remap_pfn_range(vma, vma->vm_start, pfn,
			      LAUBERHORN_ECI_CORE_OFFSET, vma->vm_page_prot);
	if (err != 0) {
		pr_info("Failed to map datapath address into thread\n");
		return err;
	}
	thr->vma_data_datapath.is_parity_page = false;
	thr->vma_data_datapath.thr = thr;
	snprintf(thr->vma_data_datapath.vma_name, THR_DATAPATH_VMA_NAME_SIZE,
		 "Lauberhorn thread#%d datapath page", thr_idx);
	vma->vm_private_data = &thr->vma_data_datapath;

	// Thread will be blocked until HW wakes it up
	prepare_worker_thread(thr);

	return 0;
}

static const struct file_operations fops = {
	.owner = THIS_MODULE,
	.open = app_dev_open,
	.release = app_dev_release,
	.unlocked_ioctl = app_dev_ioctl,
	.mmap = app_dev_mmap,
};

/**
 * Create character devices for control-path functions towards userspace.
 * Two devices will be created:
 *  - /dev/lauberhorn: accessible to normal user, app access
 *  - /dev/lauberhorn_mgmt: lower-level access, available to superuser
 */
int create_devices(void)
{
	if (alloc_chrdev_region(&dev, 0, 1, "lauberhorn") < 0) {
		pr_err("alloc_chrdev_region failed\n");
		return -1;
	}
	pr_info("chrdev major = %d, minor = %d \n", MAJOR(dev), MINOR(dev));
	cdev_init(&cdev, &fops);
	if (cdev_add(&cdev, dev, 1) < 0) {
		pr_err("cdev_add failed\n");
		return -1;
	}
	cdev.owner = THIS_MODULE;
	if (IS_ERR(dev_class = class_create("lauberhorn_class"))) {
		pr_err("class_create failed\n");
		return -1;
	}
	if (IS_ERR(device_create(dev_class, NULL, dev, NULL, "lauberhorn"))) {
		pr_err("device_create failed\n");
		return -1;
	}
	pr_info("Device created at /dev/lauberhorn\n");
	return 0;
}

void remove_devices(void)
{
	device_destroy(dev_class, dev);
	class_destroy(dev_class);
	cdev_del(&cdev);
	unregister_chrdev_region(dev, 1);
	pr_info("Device removed\n");
}