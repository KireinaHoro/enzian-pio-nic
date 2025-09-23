#include "common.h"

int prepare_worker_thread()
{
  // Allocate worker CL addresses in translation unit
  
  // Change scheduler class to SCHED_FIFO and set priority

  // Set affinity to all worker cores
  
  // Set to TASK_UNINTERRUPTIBLE and wait for ISR to wake us up
}

void clean_worker_thread(u32 proc_idx, u32 thr_idx)
{
}

void enable_worker_thread() {

}

void disable_worker_thread() {

}