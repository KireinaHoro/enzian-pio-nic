// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu, Zikai Liu

#include "common.h"
#include "ioctl.h"

static dev_t dev = 0;
static struct cdev cdev;
static struct class *dev_class;

// Defines an application thread
struct thr_def {
	bool enabled;

	pid_t pid;

	// Used to update the per-thread CL address to core worker mapping
	u32 translation_tbl_idx;
};

struct srv_def {
	bool enabled;

	// Used to check if service with same definition is already registered
	u16 port;
	u32 prog_num, prog_ver, proc_num;

	// For debugging
	void *func_ptr;

	u32 proc_idx;
};
static struct srv_def srv_defs[LAUBERHORN_NUM_SERVICES];

struct proc_def {
	bool enabled;

	// This is the PID actually programmed into the process table
	pid_t tgid;
	struct thr_def thr_defs[LAUBERHORN_NUM_WORKER_CORES];
};
static struct proc_def proc_defs[LAUBERHORN_NUM_PROCS];

static void register_service(u16 port, u32 prog_num, u32 prog_ver, u32 proc_num,
			     void *func_ptr, pid_t tgid)
{
	int i, srv_idx, proc_idx;

	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		if (!srv_defs[i].enabled) {
			srv_idx = i;
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

	for (i = 0; i < LAUBERHORN_NUM_PROCS; ++i) {
		if (proc_defs[i].enabled && proc_defs[i].tgid == tgid) {
			proc_idx = i;
			break;
		}
	}
	if (i == LAUBERHORN_NUM_PROCS) {
		pr_err("Failed to find TGID %d for service, bug?\n", tgid);
		return -1;
	}

	srv_defs[srv_idx].port = port;
	srv_defs[srv_idx].prog_num = prog_num;
	srv_defs[srv_idx].prog_ver = prog_ver;
	srv_defs[srv_idx].proc_num = proc_num;
	srv_defs[srv_idx].func_ptr = func_ptr;
	srv_defs[srv_idx].proc_idx = proc_idx;

	// TODO: program into HW

	srv_defs[srv_idx].enabled = true;
	pr_info("Registered service #%d under TGID %d\n", srv_idx, tgid);
	return 0;
}

static void deregister_service(u32 idx)
{
	pid_t tgid;
	if (!srv_defs[idx].enabled) {
		pr_err("Service #%d not registered, bug?\n", idx);
		return;
	}

	tgid = proc_defs[srv_defs[idx].proc_idx].tgid;

	// TODO: program into HW

	srv_defs[idx].enabled = false;
	pr_info("Deregistered service #%d (was with TGID %d)\n", idx, tgid);
}

static int register_app(pid_t tgid)
{
	int i, proc_idx;

	for (i = 0; i < LAUBERHORN_NUM_PROCS; ++i) {
		if (!proc_defs[i].enabled) {
			proc_idx = i;
			break;
		} else if (proc_defs[i].tgid == tgid) {
			pr_err("Process %d already registered, bug?\n", tgid);
			return -1;
		}
	}

	if (i == LAUBERHORN_NUM_PROCS) {
		pr_err("No more free process slots in HW: %d already registered\n",
		       i);
		return -1;
	}

	proc_defs[proc_idx].tgid = tgid;
	proc_defs[proc_idx].num_threads = 0;

	// TODO: program into HW
	//
	proc_defs[proc_idx].enabled = true;
	pr_info("Registered application #%d with TGID %d\n", num_procs, tgid);
	return 0;
}

static void deregister_app(pid_t tgid)
{
	int i, proc_idx;

	for (i = 0; i < num_procs; ++i) {
		if (proc_defs[i].enabled && proc_defs[i].tgid == tgid) {
			proc_idx = i;
			break;
		}
	}

	if (i == LAUBERHORN_NUM_PROCS) {
		pr_err("Process %d not registered, bug?\n", tgid);
		return;
	}

	// Stop all threads under this app
	for (i = 0; i < LAUBERHORN_NUM_WORKER_CORES; ++i) {
		if (proc_defs[proc_idx].thr_defs[i].enabled) {
			clean_worker_thread(proc_idx, i);
		}
	}

	// Deregister all services under this app
	for (i = 0; i < LAUBERHORN_NUM_SERVICES; ++i) {
		if (srv_defs[i].proc_idx == proc_idx) {
			deregister_service(i);
		}
	}

	// TODO: program into HW

	proc_defs[proc_idx].enabled = false;
	pr_info("Deregistered app #%d with TGID %d\n", proc_idx,
		proc_defs[proc_idx].tgid);
}

static long app_dev_ioctl(struct file *file, unsigned int cmd,
			  unsigned long arg)
{
	pid_t pid = -1;
	switch (cmd) {
	case IOCTL_YIELD:
		pr_info("(pid %i) going to wait\n", current->pid);
		wait_event_interruptible(wq, active_pid == current->pid);
		// TODO: active_pid atomic?
		// TODO: wait_event ignores signals
		pr_info("(pid %i) waked\n", current->pid);
		break;

	case IOCTL_TEST_ACTIVATE_PID:
		if (copy_from_user(&pid, (pid_t *)arg, sizeof(pid))) {
			pr_err("IOCTL_TEST_ACTIVATE_PID: copy_from_user failed\n");
			break;
		}
		pr_info("Going to activate pid %i\n", active_pid);
		active_pid = pid; // TODO: atomic?
		wake_up(&wq);
		break;

	default:
		pr_err("Unknown ioctl command %u\n", cmd);
		break;
	}
	return 0;
}

static int app_dev_open(struct inode *i, struct file *f)
{
  // Register service
}

static int app_dev_release(struct inode *i, struct file *f)
{
}

static void close_vma(struct vm_area_struct *vma) {

}

static const char *name_vma(struct vm_area_struct *vma) {

}

static const struct vm_operations vm_ops = {
  .close = close_vma,
  .name = name_vma,
};

static int app_dev_mmap(struct file *f, struct vm_area_struct *vma) {

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