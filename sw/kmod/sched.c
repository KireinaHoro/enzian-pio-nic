#include "common.h"

// defined in worker.c
extern int worker_lo, worker_hi;

int prepare_worker_thread(u32 thr_idx)
{
  // Change scheduler class to SCHED_FIFO and set priority to 70

  // Wait for HW to wake us up
  disable_worker_thread();
}

void clean_worker_thread(u32 proc_idx, u32 thr_idx)
{
  // Reset scheduler class to SCHED_OTHER

  // Reset affinity to all cores
  
  // Set to TASK_RUNNABLE again for it to finish clean-up
}

void enable_worker_thread(pid_t tid, u32 core_idx) {
  // XXX: must be reentrant safe!  Multiple register accesses can interleave,
  //      lock updates to various tables where idx is a separate access

  // Update affinity to this core only
  
  // Set to TASK_RUNNABLE 
  
  // Route CL
  
  // Lock preemption control CL in L2
  // Preemption 
}

void disable_worker_thread(pid_t tid) {
  // Set to TASK_UNINTERRUPTIBLE and wait for ISR to wake us up
  
  // Unroute CL: find entry and disable
}