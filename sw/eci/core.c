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

#include "../../hw/gen/eci/config.h"
#include "../../hw/gen/eci/regs.h"
#include "common.h"

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
  void *nic_regs_region;
  void *mem_region;

  struct {
    bool rx_next_cl, tx_next_cl;

    // buffer to return a packet in a pionic_pkt_desc_t; these are pre-allocated
    // and reused
    // FIXME: this precludes passing packet in the registers and forces a copy
    uint8_t *rx_pkt_buf, *tx_pkt_buf;
  } core_states[PIONIC_NUM_CORES];

  int fpgamem_fd;

  uint32_t page_size;
};

// HAL functions that access regs region (over the ECI IO bridge)
void write64(pionic_ctx_t ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  printf("[Reg] WQ %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->nic_regs_region)[addr / 8] = reg;
}

uint64_t read64(pionic_ctx_t ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("[Reg] RQ %#lx -> ", addr);
  fflush(stdout);
#endif
  uint64_t reg = ((volatile uint64_t *)ctx->nic_regs_region)[addr / 8];
#ifdef DEBUG_REG
  printf("%#lx\n", reg);
#endif
  return reg;
}

void write32(pionic_ctx_t ctx, uint64_t addr, uint32_t reg) {
#ifdef DEBUG_REG
  printf("[Reg] WD %#lx <- %#x\n", addr, reg);
#endif
  ((volatile uint32_t *)ctx->nic_regs_region)[addr / 4] = reg;
}

uint32_t read32(pionic_ctx_t ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("[Reg] RD %#lx -> ", addr);
  fflush(stdout);
#endif
  uint32_t reg = ((volatile uint32_t *)ctx->nic_regs_region)[addr / 4];
#ifdef DEBUG_REG
  printf("%#x\n", reg);
#endif
  return reg;
}

static void write64_shell(pionic_ctx_t ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  printf("[Shell] WQ %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->shell_regs_region)[addr / 8] = reg;
}

static uint64_t read64_shell(pionic_ctx_t ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("[Shell] RQ %#lx -> ", addr);
  fflush(stdout);
#endif
  uint64_t reg = ((volatile uint64_t *)ctx->shell_regs_region)[addr / 8];
#ifdef DEBUG_REG
  printf("%#lx\n", reg);
#endif
  return reg;
}

static void cl_hit_inv(pionic_ctx_t ctx, uint64_t phys_addr) {
  // ioctl(ctx->fpgamem_fd, 4, phys_addr + (uint64_t)ctx->mem_region);
}

int pionic_init(pionic_ctx_t *usr_ctx, const char *dev, bool loopback) {
  int ret = -1;

  printf("Initializing ECI PIO NIC...\n");

  pionic_ctx_t ctx = *usr_ctx = malloc(sizeof(struct pionic_ctx));
  ctx->page_size = sysconf(_SC_PAGESIZE);
  ctx->nic_regs_region = MAP_FAILED;
  ctx->shell_regs_region = MAP_FAILED;
  ctx->mem_region = MAP_FAILED;

  int fd = open("/dev/mem", O_RDWR);
  if (fd < 0) {
    perror("open /dev/mem");
    goto fail;
  }

  printf("Opened /dev/mem\n");

  // map regs for nic engine
  ctx->nic_regs_region =
      mmap(NULL, PIONIC_REGS_SIZE(ctx), PROT_READ | PROT_WRITE, MAP_SHARED, fd,
           PIONIC_REGS_BASE);
  if (ctx->nic_regs_region == MAP_FAILED) {
    perror("mmap regs space");
    goto fail;
  }
  printf("Mapped NIC regs region with /dev/mem\n");

  // map regs for shell
  ctx->shell_regs_region = mmap(NULL, ctx->page_size, PROT_READ | PROT_WRITE,
                                MAP_SHARED, fd, SHELL_REGS_BASE);
  if (ctx->shell_regs_region == MAP_FAILED) {
    perror("mmap shell regs space");
    goto fail;
  }
  printf("Mapped shell regs region with /dev/mem\n");

  close(fd);

  // read out shell version number
  printf("Enzian shell version: %08lx\n",
         read64_shell(ctx, SHELL_REGS_VERSION_ADDR));

  // TODO: implement resync

  // read out NicEngine version number
  uint64_t ver = read64(ctx, PIONIC_GLOBAL_VERSION);
  uint64_t cur_time = read64(ctx, PIONIC_GLOBAL_CYCLES);
  printf("NicEngine version: %08lx, current timestamp %ld\n", ver, cur_time);

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
  pionic_set_core_mask(ctx, (1 << PIONIC_NUM_CORES) - 1);

  // verify
  assert(read64(ctx, PIONIC_GLOBAL_RX_BLOCK_CYCLES) == 200);

  // configure CMAC
  if (start_cmac(ctx, CMAC_BASE, loopback)) {
    printf("Failed to start CMAC\n");
    goto fail;
  }

  // initialize per-core states
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    // allocate user-facing buffers
    ctx->core_states[i].rx_pkt_buf = malloc(PIONIC_MTU);
    ctx->core_states[i].tx_pkt_buf = malloc(PIONIC_MTU);

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
    bool rx_next_cl = ctx->core_states[i].rx_next_cl =
        read64(ctx, PIONIC_CONTROL_RX_CURR_CL_IDX(i));
    bool tx_next_cl = ctx->core_states[i].tx_next_cl =
        read64(ctx, PIONIC_CONTROL_TX_CURR_CL_IDX(i));

    printf("rx curr cl idx for core %d: %d\n", i, rx_next_cl);
    printf("tx curr cl idx for core %d: %d\n", i, tx_next_cl);

    // drain all rx packets -- stale ones might be hanging around
    pionic_pkt_desc_t desc;
    bool has_left = true;
    int drained = 0;
    while ((has_left = pionic_rx(ctx, i, &desc))) {
      printf("stale packet: %ld B\n", desc.len);
      ++drained;
    }
    printf("Drained %d stale ingress packets on core %d\n", drained, i);

    // reset packet buffer allocator
    pionic_reset_pkt_alloc(ctx, i);
  }

  // make sure user TX does not get reordered into before the RX drain
  BARRIER

  ret = 0;

fail:
  return ret;
}

void pionic_set_rx_block_cycles(pionic_ctx_t ctx, int cycles) {
  write64(ctx, PIONIC_GLOBAL_RX_BLOCK_CYCLES, cycles);

  printf("Rx block cycles: %d\n", cycles);
}

void pionic_set_core_mask(pionic_ctx_t ctx, uint64_t mask) {
  write64(ctx, PIONIC_GLOBAL_DISPATCH_MASK, mask);

  printf("Dispatcher mask: %#lx\n", mask);
}

void pionic_fini(pionic_ctx_t *usr_ctx) {
  printf("Uninitializing ECI PIO NIC...\n");

  pionic_ctx_t ctx = *usr_ctx;

  *usr_ctx = NULL;

  close(ctx->fpgamem_fd);

  if (ctx->nic_regs_region != MAP_FAILED) {
    // disable CMAC
    stop_cmac(ctx, CMAC_BASE);

    munmap(ctx->nic_regs_region, PIONIC_REGS_SIZE(ctx));
  }

  if (ctx->mem_region != MAP_FAILED)
    munmap(ctx->mem_region, FPGA_MEM_SIZE);

  if (ctx->shell_regs_region != MAP_FAILED)
    munmap(ctx->shell_regs_region, ctx->page_size);

  // free rx buffers
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    free(ctx->core_states[i].rx_pkt_buf);
  }

  free(ctx);
}

