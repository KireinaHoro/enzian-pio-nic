#ifndef __PIONIC_API_H__
#define __PIONIC_API_H__

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "hal.h"

#define __PIONIC_RT__
#include "pionic.h"  // get pionic_pkt_desc_t etc. and rename below

struct pionic_ctx;
typedef struct pionic_ctx *pionic_ctx_t;

// implementation details
int pionic_get_mtu();

// allocate and release ctx
int pionic_init(pionic_ctx_t *ctx, const char *dev, bool loopback);
void pionic_fini(pionic_ctx_t *ctx);

// global configurations
void pionic_set_rx_block_cycles(pionic_ctx_t ctx, uint64_t cycles);
uint64_t pionic_get_rx_block_cycles(pionic_ctx_t ctx);
// void pionic_set_core_mask(pionic_ctx_t ctx, uint64_t mask);
void pionic_set_promisc(pionic_ctx_t ctx, bool enable);

// protocol decoder configurations
void pionic_set_mac_addr(pionic_ctx_t ctx, uint8_t *mac_addr);
void pionic_set_ip_addr(pionic_ctx_t ctx, uint8_t *ip_addr);

void pionic_oncrpc_listen_port_enable(pionic_ctx_t ctx, int idx, int port);
void pionic_oncrpc_listen_port_disable(pionic_ctx_t ctx, int idx);

void pionic_oncrpc_service_enable(pionic_ctx_t ctx, int idx, int prog_num,
                                  int ver, int proc, void *func_ptr);
void pionic_oncrpc_service_disable(pionic_ctx_t ctx, int idx);

void pionic_oncrpc_set_core_mask(pionic_ctx_t ctx, int mask);

// per core
void pionic_sync_core_state(pionic_core_state_t *state, pionic_core_t *core);

// receive packet
bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);
// acknowledge received packet (for NIC to free packet)
void pionic_rx_ack(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);

// prepare TX packet descriptor (desc->type must be set to correctly set up
// header/args pointers)
void pionic_tx_prepare_desc(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);
// send packet
void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);

#endif // __PIONIC_API_H__
