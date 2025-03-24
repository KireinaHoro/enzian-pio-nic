#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "api.h"
#include "cmac.h"
#include "diag.h"
#include "hal.h"

#include "core-pcie.h"

#ifdef __KERNEL__
#error "PCIe rt is not ready for the kernel module
#endif

struct pionic_ctx {
  void *bar;
  pionic_pcie_global_t glb_dev;
  pionic_pcie_core_t core_dev[PIONIC_NUM_CORES];
};

#define PIONIC_CMAC_BASE 0x200000UL
#define PIONIC_MMAP_END 0x300000UL

int pionic_init(pionic_ctx_t *usr_ctx, const char *dev, bool loopback) {
  int ret = -1;

  pionic_ctx_t ctx = *usr_ctx = malloc(sizeof(struct pionic_ctx));

  char bar_path[64];
  snprintf(bar_path, sizeof(bar_path), "/sys/bus/pci/devices/%s/resource0",
           dev);

  int fd = open(bar_path, O_RDWR);
  if (fd < 0) {
    perror("open resource");
    goto fail;
  }

  ctx->bar =
      mmap(NULL, PIONIC_MMAP_END, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (ctx->bar == MAP_FAILED) {
    perror("mmap resource");
    goto fail_mmap;
  }

  close(fd);

  // initialize Mackerel devices
  pionic_pcie_global_initialize(&ctx->glb_dev,
                                ctx->bar + PIONIC_PCIE_GLOBAL_BASE);
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    pionic_pcie_core_initialize(&ctx->core_dev[i],
                                ctx->bar + PIONIC_PCIE_CORE_BASE(i));
  }

  // enable device
  char enable_path[64];
  snprintf(enable_path, sizeof(enable_path), "/sys/bus/pci/devices/%s/enable",
           dev);
  FILE *fp = fopen(enable_path, "w");
  if (!fp) {
    perror("open pcie enable");
    goto fail_enable;
  }
  fputc('1', fp);
  fclose(fp);

  // configure CMAC
  if (start_cmac(ctx, PIONIC_CMAC_BASE, loopback)) {
    printf("Failed to start CMAC\n");
    goto fail_start_cmac;
  }

  // set defaults
  pionic_set_rx_block_cycles(ctx, 200);
  pionic_oncrpc_set_core_mask(ctx, (1 << PIONIC_NUM_CORES) - 1);

  // reset packet buffer allocator
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    pionic_reset_pkt_alloc(ctx, i);
  }

  ret = 0;
  return ret;

// error handling
  stop_cmac(ctx, PIONIC_CMAC_BASE);
fail_start_cmac:
fail_enable:
  munmap(ctx->bar, PIONIC_MMAP_END);
fail_mmap:
  return ret;
}

void pionic_set_rx_block_cycles(pionic_ctx_t ctx, int cycles) {
  pionic_pcie_global_rx_block_cycles_wr(&ctx->glb_dev, cycles);

  printf("Rx block cycles: %d\n", cycles);
}

void pionic_fini(pionic_ctx_t *usr_ctx) {
  pionic_ctx_t ctx = *usr_ctx;

  // disable CMAC
  stop_cmac(ctx, PIONIC_CMAC_BASE);

  munmap(ctx->bar, PIONIC_MMAP_END);

  *usr_ctx = NULL;
}

bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  return core_pcie_rx(ctx->bar, &ctx->core_dev[cid], desc);
}

void pionic_rx_ack(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  core_pcie_rx_ack(ctx->bar, &ctx->core_dev[cid], desc);
}

void pionic_tx_prepare_desc(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  core_pcie_tx_prepare_desc(ctx->bar, &ctx->core_dev[cid], desc);
}

void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  core_pcie_tx(ctx->bar, &ctx->core_dev[cid], desc);
}
