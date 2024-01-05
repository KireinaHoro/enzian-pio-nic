#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <assert.h>
#include <string.h>

#include "pionic.h"
#include "timeit.h"
#include "common.h"

static const char test_pattern[] = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Curabitur vestibulum, lacus vitae finibus faucibus, augue nisl ultricies enim, quis lacinia lorem risus ac elit. Donec mauris neque, sollicitudin a diam eu, accumsan lobortis mi. Sed sagittis, nibh ut eleifend gravida, diam mauris porttitor mauris, vel sagittis mi mi eu mi. Duis pellentesque, nunc at tincidunt efficitur, nibh lacus placerat augue, eu elementum leo nisi at sem. Fusce eleifend purus sed rhoncus tristique. Praesent sit amet nunc sit amet dolor fermentum dictum et ut dolor. Sed sit amet orci eu felis posuere accumsan sit amet in odio. Aenean ullamcorper porta nibh id commodo. Phasellus malesuada posuere gravida. Nullam mattis mattis nisi non gravida. Suspendisse risus risus, semper nec eros et, bibendum convallis urna. Sed aliquet vel leo quis iaculis. Pellentesque vel accumsan nulla, vitae tristique dolor.\nInterdum et malesuada fames ac ante ipsum primis in faucibus. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Cras pulvinar laoreet mauris. Nulla facilisi. Etiam fermentum ante sit amet accumsan facilisis. Duis viverra turpis nibh, sed tincidunt odio efficitur nec. Donec dapibus, erat quis dapibus maximus, nibh sapien mollis quam, quis convallis velit nunc a libero. Curabitur interdum fringilla nibh laoreet pharetra. Nullam ultricies nec lorem eu faucibus. Aenean porta ante quis nulla maximus tempor. Nulla ut arcu ac est vestibulum auctor id quis felis. Morbi tincidunt diam felis, sed erat curae.";

static bool recv_timed(pionic_ctx_t *ctx, int cycles, pionic_pkt_desc_t *desc) {
  if (cycles)
    pionic_set_rx_block_cycles(ctx, cycles);

  TIMEIT_START(rx)

  bool valid = pionic_rx(ctx, 0, desc);

  TIMEIT_END(rx)

  if (valid) {
    printf("Received packet with length %ld at %p\n", desc->len, desc->buf);
  } else {
    printf("Did not receive packet\n");
  }

  printf("Elapsed: %lf us\n", TIMEIT_US(rx));

  return valid;
}

static double tx_rx_timed(pionic_ctx_t *ctx, uint32_t length, uint32_t offset) {
  assert(offset <= sizeof(test_pattern) && offset + length <= sizeof(test_pattern));

  pionic_pkt_desc_t desc;
  pionic_tx_get_desc(ctx, 0, &desc);
  printf("Tx buffer at %p, len %ld; sending %d B\n", desc.buf, desc.len, length);

  char rx_buf[length];
  const char *tx_buf = &test_pattern[offset];

  TIMEIT_START(rxtx)

  memcpy(desc.buf, tx_buf, length);
  desc.len = length;
  pionic_tx(ctx, 0, &desc);

  int tries = 20;
  while (tries-- > 0) {
    if (pionic_rx(ctx, 0, &desc)) break;
  }
  assert(tries > 0 && "tries exceeded in receiving packet");

  // check rx match with tx
  assert(desc.len == length && "rx packet length does not match tx");
  memcpy(rx_buf, desc.buf, desc.len);
  // pionic_rx_ack(ctx, 0, &desc);

  TIMEIT_END(rxtx);

  if (memcmp(rx_buf, tx_buf, length)) {
    printf("FAIL: data mismatch!  Expected (tx):\n");
    hexdump(tx_buf, length);
    printf("Rx:\n");
    hexdump(rx_buf, length);
    assert(false);
  }

  return TIMEIT_US(rxtx);
}

int main(int argc, char *argv[]) {
  int ret = EXIT_FAILURE;
  if (argc != 2) {
    fprintf(stderr, "usage: %s <pcie dev id>\n", argv[0]);
    goto fail;
  }

  pionic_ctx_t ctx;
  if (pionic_init(&ctx, argv[1], true) < 0) {
    goto fini;
  }

  dump_stats(&ctx, 0);

  pionic_pkt_desc_t desc;

  // estimate Rx timeout
  /*
  for (int us = 40 * 1000; us < 1000 * 1000; us += 1000) {
    recv_timed(&ctx, us * 1000 / 4, &desc); // 250 MHz
  }
  */

  // 40 ms
  pionic_set_rx_block_cycles(&ctx, 40 * 1000 * 1000 / 4);

  // only use core 0
  pionic_set_core_mask(&ctx, 1);

  // send packet and check rx data
  for (int i = 0; i < 1; ++i) {
    int to_send = 64;
    double t = tx_rx_timed(&ctx, to_send, i * to_send);

    printf("Roundtrip time: %lf us\n", t);
  }

  ret = EXIT_SUCCESS;

fini:
  pionic_fini(&ctx);

fail:
  return ret;
}
