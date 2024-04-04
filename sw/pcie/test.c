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

static uint8_t *test_data;

static measure_t loopback_timed(pionic_ctx_t *ctx, uint32_t length, uint32_t offset) {
  int cid = 0;

  pionic_pkt_desc_t desc;
  pionic_tx_get_desc(ctx, cid, &desc);
  // printf("Tx buffer at %p, len %ld; sending %d B\n", desc.buf, desc.len, length);

  char rx_buf[length];
  const uint8_t *tx_buf = test_data;

  measure_t ret = { 0 };

  // Tx path is instrumented with hardware timestamps.
  // However, Acquire is not reliable: it fires on read, but sits in the same 512B
  // as other registers, so a read on those would also trigger Acquire.
  // Use a host-side timestamp as substitute.
  ret.host_got_tx_buf = read64(ctx, PIONIC_GLOBAL_CYCLES);

  if (length > 0) {
    memcpy(desc.buf, tx_buf, length);
    desc.len = length;
    pionic_tx(ctx, cid, &desc);
  }

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
  // packet on the CPU (into the registers).  This includes the memcpy from PCIe into the
  // CPU registers.
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

  bool got_pkt = pionic_rx(ctx, cid, &desc);

  if (length > 0) {
    assert(got_pkt && "failed to receive packet");

    // check rx match with tx
#ifdef DEBUG
    printf("rx packet len: %ld; tx packet len: %d\n", desc.len, length);
    printf("Reading out packet @ %p, %ld bytes\n", desc.buf, desc.len);
#endif
    assert(desc.len == length && "rx packet length does not match tx");
    memcpy(rx_buf, desc.buf, desc.len);

    ret.host_read_complete = read64(ctx, PIONIC_GLOBAL_CYCLES);

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

  ret.acquire = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__ACQUIRE(cid));
  ret.after_tx_commit = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_TX_COMMIT(cid));
  ret.after_dma_read = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_DMA_READ(cid));
  ret.exit = read64(ctx, PIONIC_CONTROL_HOST_TX_LAST_PROFILE__EXIT(cid));

  ret.entry = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__ENTRY(cid));
  ret.after_rx_queue = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_QUEUE(cid));
  ret.read_start = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__READ_START(cid));
  ret.after_dma_write = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_DMA_WRITE(cid));
  ret.after_read = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_READ(cid));
  ret.after_rx_commit = read64(ctx, PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_COMMIT(cid));

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

  struct sigaction new = {
    .sa_handler = signal_handler,
    .sa_flags = SA_RESETHAND, // only catch once
  };
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
  uint64_t cycles = read64(&ctx, PIONIC_GLOBAL_CYCLES);
  for (int i = 0; i < num_trials; ++i) {
    uint64_t new_cycles = read64(&ctx, PIONIC_GLOBAL_CYCLES);
    fprintf(out, "%ld\n", new_cycles - cycles);
    cycles = new_cycles;
  }
  fclose(out);

  // only use core 0
  pionic_set_core_mask(&ctx, 1);

  out = fopen("loopback.csv", "w");
  fprintf(out, "size,"
      "acquire_cyc,after_tx_commit_cyc,after_dma_read_cyc,exit_cyc,host_got_tx_buf_cyc,"
      "entry_cyc,after_rx_queue_cyc,after_dma_write_cyc,read_start_cyc,after_read_cyc,after_rx_commit_cyc,host_read_complete_cyc\n");

  // send packet and check rx data
  int min_pkt = 64, max_pkt = 9600, step = 64;

  for (int to_send = min_pkt; to_send <= max_pkt; to_send += step) {
    printf("Testing packet size %d", to_send);
    for (int i = 0; i < num_trials; ++i) {
      measure_t m = loopback_timed(&ctx, to_send, i * 64);
      fprintf(out, "%d,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u\n", to_send,
          m.acquire, m.after_tx_commit, m.after_dma_read, m.exit, m.host_got_tx_buf,
          m.entry, m.after_rx_queue, m.after_dma_write, m.read_start, m.after_read, m.after_rx_commit, m.host_read_complete);
      printf(".");
      fflush(stdout);
    }
    printf("\n");
  }

  fclose(out);

  ret = EXIT_SUCCESS;

fini:
  pionic_fini(&ctx);

fail:
  return ret;
}
