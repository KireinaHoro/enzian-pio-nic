#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>
#include <assert.h>
#include <string.h>

#include "common.h"
#include "api.h"
#include "diag.h"
#include "profile.h"

#define BARRIER asm volatile ("dmb sy\nisb");

typedef struct {
  uint32_t host_got_tx_buf;
  uint32_t host_read_complete;

  pionic_core_ctrl_timestamps_t ts;
} measure_t;

static void rand_fill(uint8_t *buf, size_t len) {
  for (int i = 0; i < len; ++i) {
    buf[i] = rand();
  }
}

static uint8_t *rx_buf, *tx_buf;

static measure_t loopback_timed(pionic_ctx_t ctx, uint32_t length, uint32_t offset) {
  int cid = 0;

  pionic_pkt_desc_t desc;
  pionic_tx_get_desc(ctx, cid, &desc);
  // printf("Tx buffer at %p, len %ld; sending %d B\n", desc.buf, desc.len, length);

  // make sure we don't read past the end of the tx buffer
  assert(length <= pionic_get_mtu());

  // randomize packet contents
  rand_fill(tx_buf, length);

  measure_t ret = { 0 };

  // Tx path is instrumented with hardware timestamps.
  // However, Acquire is not reliable: it fires on read, but sits in the same 512B
  // as other registers, so a read on those would also trigger Acquire.
  // Use a host-side timestamp as substitute.
  ret.host_got_tx_buf = pionic_get_cycles(ctx);

  if (length > 0) {
    memcpy(desc.buf, tx_buf, length);
    desc.len = length;
    pionic_tx(ctx, cid, &desc);
  }

  BARRIER

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

  int tries = 10;
  bool got_pkt;
  while (!(got_pkt = pionic_rx(ctx, cid, &desc)) && (--tries > 0)) {
    // printf("Didn't get packet, %d tries left...\n", tries);
    printf("~");
    fflush(stdout);
  }

  if (length > 0) {
    assert(got_pkt && "failed to receive packet");

    // check rx match with tx
#ifdef DEBUG
    printf("rx packet len: %ld; tx packet len: %d\n", desc.len, length);
    printf("Reading out packet @ %p, %ld bytes\n", desc.buf, desc.len);
#endif
    assert(desc.len == length && "rx packet length does not match tx");
    memcpy(rx_buf, desc.buf, desc.len);

    ret.host_read_complete = pionic_get_cycles(ctx);

    pionic_rx_ack(ctx, cid, &desc);
  } else {
    assert(!got_pkt && "got packet when not expecting one");
  }


  if (length > 0 && memcmp(rx_buf, tx_buf, length)) {
    printf("FAIL: data mismatch for length %d!  Expected (tx):\n", length);
    hexdump(tx_buf, length);
    printf("Rx:\n");
    hexdump(rx_buf, length);
    assert(false);
  }

  pionic_read_timestamps_core_ctrl(ctx, cid, &ret.ts);

  return ret;
}

int main(int argc, char *argv[]) {
  int ret = EXIT_FAILURE;
  if (argc != 1) {
    fprintf(stderr, "usage: %s\n", argv[0]);
    goto fail;
  }

  rx_buf = malloc(pionic_get_mtu());
  tx_buf = malloc(pionic_get_mtu());

  pionic_ctx_t ctx;
  if (pionic_init(&ctx, NULL, true) < 0) {
    goto fini;
  }

  pionic_dump_core_stats(ctx, 0);

  // estimate Rx timeout
  // pionic_probe_rx_block_cycles(ctx);
  // exit(EXIT_SUCCESS);

  // 20 ms
  pionic_set_rx_block_cycles(ctx, pionic_us_to_cycles(20 * 1000));

  // estimate eci I/O roundtrip time
  FILE *out = fopen("eci_lat.csv", "w");
  fprintf(out, "eci_lat_cyc\n");
  int num_trials = 50;
  uint64_t cycles = pionic_get_cycles(ctx);
  for (int i = 0; i < num_trials; ++i) {
    uint64_t new_cycles = pionic_get_cycles(ctx);
    fprintf(out, "%ld\n", new_cycles - cycles);
    cycles = new_cycles;
  }
  fclose(out);

  // only use core 0
  pionic_set_core_mask(ctx, 1);

  out = fopen("loopback.csv", "w");
  fprintf(out, "size,"
      "acquire_cyc,after_tx_commit_cyc,after_dma_read_cyc,exit_cyc,host_got_tx_buf_cyc,"
      "entry_cyc,after_rx_queue_cyc,after_dma_write_cyc,read_start_cyc,after_read_cyc,"
      "after_rx_commit_cyc,host_read_complete_cyc\n");

  // send packet and check rx data
  int min_pkt = 64, max_pkt = 9600, step = 64;

  for (int to_send = min_pkt; to_send <= max_pkt; to_send += step) {
    printf("Testing packet size %d", to_send);
    for (int i = 0; i < num_trials; ++i) {
      measure_t m = loopback_timed(ctx, to_send, i * 64);
      fprintf(out, "%d,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u\n", to_send,
          m.ts.acquire, m.ts.after_tx_commit, m.ts.after_dma_read, m.ts.exit,
          m.host_got_tx_buf, m.ts.entry, m.ts.after_rx_queue, m.ts.after_dma_write,
          m.ts.read_start, m.ts.after_read, m.ts.after_rx_commit, m.host_read_complete);
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
