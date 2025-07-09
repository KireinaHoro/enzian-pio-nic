#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "api.h"
#include "common.h"
#include "diag.h"
#include "profile.h"

// TODO: send other protocol headers
static uint8_t ethernet_hdr[] = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0xaa,
                                 0xbb, 0xcc, 0xdd, 0xee, 0xff, 0xde, 0xad};

typedef struct {
  uint32_t host_got_tx_buf;
  uint32_t host_read_complete;

  pionic_core_ctrl_timestamps_t ts;
} measure_t;

static measure_t loopback_timed(pionic_ctx_t ctx, pionic_pkt_desc_t *desc,
                                uint8_t *tx_buf, uint32_t length) {
  int cid = 0;

  pionic_tx_prepare_desc(ctx, cid, desc);

  // fill in Ethernet header
  desc->type = TY_BYPASS;
  desc->bypass.header_type = HDR_ETHERNET;
  memcpy(desc->bypass.header, ethernet_hdr, sizeof(ethernet_hdr));

  char rx_buf[length];

  measure_t ret = {0};

  // Tx path is instrumented with hardware timestamps.
  // However, Acquire is not reliable: it fires on read, but sits in the same
  // 512B as other registers, so a read on those would also trigger Acquire. Use
  // a host-side timestamp as substitute.
  ret.host_got_tx_buf = pionic_get_cycles(ctx);

  if (length > 0) {
    // TODO: check desc->payload_len is enough?
    memcpy(desc->payload_buf, tx_buf, length);
    desc->payload_len = length;
    pionic_tx(ctx, cid, desc);
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
  // In addition, we take one timestamp HostReadCompleted when we
  // received the packet on the CPU (into the registers).  This includes the
  // memcpy from the NIC into the CPU registers.
  //
  // XXX: we hope that we get to issue the read before the packet actually come
  // back
  //      from loopback.  In this case we have the following timestamp sequence:
  //
  //      ReadStart, Entry, AfterRxQueue, AfterDmaWrite, AfterRead,
  //      HostReadCompleted, AfterCommit
  //
  //      In this setup we would not bloat the latency artificially (due to
  //      arbitrary latency between AfterDmaWrite and ReadStart, as the host is
  //      not yet ready).
  //
  // Taking HostReadCompleted on the CPU requires one bus round-trip, resulting
  // in a measurement too late by half the RTT.  This is measured beforehand and
  // subtracted during actual interval calculation.

  bool got_pkt = pionic_rx(ctx, cid, desc);

  if (length > 0) {
    assert(got_pkt && "failed to receive packet");
    assert(desc->type == TY_BYPASS && "received type not BYPASS");
    // TODO: check for other protocol types
    assert(desc->bypass.header_type == HDR_ETHERNET &&
           "received bypass header type not ethernet");

    // check rx match with tx
#ifdef DEBUG
    printf("rx payload len: %ld; tx payload len: %d\n", desc->payload_len,
           length);
    printf("Reading out packet @ %p, %ld bytes\n", desc->payload_buf,
           desc->payload_len);
#endif
    assert(desc->payload_len == length && "rx packet length does not match tx");
    memcpy(rx_buf, desc->payload_buf, desc->payload_len);

    ret.host_read_complete = pionic_get_cycles(ctx);

    pionic_rx_ack(ctx, cid, desc);
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

  pionic_read_timestamps_core_ctrl(ctx, cid, &ret.ts);

  return ret;
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

  pionic_dump_core_stats(ctx, 0);

  // estimate Rx timeout
  // pionic_probe_rx_block_cycles(ctx);

  // 20 ms
  pionic_set_rx_block_cycles(ctx, pionic_us_to_cycles(20 * 1000));

  // estimate pcie roundtrip time
  FILE *out = fopen("pcie_lat.csv", "w");
  fprintf(out, "pcie_lat_cyc\n");
  int num_trials = 50;
  uint64_t cycles = pionic_get_cycles(ctx);
  for (int i = 0; i < num_trials; ++i) {
    uint64_t new_cycles = pionic_get_cycles(ctx);
    fprintf(out, "%ld\n", new_cycles - cycles);
    cycles = new_cycles;
  }
  fclose(out);

  // enable promisc mode
  pionic_set_promisc(ctx, true);

  out = fopen("loopback.csv", "w");
  fprintf(out, "size,"
               "acquire_cyc,after_tx_commit_cyc,after_dma_read_cyc,exit_cyc,"
               "host_got_tx_buf_cyc,"
               "entry_cyc,after_rx_queue_cyc,after_dma_write_cyc,read_start_"
               "cyc,after_read_cyc,"
               "after_rx_commit_cyc,host_read_complete_cyc\n");

  // send packet and check rx data
  int min_pkt = 64, max_pkt = 9600, step = 64;

  // allocate packet descriptor once
  pionic_pkt_desc_t *desc = pionic_alloc_pkt_desc();
  assert(!desc);

  for (int to_send = min_pkt; to_send <= max_pkt; to_send += step) {
    printf("Testing packet size %d", to_send);
    uint8_t *test_data = malloc(to_send);
    for (int i = 0; i < to_send; ++i) {
      test_data[i] = rand();
    }

    for (int i = 0; i < num_trials; ++i) {
      measure_t m = loopback_timed(ctx, desc, test_data, to_send);
      fprintf(out, "%d,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u\n", to_send,
              m.ts.acquire, m.ts.after_tx_commit, m.ts.after_dma_read,
              m.ts.exit, m.host_got_tx_buf, m.ts.entry, m.ts.after_rx_queue,
              m.ts.after_dma_write, m.ts.read_start, m.ts.after_read,
              m.ts.after_rx_commit, m.host_read_complete);
      printf(".");
      fflush(stdout);
    }
    printf("\n");
  }

  fclose(out);
  pionic_free_pkt_desc(desc);

  ret = EXIT_SUCCESS;

fini:
  pionic_fini(&ctx);

fail:
  return ret;
}
