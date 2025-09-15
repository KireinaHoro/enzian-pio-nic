// User-space APIs facing threads

#ifndef __PIONIC_H__
#define __PIONIC_H__

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// generated hw header
#include "config.h"

#ifndef __PIONIC_RT__

// --- deprecated for now ---
// must be allocated / freed by the respective functions, since we
// want to decouple implementation size choices from application
// pionic_pkt_desc_t *pionic_alloc_pkt_desc();
// void pionic_free_pkt_desc(pionic_pkt_desc_t *desc);

// open/close device
struct pionic_dev {
  size_t page_len;

  char *page_1;
  char *page_2;
  uint64_t fake_struct;
};
typedef struct pionic_dev *pionic_dev_t;

int pionic_dev_open(pionic_dev_t *d, const char *dev);
void pionic_dev_close(pionic_dev_t *d);

// admin configurations (may require sudo)

// global configurations
void pionic_dev_set_rx_block_cycles(pionic_dev_t d, int cycles);
void pionic_dev_set_promisc(pionic_dev_t d, bool enable);

// protocol decoder configurations
void pionic_dev_set_mac_addr(pionic_dev_t d, uint8_t *mac_addr);
void pionic_dev_set_ip_addr(pionic_dev_t d, uint8_t *ip_addr);

// enable port
// return true on success
bool pionic_oncrpc_listen_port_open(pionic_dev_t d, int port);
// disable port
void pionic_oncrpc_listen_port_close(pionic_dev_t d, int port);

// register service
// returns an index on success or negative number on failure
int pionic_oncrpc_service_register(pionic_dev_t d, int prog_num, int ver,
                                   int proc, void *func_ptr);
// deregister service by index
void pionic_oncrpc_service_deregister(pionic_dev_t d, int idx);

// create/destroy threads
// XXX: threads... how to name this... actually the only thread (excluding
// non-RPC-serving threads) in the process.
// struct pionic_thd;
typedef struct pionic_thd {
  uint64_t fake_struct;
  void *pionic_base;
  bool tx_parity;
  bool rx_parity;
} pionic_thd;
typedef struct pionic_thd *pionic_thd_t;

int pionic_thd_create(pionic_dev_t d, pionic_thd *t);
void pionic_thd_destroy(pionic_thd *t);

// receive packet
// return true on success, false on no packet
bool pionic_thd_rx(pionic_thd t, pionic_pkt_desc_t *desc);
// acknowledge received packet (for NIC to free packet)
void pionic_thd_rx_ack(pionic_thd t, pionic_pkt_desc_t *desc);

// prepare TX packet descriptor
// desc->payload_buf must be NULL and payload_len must be 0 when calling the
// function Depending on the backend (PCIe or ECI):
//   if returned desc->payload_buf is not NULL, use this buffer (will be
//   released on tx) if returned desc->payload_buf is NULL, the user can
//   attached a buffer before tx but should also free the buffer (tx does
//   nothing)
void pionic_thd_tx_prepare_desc(pionic_thd t, pionic_pkt_desc_t *desc);
// send packet
void pionic_thd_tx(pionic_thd t, pionic_pkt_desc_t *desc);

#endif // __PIONIC_RT__

#endif // __PIONIC_H__

