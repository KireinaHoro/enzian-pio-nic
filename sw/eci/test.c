#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <assert.h>
#include <string.h>
#include <setjmp.h>
#include <signal.h>

#include "pionic.h"
#include "timeit.h"
#include "../include/common.h"
#include "../include/api.h"

// handle SIGBUS and resume -- https://stackoverflow.com/a/19416424/5520728
static jmp_buf *sigbus_jmp;

void signal_handler(int sig) {
  if (sig == SIGBUS) {
    if (sigbus_jmp) siglongjmp(*sigbus_jmp, 1);
    abort();
  }
}

typedef struct {
  // TX timestamps
  uint32_t acquire;
  uint32_t host_got_tx_buf; // needed because acquire is not reliable
  uint32_t after_tx_commit;
  uint32_t after_dma_read;
  uint32_t exit;
  // RX timestamps
  uint32_t entry;
  uint32_t after_rx_queue;
  uint32_t after_dma_write;
  uint32_t read_start;
  uint32_t after_read;
  uint32_t after_rx_commit; // we don't need this
  uint32_t host_read_complete;
} measure_t;