static void write64_fpgamem(pionic_ctx_t ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  printf("[Mem] W %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->mem_region)[addr / 8] = reg;
}

static uint64_t read64_fpgamem(pionic_ctx_t ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("[Mem] R %#lx -> ", addr);
  fflush(stdout);
#endif
  uint64_t reg = ((volatile uint64_t *)ctx->mem_region)[addr / 8];
#ifdef DEBUG_REG
  printf("%#lx\n", reg);
#endif
  return reg;
}

static void copy_from_fpgamem(pionic_ctx_t ctx, uint64_t addr, void *dest,
                              size_t len) {
#ifdef DEBUG_REG
  printf("[Mem] RM %#lx ->\n", addr);
#endif
  memcpy(dest, (uint8_t *)ctx->mem_region + addr, len);
#ifdef DEBUG_REG
  hexdump(dest, len);
#endif
}

static void copy_to_fpgamem(pionic_ctx_t ctx, uint64_t addr, void *src,
                            size_t len) {
#ifdef DEBUG_REG
  printf("[Mem] WM %#lx <-\n", addr);
  hexdump(src, len);
#endif
  memcpy((uint8_t *)ctx->mem_region + addr, src, len);
}

bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  // we have to read the first word in the CL first
  // FIXME: should we read the entire 64B of control information?
  uint64_t rx_base = PIONIC_ECI_RX_BASE + cid * PIONIC_ECI_CORE_OFFSET;
  bool *next_cl = &ctx->core_states[cid].rx_next_cl;

#ifdef DEBUG
  printf("pionic_rx: next cacheline ID: %d\n", *next_cl);
#endif

  // make sure TX actually took effect before e.g. we attempt to RX
  BARRIER

  uint64_t ctrl = read64_fpgamem(ctx, *next_cl * 0x80 + rx_base);

  // make sure that we don't read data before we actually get the control
  BARRIER

  uint64_t inline_data_addr = *next_cl * 0x80 + 0x40 + rx_base;

  // always toggle CL
  *next_cl ^= true;

  if (ctrl & 0x1) {
    uint64_t hw_desc = ctrl >> 1;
    uint64_t pkt_len = hw_desc & PIONIC_PKT_LEN_MASK;

    desc->buf = ctx->core_states[cid].rx_pkt_buf;
    desc->len = pkt_len;

    // copy to prepared buffer
    int first_read_size = pkt_len > 64 ? 64 : pkt_len;
    copy_from_fpgamem(ctx, inline_data_addr, desc->buf, first_read_size);
    if (pkt_len > 64) {
      copy_from_fpgamem(ctx, rx_base + PIONIC_ECI_OVERFLOW_OFFSET,
                        desc->buf + 64, pkt_len - 64);
    }

    // make sure reading of the next ctrl cl does not get reordered before the
    // overflow reads
    BARRIER

#ifdef DEBUG
    printf("Got packet len %#lx\n", pkt_len);
#endif
    return true;
  } else {
#ifdef DEBUG
    printf("Did not get packet\n");
#endif
    return false;
  }
}

