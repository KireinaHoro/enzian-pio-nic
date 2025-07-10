#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "api.h"
#include "cmac.h"
#include "diag.h"
#include "hal.h"

#include "config.h"
#include "debug.h"
#include "regblock_bases.h"

#include "gen/cmac.h"
#include "gen/pionic_eci_core.h"
#include "gen/pionic_eci_global.h"

#define CMAC_BASE 0x200000UL

#define PIONIC_REGS_BASE (0x900000000000UL)
#define PIONIC_REGS_SIZE(ctx) (0x300000)

#define SHELL_REGS_BASE (0x97EFFFFFF000UL)
#define SHELL_REGS_CSR_ADDR (0xff0)
#define SHELL_REGS_VERSION_ADDR (0xff8)

#define FPGA_MEM_BASE (0x10000000000UL)
#define FPGA_MEM_SIZE (1UL << 40) // 1 TiB

#define BARRIER asm volatile("dmb sy\nisb");

struct pionic_ctx {
  void *shell_regs_region;

  pionic_eci_global_t global;
  cmac_t cmac;

  pionic_core_t core[PIONIC_NUM_CORES];

  void *mem_region;
  int fpgamem_fd;

  uint32_t page_size;
};

#ifndef __KERNEL__

#include "core-eci.h"

// Core state structs are not mmapped but declared statically
static pionic_core_state_t core_states[PIONIC_NUM_CORES];

#endif

