#include "diag.h"
#include "hal.h"
#include "profile.h"

#include "regs.h"

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <setjmp.h>
#include <signal.h>
#include <assert.h>

void pionic_dump_glb_stats(pionic_ctx_t ctx) {
#define READ_PRINT(name) printf("%s\t: %#lx\n", #name, read64(ctx, name));
  READ_PRINT(PIONIC_GLOBAL_RX_OVERFLOW_COUNT)
#undef READ_PRINT
}

void pionic_dump_core_stats(pionic_ctx_t ctx, int cid) {
#define READ_PRINT(name) printf("core %d: %s\t: %#lx\n", cid, #name, read64(ctx, name(cid)));
  READ_PRINT(PIONIC_CONTROL_RX_PACKET_COUNT)
  READ_PRINT(PIONIC_CONTROL_TX_PACKET_COUNT)
  READ_PRINT(PIONIC_CONTROL_RX_DMA_ERROR_COUNT)
  READ_PRINT(PIONIC_CONTROL_TX_DMA_ERROR_COUNT)
  READ_PRINT(PIONIC_CONTROL_RX_ALLOC_OCCUPANCY_UP_TO_128)
  READ_PRINT(PIONIC_CONTROL_RX_ALLOC_OCCUPANCY_UP_TO_1518)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__ENTRY)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_QUEUE)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__READ_START)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_DMA_WRITE)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_READ)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_COMMIT)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__ACQUIRE)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_TX_COMMIT)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_DMA_READ)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__EXIT)
#undef READ_PRINT
}

void pionic_reset_pkt_alloc(pionic_ctx_t ctx, int cid) {
  write64(ctx, PIONIC_CONTROL_ALLOC_RESET(cid), 1);
  usleep(1); // arbitrary
  write64(ctx, PIONIC_CONTROL_ALLOC_RESET(cid), 0);
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

