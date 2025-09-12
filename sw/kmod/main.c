// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu
// Copyright (c) 2025 Zikai Liu
#include "common.h"
#include <linux/cdev.h>
#include <linux/delay.h>
#include <linux/device.h>
#include <linux/fs.h>
#include <linux/init.h>
#include <linux/ioctl.h>
#include <linux/kdev_t.h>
#include <linux/kthread.h>
#include <linux/module.h>
#include <linux/sched.h>
#include <linux/slab.h>    // kmalloc
#include <linux/uaccess.h> // copy_to/from_user
#include <linux/wait.h>
#include <asm/io.h>
#include <linux/smp.h>
#include <linux/irq.h>
#include <linux/interrupt.h>
#include <linux/irqreturn.h>
#include <linux/irqdomain.h>

#include <asm/arch_gicv3.h>


/* static struct task_struct *ktask;

int thread_function(void *data) {
    unsigned int i = 0;

    while (!kthread_should_stop()) {
        pr_info("Still running... %d secs\n", i);
        i++;
        if (i == 5)
        break;
        msleep(1000);
    }

    // Spin on !kthread_should_stop(), or kthread_stop segfaults
    while (!kthread_should_stop()) {
        // TODO: this causes 100% kernel utilization on a core
        schedule();
    }

    pr_info("kthread stopped\n");
    return 0;
} */

// In ioctl.c
long mod_ioctl(struct file *file, unsigned int cmd, unsigned long arg);

static struct file_operations fops = {
    .owner = THIS_MODULE,
    // .read           = etx_read,
    // .write          = etx_write,
    // .open           = etx_open,
    .unlocked_ioctl = mod_ioctl,
    // .release        = etx_release,
};

// ================================ FPI ================================

int do_fpi_irq_activate(void *data) {
    unsigned irq_no = (uint64_t)data;
    enable_percpu_irq(irq_no, 0);
    return 0;
}

int do_fpi_irq_deactivate(void *data) {
    unsigned irq_no = (uint64_t)data;
    disable_percpu_irq(irq_no);
    return 0;
}

// Module initialization
static int __init mod_init(void) {
  probe_versions();

  if (init_workers() != 0) {
    deinit_workers();
    return -1;
  }
  if (create_devices() != 0) {
    remove_devices();
    return -1;
  }
  if (init_workers() != 0) {
    deinit_workers();
    return -1;
  }
  if (init_bypass() != 0) {
    deinit_bypass();
    return -1;
  }
  init_datapath();

  pr_info("Lauberhorn kmod loaded\n");
  return 0;
}

// Module exit
static void __exit mod_exit(void) {
  pr_info("Lauberhorn kmod exiting...\n");

  remove_devices();
  deinit_workers();
  deinit_bypass();
  deinit_datapath();

  /* ret = kthread_stop(ktask);
  pr_info("kthread returns %d", ret); */

  pr_info("Lauberhorn kmod unloaded\n");
}

MODULE_AUTHOR("Zikai Liu");
MODULE_AUTHOR("Pengcheng Xu");
MODULE_DESCRIPTION("Lauberhorn kernel driver");
MODULE_LICENSE("Dual BSD/GPL");

module_init(mod_init);
module_exit(mod_exit);
