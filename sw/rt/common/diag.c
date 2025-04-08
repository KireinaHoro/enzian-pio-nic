#include "diag.h"
#include "hal.h"
#include "profile.h"

#include "gen/pionic_eci_global.h"
#include "gen/pionic_eci_core.h"

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <setjmp.h>
#include <signal.h>
#include <assert.h>

void pionic_dump_glb_stats(pionic_global_t *glb) {
#define READ_PRINT(name) printf("%s\t: %#lx\n", #name, pionic_global(name ## _rd)(glb))
  READ_PRINT(rx_overflow_count);
#undef READ_PRINT
}

void pionic_dump_core_stats(pionic_core_t *core) {
#define READ_PRINT(name) printf("core: %s\t: %#lx\n", #name, pionic_core(name ## _rd)(core))
  READ_PRINT(rx_packet_count);
  READ_PRINT(tx_packet_count);
  READ_PRINT(rx_dma_error_count);
  READ_PRINT(tx_dma_error_count);
  READ_PRINT(rx_alloc_occupancy_up_to_128);
  READ_PRINT(rx_alloc_occupancy_up_to_1518);
  READ_PRINT(rx_alloc_occupancy_up_to_9618);
  READ_PRINT(rx_fsm_state);
  READ_PRINT(rx_curr_cl_idx);
  READ_PRINT(tx_fsm_state);
  READ_PRINT(tx_curr_cl_idx);
#undef READ_PRINT
}

void pionic_reset_pkt_alloc(pionic_core_t *core) {
  pionic_core(alloc_reset_wr)(core, 1);
  usleep(1); // arbitrary
  pionic_core(alloc_reset_wr)(core, 0);
}

// handle SIGBUS and resume -- https://stackoverflow.com/a/19416424/5520728
static jmp_buf *sigbus_jmp;

static void signal_handler(int sig) {
  if (sig == SIGBUS) {
    if (sigbus_jmp) siglongjmp(*sigbus_jmp, 1);
    abort();
  }
}

void pionic_probe_rx_block_cycles(pionic_ctx_t ctx) {
  printf("Testing for maximum blocking time...\n");

  struct sigaction new = {
    .sa_handler = signal_handler,
    .sa_flags = SA_RESETHAND, // only catch once
  };
  sigemptyset(&new.sa_mask);
  if (sigaction(SIGBUS, &new, NULL)) {
    perror("sigaction");
    return;
  }

  jmp_buf sigbus_jmpbuf;
  sigbus_jmp = &sigbus_jmpbuf;
  volatile int us = -1;

  if (!sigsetjmp(sigbus_jmpbuf, 1)) {
    // probe from 1 ms to 1 s
    for (us = 1000; us < 1000 * 1000; us += 1000) {
      pionic_set_rx_block_cycles(ctx, pionic_us_to_cycles(us));
      pionic_pkt_desc_t desc;
      bool got_pkt = pionic_rx(ctx, 0, &desc);
      assert(!got_pkt);
    }
  } else {
    printf("Caught SIGBUS when blocking for %d us\n", us);
  }
}

