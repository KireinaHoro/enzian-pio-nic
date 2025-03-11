// IOCTL commands and handler

#include <linux/ioctl.h>
#include <linux/fs.h>
#include <linux/wait.h>
#include <linux/uaccess.h>  // copy_to/from_user
#include "common.h"

// Define ioctl numbers properly
// https://www.kernel.org/doc/Documentation/ioctl/ioctl-number.txt
#define IOCTL_YIELD _IO('p', 'y')
#define IOCTL_TEST_ACTIVATE_PID _IOW('t', 'a', pid_t)
// Others...

DECLARE_WAIT_QUEUE_HEAD(wq);
pid_t active_pid = -1;

// ioctl handler
long mod_ioctl(struct file *file, unsigned int cmd, unsigned long arg) {
  pid_t pid = -1;
  switch(cmd) {
    
    case IOCTL_YIELD:
      pr_info("(pid %i) going to wait\n", current->pid);
      wait_event_interruptible(wq, active_pid == current->pid);
      // TODO: active_pid atomic?
      // TODO: wait_event ignores signals
      pr_info("(pid %i) waked\n", current->pid);
      break;

    case IOCTL_TEST_ACTIVATE_PID:
      if (copy_from_user(&pid, (pid_t *) arg, sizeof(pid))) {
        pr_err("IOCTL_TEST_ACTIVATE_PID: copy_from_user failed\n");
        break;
      }
      pr_info("Going to activate pid %i\n", active_pid);
      active_pid = pid;  // TODO: atomic?
      wake_up(&wq);
      break;

    default:
      pr_err("Unknown ioctl command %u\n", cmd);
      break;
  }
  return 0;
}