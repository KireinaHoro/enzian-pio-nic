#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

#include "api.h"
#include "hal.h"
#include "cmac.h"
#include "debug.h"

#include "../../hw/gen/eci/regs.h"
#include "../../hw/gen/eci/config.h"

#define CMAC_BASE 0x200000UL

#define SHELL_REGS_BASE (0x900000000000UL)
#define SHELL_REGS_SIZE (1UL << 44)
#define SHELL_VERSION_ADDR (0x7effffffff8)

#define FPGA_MEM_BASE (0x10000000000UL)
#define FPGA_MEM_SIZE (1UL << 30) // 1 TiB

struct pionic_ctx {
  void *regs_region;
  void *mem_region;

  struct {
    bool rx_next_cl, tx_next_cl;

    // buffer to return a packet in a pionic_pkt_desc_t; these are pre-allocated
    // and reused
    // FIXME: this precludes passing packet in the registers and forces a copy
    uint8_t *rx_pkt_buf, *tx_pkt_buf;
  } core_states[PIONIC_NUM_CORES];
};

// HAL functions that access regs_region (over the ECI IO bridge)
void write64(pionic_ctx_t ctx, uint64_t addr, uint64_t reg) {
#ifdef DEBUG_REG
  printf("[Reg] WQ %#lx <- %#lx\n", addr, reg);
#endif
  ((volatile uint64_t *)ctx->regs_region)[addr / 8] = reg;
}

uint64_t read64(pionic_ctx_t ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("[Reg] RQ %#lx -> ", addr);
  fflush(stdout);
#endif
  uint64_t reg = ((volatile uint64_t *)ctx->regs_region)[addr / 8];
#ifdef DEBUG_REG
  printf("%#lx\n", reg);
#endif
  return reg;
}

void write32(pionic_ctx_t ctx, uint64_t addr, uint32_t reg) {
#ifdef DEBUG_REG
  printf("[Reg] WD %#lx <- %#x\n", addr, reg);
#endif
  ((volatile uint32_t *)ctx->regs_region)[addr / 4] = reg;
}

uint32_t read32(pionic_ctx_t ctx, uint64_t addr) {
#ifdef DEBUG_REG
  printf("[Reg] RD %#lx -> ", addr);
  fflush(stdout);
#endif
  uint32_t reg = ((volatile uint32_t *)ctx->regs_region)[addr / 4];
#ifdef DEBUG_REG
  printf("%#x\n", reg);
#endif
  return reg;
}

int pionic_init(pionic_ctx_t *usr_ctx, const char *dev, bool loopback) {
  int ret = -1;

  pionic_ctx_t ctx = *usr_ctx = malloc(sizeof(struct pionic_ctx));

  int fd = open("/dev/mem", O_RDWR);
  if (fd < 0) {
    perror("open /dev/mem");
    goto fail;
  }

  // map the entire regs region (44 bits)
  ctx->regs_region = mmap(NULL, SHELL_REGS_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, SHELL_REGS_BASE);
  if (ctx->regs_region == MAP_FAILED) {
    perror("mmap regs space");
    goto fail;
  }

  close(fd);

  // read out shell version number
  printf("Enzian shell version: %08x\n", read32(ctx, SHELL_VERSION_ADDR));

  fd = open("/dev/fpgamem", O_RDWR);
  if (fd < 0) {
    perror("open /dev/fpgamem");
    goto fail;
  }

  // map the entire fpga memory region (1 TiB)
  ctx->mem_region = mmap(NULL, FPGA_MEM_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, FPGA_MEM_BASE);
  if (ctx->mem_region == MAP_FAILED) {
    perror("mmap fpga mem space");
    goto fail;
  }

  close(fd);

  // configure CMAC
  start_cmac(ctx, CMAC_BASE, loopback);

  // set defaults
  pionic_set_rx_block_cycles(ctx, 200);
  pionic_set_core_mask(ctx, (1 << PIONIC_NUM_CORES) - 1);

  // reset packet buffer allocator
  for (int i = 0; i < PIONIC_NUM_CORES; ++i) {
    pionic_reset_pkt_alloc(ctx, i);

    // reset next CL counter
    ctx->core_states[i].rx_next_cl = 0;
    ctx->core_states[i].tx_next_cl = 0;

    // allocate user-facing buffers
    ctx->core_states[i].rx_pkt_buf = malloc(PIONIC_MTU);
    ctx->core_states[i].tx_pkt_buf = malloc(PIONIC_MTU);
  }

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
  pionic_ctx_t ctx = *usr_ctx;

  *usr_ctx = NULL;

  // disable CMAC
  stop_cmac(ctx, CMAC_BASE);

  munmap(ctx->mem_region, FPGA_MEM_SIZE);
  munmap(ctx->regs_region, SHELL_REGS_SIZE);

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

static void copy_from_fpgamem(pionic_ctx_t ctx, uint64_t addr, void *dest, size_t len) {
  memcpy(dest, (uint8_t *)ctx->mem_region + addr, len);
}

static void copy_to_fpgamem(pionic_ctx_t ctx, uint64_t addr, void *src, size_t len) {
  memcpy((uint8_t *)ctx->mem_region + addr, src, len);
}

bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc) {
  // we have to read the first word in the CL first
  // FIXME: should we read the entire 64B of control information?
  uint64_t rx_base = PIONIC_ECI_RX_BASE + cid * PIONIC_ECI_CORE_OFFSET;
  bool *next_cl = &ctx->core_states[cid].rx_next_cl;
  uint64_t ctrl = read64_fpgamem(ctx, *next_cl * 0x80 + rx_base);

  // always toggle CL
  *next_cl ^= true;

  if (ctrl & 0x1) {
    uint64_t hw_desc = ctrl >> 1;
    uint64_t pkt_len = hw_desc & PIONIC_PKT_LEN_MASK;

    desc->buf = ctx->core_states[cid].rx_pkt_buf;
    desc->len = pkt_len;

    // copy to prepared buffer
    int first_read_size = pkt_len > 64 ? 64 : pkt_len;
    copy_from_fpgamem(ctx, *next_cl * 0x80 + 0x40 + rx_base, desc->buf, first_read_size);
    if (pkt_len > 64) {
      copy_from_fpgamem(ctx, rx_base + PIONIC_ECI_RX_OVERFLOW, desc->buf + 64,
          pkt_len - 64);
    }

#ifdef DEBUG
    printf("Got packet at pktbuf %#lx len %#lx\n", PIONIC_ADDR_TO_PKTBUF_OFF(read_addr), pkt_len);
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
  uint64_t pkt_len = desc->len;

  // write packet length to control
  write64_fpgamem(ctx, *next_cl * 0x80 + tx_base, pkt_len);

  // copy from prepared buffer
  int first_write_size = pkt_len > 64 ? 64 : pkt_len;
  copy_to_fpgamem(ctx, *next_cl * 0x80 + 0x40 + tx_base, desc->buf, first_write_size);
  if (pkt_len > 64) {
    copy_to_fpgamem(ctx, tx_base + PIONIC_ECI_TX_OVERFLOW, desc->buf + 64,
        pkt_len - 64);
  }

  *next_cl ^= true;

  // trigger actual sending by doing a dummy read on the next cacheline
  read64_fpgamem(ctx, *next_cl * 0x80 + tx_base);
}