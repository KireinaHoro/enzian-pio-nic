#ifndef __LAUBERHORN_ECI_REGS_H__
#define __LAUBERHORN_ECI_REGS_H__
#define LAUBERHORN_ECI_MAC_IF_BASE 0x0

#define LAUBERHORN_ECI_DECODER_SINK_BASE 0x100

#define LAUBERHORN_ECI__ETHERNET_DECODER_BASE 0x200

#define LAUBERHORN_ECI__IP_DECODER_BASE 0x300

#define LAUBERHORN_ECI__UDP_DECODER_BASE 0x400

#define LAUBERHORN_ECI__ONC_RPC_CALL_DECODER_BASE 0x500

#define LAUBERHORN_ECI__IP_ENCODER_BASE 0x600

#define LAUBERHORN_ECI__ONC_RPC_REPLY_ENCODER_BASE 0x700

#define LAUBERHORN_ECI_PROFILER_BASE 0x800

#define LAUBERHORN_ECI_SCHED_BASE 0x900

#define LAUBERHORN_ECI_DMA_BASE 0xa00

#define LAUBERHORN_ECI_HOST_IF_BASE 0xb00

static uint64_t __lauberhorn_eci_worker_bases[] __attribute__((unused)) = {
  0xc00,
  0xe00,
  0x1000,
  0x1200,
  0x1400,
};
#define LAUBERHORN_ECI_WORKER_BASE(blockIdx) (__lauberhorn_eci_worker_bases[blockIdx])

static uint64_t __lauberhorn_eci_preempt_bases[] __attribute__((unused)) = {
  0xd00,
  0xf00,
  0x1100,
  0x1300,
  0x1500,
};
#define LAUBERHORN_ECI_PREEMPT_BASE(blockIdx) (__lauberhorn_eci_preempt_bases[blockIdx])

#endif // __LAUBERHORN_ECI_REGS_H__
