#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <assert.h>
#include <string.h>

#include "pionic.h"

static inline void memcpy16(void *dest, const void *src, size_t len) {
  uint16_t *d = dest;
  const uint16_t *s = src;
  size_t p = 0;

  while (p * 2 < len) {
    d[p] = s[p];
    ++p;
  }
}

static inline void hexdump(const void* data, size_t size) {
  char ascii[17];
  size_t i, j;
  ascii[16] = '\0';
  for (i = 0; i < size; ++i) {
    printf("%02X ", ((unsigned char*)data)[i]);
    if (((unsigned char*)data)[i] >= ' ' && ((unsigned char*)data)[i] <= '~') {
      ascii[i % 16] = ((unsigned char*)data)[i];
    } else {
      ascii[i % 16] = '.';
    }
    if ((i+1) % 8 == 0 || i+1 == size) {
      printf(" ");
      if ((i+1) % 16 == 0) {
        printf("|  %s \n", ascii);
      } else if (i+1 == size) {
        ascii[(i+1) % 16] = '\0';
        if ((i+1) % 16 <= 8) {
          printf(" ");
        }
        for (j = (i+1) % 16; j < 16; ++j) {
          printf("   ");
        }
        printf("|  %s \n", ascii);
      }
    }
  }
}

// TODO: remove from user code
static inline void write_reg(void *bar, uint64_t addr, uint64_t reg) {
  ((volatile uint64_t *)bar)[addr / 8] = reg;
}

static inline uint64_t read_reg(void *bar, uint64_t addr) {
  return ((volatile uint64_t *)bar)[addr / 8];
}

static bool recv_timed(pionic_ctx_t *ctx, int cycles, pionic_pkt_desc_t *desc) {
  if (cycles)
    pionic_set_rx_block_cycles(ctx, cycles);

  struct timespec start, stop;
  clock_gettime(CLOCK_REALTIME, &start);

  bool valid = pionic_rx(ctx, 0, desc);

  clock_gettime(CLOCK_REALTIME, &stop);

  if (valid) {
    printf("Received packet with length %ld at %p\n", desc->len, desc->buf);
  } else {
    printf("Did not receive packet\n");
  }

  double elapsed_us = (stop.tv_sec - start.tv_sec) * 1e6 + (stop.tv_nsec - start.tv_nsec) / 1e3;
  printf("Elapsed: %lf us\n", elapsed_us);

  return valid;
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

  pionic_pkt_desc_t desc;

  // check Rx timeout
  recv_timed(&ctx, 100, &desc);
  recv_timed(&ctx, 200, &desc);
  recv_timed(&ctx, 400, &desc);
  recv_timed(&ctx, 800, &desc);
  recv_timed(&ctx, 1600, &desc);
  recv_timed(&ctx, 3200, &desc);
  recv_timed(&ctx, 6400, &desc);

  // send packet and check rx data
  pionic_tx_get_desc(&ctx, 0, &desc);
  printf("Tx buffer at %p, len %ld\n", desc.buf, desc.len);

  const char *test_pattern = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Curabitur vestibulum, lacus vitae finibus faucibus, augue nisl ultricies enim, quis lacinia lorem risus ac elit. Donec mauris neque, sollicitudin a diam eu, accumsan lobortis mi. Sed sagittis, nibh ut eleifend gravida, diam mauris porttitor mauris, vel sagittis mi mi eu mi. Duis pellentesque, nunc at tincidunt efficitur, nibh lacus placerat augue, eu elementum leo nisi at sem. Fusce eleifend purus sed rhoncus tristique. Praesent sit amet nunc sit amet dolor fermentum dictum et ut dolor. Sed sit amet orci eu felis posuere accumsan sit amet in odio. Aenean ullamcorper porta nibh id commodo. Phasellus malesuada posuere gravida. Nullam mattis mattis nisi non gravida. Suspendisse risus risus, semper nec eros et, bibendum convallis urna. Sed aliquet vel leo quis iaculis. Pellentesque vel accumsan nulla, vitae tristique dolor.\nInterdum et malesuada fames ac ante ipsum primis in faucibus. Lorem ipsum dolor sit amet, consectetur adipiscing elit. Cras pulvinar laoreet mauris. Nulla facilisi. Etiam fermentum ante sit amet accumsan facilisis. Duis viverra turpis nibh, sed tincidunt odio efficitur nec. Donec dapibus, erat quis dapibus maximus, nibh sapien mollis quam, quis convallis velit nunc a libero. Curabitur interdum fringilla nibh laoreet pharetra. Nullam ultricies nec lorem eu faucibus. Aenean porta ante quis nulla maximus tempor. Nulla ut arcu ac est vestibulum auctor id quis felis. Morbi tincidunt diam felis, sed erat curae.";
  int to_send = 128; // arbitrary

  hexdump(desc.buf, to_send);
  // getchar();
  memcpy16(desc.buf, test_pattern, to_send);
  // FIXME: alignment!
  // memcpy(desc.buf, test_pattern, to_send);
  desc.len = to_send;
  printf("Written %d bytes to packet buffer\n", to_send);

  pionic_tx(&ctx, 0, &desc);

  // receive on rx to check
  int tries = 5;
  while (tries-- > 0) {
    if (recv_timed(&ctx, 6400, &desc)) break;
  }
  assert(tries > 0);
  hexdump(desc.buf, desc.len);

  ret = EXIT_SUCCESS;

fini:
  pionic_fini(&ctx);

fail:
  return ret;
}