void pionic_rx_ack(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  // Option 1:
  // since reading the same ctrl cacheline is idempotent, we can acknowledge
  // this packet with a dummy read; when the user is ready, they will call
  // pionic_rx (already in cache)
  // XXX: in the worst case this will block the bus for rxBlockCycles (when no
  //      packet is available)
  //
  // Option 2:
  // just do nothing; CPU shouldn't do much between ack and rx of next packet
  // XXX: inaccurate "end of processing" timestamp

  /*
  uint64_t rx_base = PIONIC_ECI_RX_BASE + cid * PIONIC_ECI_CORE_OFFSET;
  bool *next_cl = &ctx->core_states[cid].rx_next_cl;
  read64_fpgamem(ctx, *next_cl * 0x80 + rx_base);
  */
}

void pionic_tx_get_desc(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  desc->buf = ctx->core_states[cid].tx_pkt_buf;
  desc->len = PIONIC_MTU;
}

void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  uint64_t tx_base = PIONIC_ECI_TX_BASE + cid * PIONIC_ECI_CORE_OFFSET;
  bool *next_cl = &ctx->core_states[cid].tx_next_cl;

#ifdef DEBUG
  printf("pionic_tx: next cacheline ID: %d\n", *next_cl);
#endif

  uint64_t pkt_len = desc->len;

  // write packet length to control
  write64_fpgamem(ctx, *next_cl * 0x80 + tx_base, pkt_len);

  // copy from prepared buffer
  int first_write_size = pkt_len > 64 ? 64 : pkt_len;
  copy_to_fpgamem(ctx, *next_cl * 0x80 + 0x40 + tx_base, desc->buf,
                  first_write_size);
  if (pkt_len > 64) {
    copy_to_fpgamem(ctx, tx_base + PIONIC_ECI_OVERFLOW_OFFSET, desc->buf + 64,
                    pkt_len - 64);
  }

  *next_cl ^= true;

  // make sure packet data actually hit L2, before the FPGA invalidates
  BARRIER

  // trigger actual sending by doing a dummy read on the next cacheline
  read64_fpgamem(ctx, *next_cl * 0x80 + tx_base);

  // make sure TX actually took effect before e.g. we attempt to RX
  // make sure next_cl tracking is not out of sync due to reordering
  BARRIER
}
