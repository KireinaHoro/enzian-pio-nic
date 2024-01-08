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
#include "common.h"

// handle SIGBUS and resume -- https://stackoverflow.com/a/19416424/5520728
static jmp_buf *sigbus_jmp;

void signal_handler(int sig) {
  if (sig == SIGBUS) {
    if (sigbus_jmp) siglongjmp(*sigbus_jmp, 1);
    abort();
  }
}

typedef struct {
  double roundtrip;
  double rx;
} measure_t;

static uint8_t *test_data;

static measure_t tx_rx_timed(pionic_ctx_t *ctx, uint32_t length, uint32_t offset) {
  pionic_pkt_desc_t desc;
  pionic_tx_get_desc(ctx, 0, &desc);
  // printf("Tx buffer at %p, len %ld; sending %d B\n", desc.buf, desc.len, length);

  char rx_buf[length];
  const char *tx_buf = test_data;

  TIMEIT_START(rxtx)

  if (length > 0) {
    memcpy(desc.buf, tx_buf, length);
    desc.len = length;
    pionic_tx(ctx, 0, &desc);
  }

  TIMEIT_START(rx)

  bool got_pkt = pionic_rx(ctx, 0, &desc);

  if (length > 0) {
    assert(got_pkt && "failed to receive packet");

    // check rx match with tx
    assert(desc.len == length && "rx packet length does not match tx");
    memcpy(rx_buf, desc.buf, desc.len);
    pionic_rx_ack(ctx, 0, &desc);
  } else {
    assert(!got_pkt && "got packet when not expecting one");
  }

  TIMEIT_END(rx)
  TIMEIT_END(rxtx)

  if (length > 0 && memcmp(rx_buf, tx_buf, length)) {
    printf("FAIL: data mismatch!  Expected (tx):\n");
    hexdump(tx_buf, length);
    printf("Rx:\n");
    hexdump(rx_buf, length);
    assert(false);
  }

  measure_t ret = {
    .roundtrip = TIMEIT_US(rxtx),
    .rx = TIMEIT_US(rx),
  };
  // printf("Tx+Rx: %lf us; Rx: %lf us\n", TIMEIT_US(rxtx), TIMEIT_US(rx));

  return ret;
}

int main(int argc, char *argv[]) {
  int ret = EXIT_FAILURE;
  if (argc != 2) {
    fprintf(stderr, "usage: %s <pcie dev id>\n", argv[0]);
    goto fail;
  }

  test_data = malloc(4096);
  for (int i = 0; i < 4096; ++i) {
    test_data[i] = rand();
  }

  struct sigaction new = { .sa_handler = signal_handler };
  sigemptyset(&new.sa_mask);
  if (sigaction(SIGBUS, &new, NULL)) {
    perror("sigaction");
    goto fail;
  }

  pionic_ctx_t ctx;
  if (pionic_init(&ctx, argv[1], true) < 0) {
    goto fini;
  }

  dump_stats(&ctx, 0);

  // estimate Rx timeout
  jmp_buf sigbus_jmpbuf;
  sigbus_jmp = &sigbus_jmpbuf;
  double time_syscall = 0;
  int count = 0;
  if (!sigsetjmp(sigbus_jmpbuf, 1)) {
    for (int us = 40 * 1000; us < 1000 * 1000; us += 1000) {
      pionic_set_rx_block_cycles(&ctx, us * 1000 / 4); // 250 MHz
      measure_t m = tx_rx_timed(&ctx, 0, 0);
      time_syscall += (m.roundtrip - m.rx) / 2;
      count++;
    }
  } else {
    printf("Caught SIGBUS, continuing...\n");
  }
  printf("clock_gettime: %lf us\n", time_syscall / count);

  // 40 ms
  pionic_set_rx_block_cycles(&ctx, 40 * 1000 * 1000 / 4);

  // only use core 0
  pionic_set_core_mask(&ctx, 1);

  // send packet and check rx data
  measure_t sum = { 0 };
  int total = 20;
  for (int to_send = 64; to_send <= 1518; to_send += 64) {
    for (int i = 0; i < total; ++i) {
      measure_t m = tx_rx_timed(&ctx, to_send, i * 64);
      sum.roundtrip += m.roundtrip;
      sum.rx += m.rx;
    }
    printf("%d B:\tRTT average: %lf, Rx average: %lf\n", to_send, sum.roundtrip / total, sum.rx / total);
  }

  ret = EXIT_SUCCESS;

fini:
  pionic_fini(&ctx);

fail:
  return ret;
}
