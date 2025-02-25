#include<linux/module.h>
#include<linux/init.h> 
#include<linux/kthread.h>
#include<linux/sched.h> 
#include<linux/delay.h>

// Always print module name in pr_info, pr_err, etc.
#ifdef pr_fmt
#undef pr_fmt
#endif
#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

static struct task_struct *ktask;

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
}

static int __init mod_init(void) {
  pr_info("Loading...\n");

  ktask = kthread_create(thread_function, NULL, "pionic_kthread");
  // TODO: kthread_bind to CPU
  if (ktask != NULL) {
    wake_up_process(ktask);
    pr_info("kthread is running\n");
  } else {
    pr_info("kthread could not be created\n");
    return -1;
  }
  
  pr_info("Loaded\n");
  return 0;
}

static void __exit mod_exit(void) {
  int ret = 0;
  pr_info("Exiting...\n");

  ret = kthread_stop(ktask);
  pr_info("kthread returns %d", ret);
  
  pr_info("Unloaded\n");
}

MODULE_AUTHOR("Zikai Liu");
MODULE_DESCRIPTION("PIO NIC kernel driver");
MODULE_LICENSE("GPL");

module_init(mod_init);
module_exit(mod_exit);