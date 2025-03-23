// User-space API facing threads

#ifndef __PIONIC_H__
#define __PIONIC_H__

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// #define PIONIC_BYPASS_HEADER_SIZE (PIONIC_BYPASS_HEADER_MAX_WIDTH / 8)

typedef enum {
  TY_ERROR,
  TY_BYPASS,
  TY_ONCRPC_CALL,
} pionic_pkt_desc_type_t;

// descriptor for one packet / transaction
// must be allocated / freed by the respective functions, since we
// want to decouple implementation size choices from application
typedef struct {
  pionic_pkt_desc_type_t type;
  // transaction metadata (packet header, RPC session data, etc.)
  union {
    struct {
      enum {
        HDR_ETHERNET,
        HDR_IP,
        HDR_UDP,
        HDR_ONCRPC_CALL,
      } header_type;
      uint8_t *header;
    } bypass;
    struct {
      void *func_ptr;
      int xid;
      uint32_t *args;
    } oncrpc_call;
  };

  // extra payload
  uint8_t *payload_buf;
  size_t payload_len;
} pionic_pkt_desc_t;

#ifndef __PIONIC_RT__

pionic_pkt_desc_t *pionic_alloc_pkt_desc();
void pionic_free_pkt_desc(pionic_pkt_desc_t *desc);

// open/close device
struct pionic_dev;
typedef struct pionic_dev *pionic_dev_t;

int pionic_dev_open(pionic_dev_t *d, const char *dev);
void pionic_dev_close(pionic_dev_t *d);


// create/destroy threads
struct pionic_thd;
typedef struct pionic_thd *pionic_thd_t;

int pionic_thd_create(pionic_dev_t d, pionic_thd *t);
void pionic_thd_destroy(pionic_thd *t);


// receive packet
bool pionic_thd_rx(pionic_thd t, pionic_pkt_desc_t *desc);
// acknowledge received packet (for NIC to free packet)
void pionic_thd_rx_ack(pionic_thd t, pionic_pkt_desc_t *desc);


// prepare TX packet descriptor (desc->type must be set to correctly set up
// header/args pointers)
void pionic_thd_tx_prepare_desc(pionic_thd t, pionic_pkt_desc_t *desc);
// send packet
void pionic_thd_tx(pionic_thd t, pionic_pkt_desc_t *desc);

#endif  // __PIONIC_RT__

#endif  // __PIONIC_H__


