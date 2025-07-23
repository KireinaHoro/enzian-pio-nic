// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
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

static DEFINE_PER_CPU_READ_MOSTLY(int, fpi_cpu_number);
static unsigned fpi_irq_no;

static irqreturn_t fpi_handler(int irq, void *data) {
    printk("%s.%d[%2d]: FPI %d\n", __func__, __LINE__, smp_processor_id(), irq);
    // wq_flag = 1;
    // smp_wmb();
    // wake_up_interruptible(&wq);
    // TODO: continue in the second half, to get a proper thread context
    return IRQ_HANDLED;
}

static int do_fpi_irq_activate(void *unused) {
    enable_percpu_irq(fpi_irq_no, 0);
    return 0;
}

static int do_fpi_irq_deactivate(void *unused) {
    disable_percpu_irq(fpi_irq_no);
    return 0;
}

static int init_fpi(void) {
    int err, cpu_no;
    struct irq_data *gic_irq_data;
    struct irq_domain *gic_domain;
    struct fwnode_handle *fwnode;
    static struct irq_fwspec fwspec_fpi;

    // init_waitqueue_head(&wq);
    // wq_flag = 0;

    gic_irq_data = irq_get_irq_data(1U);
    gic_domain = gic_irq_data->domain;
    // Assuming that fwnode is the first element of structure gic_chip_data
    fwnode = *(struct fwnode_handle **)(gic_domain->host_data);

    fwspec_fpi.fwnode = fwnode;
    fwspec_fpi.param_count = 1;
    fwspec_fpi.param[0] = 8; // first free SGI interrupt
    err = irq_create_fwspec_mapping(&fwspec_fpi);
    if (err < 0) {
        pr_warn("irq_create_fwspec_mapping returns %d\n", err);
    }
    fpi_irq_no = err;
    pr_info("Allocated interrupt number = %d\n", fpi_irq_no);
    smp_wmb();
    err = request_percpu_irq(fpi_irq_no, fpi_handler, "Lauberhorn FPI", &fpi_cpu_number);
    if (err < 0) {
        pr_warn("request_percpu_irq returns %d\n", err);
        // FIXME: on Ubuntu 20.04, err == -22
    }
    for_each_online_cpu(cpu_no) { // active the interrupt on all cores
        err = smp_call_on_cpu(cpu_no, do_fpi_irq_activate, NULL, true);
        if (err < 0) {
            pr_warn("smp_call_on_cpu returns %d\n", err);
        }
    }

    return 0;
}

static void deinit_fpi(void) {
    int err, cpu_no;

    for_each_online_cpu(cpu_no) { // deactive the interrupt on all cores
        err = smp_call_on_cpu(cpu_no, do_fpi_irq_deactivate, NULL, true);
        WARN_ON(err < 0);
    }
    free_percpu_irq(fpi_irq_no, &fpi_cpu_number);
    irq_dispose_mapping(fpi_irq_no);
}

static dev_t dev = 0;
static struct cdev cdev;
static struct class *dev_class;


static int create_device(void) {
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
  if (IS_ERR(dev_class = class_create(THIS_MODULE, "lauberhorn_class"))) {
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

static void remove_device(void) {
  device_destroy(dev_class, dev);
  class_destroy(dev_class);
  cdev_del(&cdev);
  unregister_chrdev_region(dev, 1);
  pr_info("Device removed\n");
}


// Module initialization
static int __init mod_init(void) {
  pr_info("Lauberhorn kmod loading...\n");

  /* ktask = kthread_create(thread_function, NULL, "lauberhorn_kthread");
  // TODO: kthread_bind to CPU
  if (ktask != NULL) {
    wake_up_process(ktask);
    pr_info("kthread is running\n");
  } else {
    pr_info("kthread could not be created\n");
    ret = -1;
    goto err_kthread_create;
  } */

  if (create_device() != 0) {
    remove_device();
    return -1;
  }
  if (init_fpi() != 0) {
    deinit_fpi();
    return -1;
  }

  pr_info("Lauberhorn kmod loaded\n");
  return 0;
}

// Module exit
static void __exit mod_exit(void) {
  pr_info("Lauberhorn kmod exiting...\n");

  remove_device();
  deinit_fpi();

  /* ret = kthread_stop(ktask);
  pr_info("kthread returns %d", ret); */

  pr_info("Lauberhorn kmod unloaded\n");
}

MODULE_AUTHOR("Zikai Liu");
MODULE_DESCRIPTION("PIO NIC kernel driver");
MODULE_LICENSE("Dual BSD/GPL");

module_init(mod_init);
module_exit(mod_exit);
