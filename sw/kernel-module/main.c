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

static dev_t dev = 0;
static struct cdev cdev;
static struct class *dev_class;

// Module initialization
static int __init mod_init(void) {
  int ret = 0;

  pr_info("Loading...\n");

  /* ktask = kthread_create(thread_function, NULL, "pionic_kthread");
  // TODO: kthread_bind to CPU
  if (ktask != NULL) {
    wake_up_process(ktask);
    pr_info("kthread is running\n");
  } else {
    pr_info("kthread could not be created\n");
    ret = -1;
    goto err_kthread_create;
  } */

  // Register the device

  // Use char device for now...
  if (alloc_chrdev_region(&dev, 0, 1, "pionic_device") < 0) {
    pr_err("alloc_chrdev_region failed\n");
    ret = -1;
    goto err_alloc_chrdev_region;
  }
  pr_info("chrdev major = %d, minor = %d \n", MAJOR(dev), MINOR(dev));

  cdev_init(&cdev, &fops);

  if (cdev_add(&cdev, dev, 1) < 0) {
    pr_err("cdev_add failed\n");
    ret = -1;
    goto err_cdev_add;
  }

  if (IS_ERR(dev_class = class_create(THIS_MODULE, "pionic_class"))) {
    pr_err("class_create failed\n");
    ret = -1;
    goto err_class_create;
  }

  if (IS_ERR(device_create(dev_class, NULL, dev, NULL, "pionic"))) {
    pr_err("device_create failed\n");
    ret = -1;
    goto err_device_create;
  }
  pr_info("Device created at /dev/pionic\n");

  pr_info("Loaded\n");
  goto done;

  device_destroy(dev_class, dev);
err_device_create:
  class_destroy(dev_class);
err_class_create:
  cdev_del(&cdev);
err_cdev_add:
  unregister_chrdev_region(dev, 1);
err_alloc_chrdev_region:
/*   // delete the kthread...
err_kthread_create: */
done:
  return ret;
}

// Module exit
static void __exit mod_exit(void) {
  pr_info("Exiting...\n");

  // Remove the device
  device_destroy(dev_class, dev);
  class_destroy(dev_class);
  cdev_del(&cdev);
  unregister_chrdev_region(dev, 1);
  pr_info("Device removed\n");

  /* ret = kthread_stop(ktask);
  pr_info("kthread returns %d", ret); */

  pr_info("Unloaded\n");
}

MODULE_AUTHOR("Zikai Liu");
MODULE_DESCRIPTION("PIO NIC kernel driver");
MODULE_LICENSE("Dual BSD/GPL");

module_init(mod_init);
module_exit(mod_exit);
