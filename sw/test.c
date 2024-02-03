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
  double tx;
  uint32_t entry;
  uint32_t after_rx_queue;
  uint32_t after_dma_write;
  uint32_t read_start;
  uint32_t after_read;
  uint32_t after_commit; // we don't need this
  uint32_t host_read_complete;
} measure_t;

static uint8_t *test_data;

static measure_t loopback_timed(pionic_ctx_t *ctx, uint32_t length, uint32_t offset) {
  int cid = 0;

  pionic_pkt_desc_t desc;
  pionic_tx_get_desc(ctx, cid, &desc);
  // printf("Tx buffer at %p, len %ld; sending %d B\n", desc.buf, desc.len, length);

  char rx_buf[length];
  const uint8_t *tx_buf = test_data;

  // simple elapsed time for tx
  // TODO: instrument tx path as well (?)
  TIMEIT_START(ctx, tx)

  if (length > 0) {
    memcpy(desc.buf, tx_buf, length);
    desc.len = length;
    pionic_tx(ctx, cid, &desc);
  }

  TIMEIT_END(ctx, tx)

  // Rx path is well instrumented, so let's take advantage of this.
  //
  // Description of all timestamps:
  //
  // Entry:         the NIC received the packet from CMAC (lastFire)
  // AfterRxQueue:  packet exited from all Rx queuing
  // AfterDmaWrite: packet written to packet buffer
  // ReadStart:     the NIC received the read command (on hostRxNext)
  // AfterRead:     the read is finished (on hostRxNext)
  // AfterCommit:   the host freed the packet (on hostRxNextAck)
  //
  // In addition, we take one timestamp HostReadCompleted (over PCIe) when we received the
  // packet on the CPU.
  //
  // XXX: we hope that we get to issue the read before the packet actually come back
  //      from loopback.  In this case we have the following timestamp sequence:
  //
  //      ReadStart, Entry, AfterRxQueue, AfterDmaWrite, AfterRead, HostReadCompleted, AfterCommit
  //
  //      In this setup we would not bloat the latency artificially (due to arbitrary latency
  //      between AfterDmaWrite and ReadStart, as the host is not yet ready).
  //
  // Taking HostReadCompleted on the CPU requires one PCIe round-trip, resulting in a measurement too late
  // by half the RTT.  This is measured beforehand and subtracted during actual interval calculation.

  measure_t ret = { 0 };

  bool got_pkt = pionic_rx(ctx, cid, &desc);

  ret.host_read_complete = read64(ctx, PIONIC_GLOBAL_CYCLES_COUNT);

  if (length > 0) {
    assert(got_pkt && "failed to receive packet");

    // check rx match with tx
    assert(desc.len == length && "rx packet length does not match tx");
    memcpy(rx_buf, desc.buf, desc.len);
    pionic_rx_ack(ctx, cid, &desc);
  } else {
    assert(!got_pkt && "got packet when not expecting one");
  }


  if (length > 0 && memcmp(rx_buf, tx_buf, length)) {
    printf("FAIL: data mismatch!  Expected (tx):\n");
    hexdump(tx_buf, length);
    printf("Rx:\n");
    hexdump(rx_buf, length);
    assert(false);
  }

  ret.tx = TIMEIT_US(tx);
  ret.entry = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__ENTRY(cid));
  ret.after_rx_queue = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_QUEUE(cid));
  ret.read_start = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__READ_START(cid));
  ret.after_dma_write = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_DMA_WRITE(cid));
  ret.after_read = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_READ(cid));
  ret.after_commit = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_COMMIT(cid));

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
  if (!sigsetjmp(sigbus_jmpbuf, 1)) {
    for (int us = 40 * 1000; us < 1000 * 1000; us += 1000) {
      pionic_set_rx_block_cycles(&ctx, us * 1000 / 4); // 250 MHz
      loopback_timed(&ctx, 0, 0);
    }
  } else {
    printf("Caught SIGBUS, continuing...\n");
  }

  // 40 ms
  pionic_set_rx_block_cycles(&ctx, US_TO_CYCLES(40 * 1000));

  // estimate pcie roundtrip time
  FILE *out = fopen("pcie_lat.csv", "w");
  fprintf(out, "pcie_lat_cyc\n");
  int num_trials = 50;
  uint64_t cycles = read64(&ctx, PIONIC_GLOBAL_CYCLES_COUNT);
  for (int i = 0; i < num_trials; ++i) {
    uint64_t new_cycles = read64(&ctx, PIONIC_GLOBAL_CYCLES_COUNT);
    fprintf(out, "%ld\n", new_cycles - cycles);
    cycles = new_cycles;
  }
  fclose(out);

  // only use core 0
  pionic_set_core_mask(&ctx, 1);

  out = fopen("loopback.csv", "w");
  fprintf(out, "size,tx_us,entry_cyc,after_rx_queue_cyc,after_dma_write_cyc,read_start_cyc,after_read_cyc,after_commit_cyc,host_read_complete_cyc\n");

  // send packet and check rx data
  int min_pkt = 64, max_pkt = 1500, step = 64;

  for (int to_send = min_pkt; to_send <= max_pkt; to_send += step) {
    for (int i = 0; i < num_trials; ++i) {
      measure_t m = loopback_timed(&ctx, to_send, i * 64);
      fprintf(out, "%d,%lf,%u,%u,%u,%u,%u,%u,%u\n", to_send, m.tx, m.entry, m.after_rx_queue, m.after_dma_write, m.read_start, m.after_read, m.after_commit, m.host_read_complete);
    }
  }

  fclose(out);

  ret = EXIT_SUCCESS;

fini:
  pionic_fini(&ctx);

fail:
  return ret;
}