static void write64_shell(pionic_ctx_t ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  pr_debug("[Shell] WQ %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->shell_regs_region)[addr / 8] = reg;
}

static uint64_t read64_shell(pionic_ctx_t ctx, uint64_t addr) {
#ifdef DEBUG_REG
  pr_debug("[Shell] RQ %#lx -> ", addr);
  pr_flush();
#endif
  uint64_t reg = ((volatile uint64_t *)ctx->shell_regs_region)[addr / 8];
#ifdef DEBUG_REG
  pr_debug("%#lx\n", reg);
#endif
  return reg;
}

static void cl_hit_inv(pionic_ctx_t ctx, uint64_t phys_addr) {
  // ioctl(ctx->fpgamem_fd, 4, phys_addr + (uint64_t)ctx->mem_region);
}

int pionic_init(pionic_ctx_t *usr_ctx, const char *dev, bool loopback) {
  int ret = -1;

  pr_info("Initializing ECI PIO NIC...\n");

  pionic_ctx_t ctx = *usr_ctx = malloc(sizeof(struct pionic_ctx));
  ctx->page_size = sysconf(_SC_PAGESIZE);
  ctx->shell_regs_region = MAP_FAILED;
  ctx->global.base = MAP_FAILED;
  ctx->mem_region = MAP_FAILED;

  int fd = open("/dev/mem", O_RDWR);
  if (fd < 0) {
    perror("open /dev/mem");
    goto fail;
  }

  pr_info("Opened /dev/mem\n");

  // map regs for nic engine
  void *nic_regs_region =
      mmap(NULL, PIONIC_REGS_SIZE(ctx), PROT_READ | PROT_WRITE, MAP_SHARED, fd,
           PIONIC_REGS_BASE);
  if (nic_regs_region == MAP_FAILED) {
    perror("mmap regs space");
    goto fail;
  }
  pionic_eci_global_initialize(&ctx->global, nic_regs_region);
  pr_info("Mapped NIC regs region with /dev/mem\n");

  // map regs for shell
  ctx->shell_regs_region = mmap(NULL, ctx->page_size, PROT_READ | PROT_WRITE,
                                MAP_SHARED, fd, SHELL_REGS_BASE);
  if (ctx->shell_regs_region == MAP_FAILED) {
    perror("mmap shell regs space");
    goto fail;
  }
  pr_info("Mapped shell regs region with /dev/mem\n");

  close(fd);

  // read out shell version number
  pr_info("Enzian shell version: %08lx\n",
          read64_shell(ctx, SHELL_REGS_VERSION_ADDR));

  // TODO: implement resync

  // read out NicEngine version number
  uint64_t ver = pionic_eci_global_version_rd(&ctx->global);
  uint64_t cur_time = pionic_eci_global_cycles_rd(&ctx->global);
  pr_info("NicEngine version: %08lx, current timestamp %ld\n", ver, cur_time);

  fd = ctx->fpgamem_fd = open("/dev/fpgamem", O_RDWR);
  if (fd < 0) {
    perror("open /dev/fpgamem");
    goto fail;
  }

  // map the entire fpga memory region (1 TiB)
  ctx->mem_region = mmap((void *)FPGA_MEM_BASE, FPGA_MEM_SIZE,
                         PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (ctx->mem_region == MAP_FAILED) {
    perror("mmap fpga mem space");
    goto fail;
  }

  // set defaults
  pionic_set_rx_block_cycles(ctx, 200);
  // pionic_set_core_mask(ctx, (1 << PIONIC_NUM_CORES) - 1);
  // FIXME: @PX, what else to initialize

  // verify
  assert(pionic_get_rx_block_cycles(ctx) == 200);

  // configure CMAC
  cmac_initialize(&ctx->cmac, nic_regs_region + CMAC_BASE);
  if (start_cmac(&ctx->cmac, loopback)) {
    pr_err("Failed to start CMAC\n");
    goto fail;
  }

  // initialize per-core states
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    pionic_eci_core_initialize(&ctx->core[i],
                               nic_regs_region + PIONIC_ECI_CORE_BASE(i));

    // clean all cachelines
    // we need to do this first, since inv might change tx cl idx
    uint64_t rx_base = PIONIC_ECI_RX_BASE + i * PIONIC_ECI_CORE_OFFSET;
    uint64_t tx_base = PIONIC_ECI_TX_BASE + i * PIONIC_ECI_CORE_OFFSET;

    for (int next_cl = 0; next_cl < 2; ++next_cl) {
      cl_hit_inv(ctx, rx_base + 0x80 * next_cl);
      cl_hit_inv(ctx, tx_base + 0x80 * next_cl);
    }

    for (int overflow_cl = 0; overflow_cl < PIONIC_ECI_NUM_OVERFLOW_CL;
         ++overflow_cl) {
      cl_hit_inv(ctx,
                 rx_base + PIONIC_ECI_OVERFLOW_OFFSET + 0x80 * overflow_cl);
      cl_hit_inv(ctx,
                 tx_base + PIONIC_ECI_OVERFLOW_OFFSET + 0x80 * overflow_cl);
    }

    // read out next CL counters
    pionic_sync_core_state(&core_states[i], &ctx->core[i]);
    pr_info("rx curr cl idx for core %d: %d\n", i, core_states[i].rx_next_cl);
    pr_info("tx curr cl idx for core %d: %d\n", i, core_states[i].tx_next_cl);

    // drain all rx packets -- stale ones might be hanging around
    pionic_pkt_desc_t desc;
    bool has_left = true;
    int drained = 0;
    while ((has_left = pionic_rx(ctx, i, &desc))) {
      printf("stale packet: desc + %ld B\n", desc.payload_len);
      ++drained;
    }
    printf("Drained %d stale ingress packets on core %d\n", drained, i);

    // reset packet buffer allocator
    pionic_reset_pkt_alloc(&ctx->core[i]);
  }

  // make sure user TX does not get reordered into before the RX drain
  BARRIER

  ret = 0;

fail:
  return ret;
}

void pionic_set_rx_block_cycles(pionic_ctx_t ctx, uint64_t cycles) {
  pionic_eci_global_rx_block_cycles_wr(&ctx->global, cycles)
      pr_debug("Rx block cycles: %d\n", cycles);
}

uint64_t pionic_get_rx_block_cycles(pionic_ctx_t ctx) {
  return pionic_eci_global_rx_block_cycles_rd(&ctx->global);
}

// void pionic_set_dispatch_mask(pionic_ctx_t ctx, uint64_t mask) {
//   write64(ctx, PIONIC_GLOBAL_DISPATCH_MASK, mask);
//   printf("Dispatcher mask: %#lx\n", mask);
// }

void pionic_fini(pionic_ctx_t *usr_ctx) {
  pr_info("Uninitializing ECI PIO NIC...\n");

  pionic_ctx_t ctx = *usr_ctx;

  *usr_ctx = NULL;

  close(ctx->fpgamem_fd);

  if (ctx->global.base != MAP_FAILED) {
    stop_cmac(&ctx->cmac);
    munmap(ctx->global.base, PIONIC_REGS_SIZE(ctx));
  }

  if (ctx->mem_region != MAP_FAILED)
    munmap(ctx->mem_region, FPGA_MEM_SIZE);

  if (ctx->shell_regs_region != MAP_FAILED)
    munmap(ctx->shell_regs_region, ctx->page_size);

  free(ctx);
}

void pionic_sync_core_state(pionic_core_state_t *state, pionic_core_t *core) {
  state->rx_next_cl = pionic_eci_core_rx_curr_cl_idx_rd(core) ? 1 : 0;
  state->tx_next_cl = pionic_eci_core_tx_curr_cl_idx_rd(core) ? 1 : 0;
}

bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  return core_eci_rx((uint8_t *)ctx->mem_region + cid * PIONIC_ECI_CORE_OFFSET,
                     (volatile bool *)&core_states[cid].rx_next_cl, desc);
}

void pionic_rx_ack(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  (void)ctx;
  (void)cid;
  core_eci_rx_ack(desc);
}

void pionic_tx_prepare_desc(pionic_ctx_t ctx, int cid,
                            pionic_pkt_desc_t *desc) {
  (void)ctx;
  (void)cid;
  core_eci_tx_prepare_desc(desc);
}

void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  core_eci_tx((uint8_t *)ctx->mem_region + cid * PIONIC_ECI_CORE_OFFSET,
              (volatile bool *)&core_states[cid].tx_next_cl, desc);
}
