// User-space API facing threads

#ifndef __LAUBERHORN_H__
#define __LAUBERHORN_H__

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define LAUBERHORN_BYPASS_HEADER_SIZE (LAUBERHORN_BYPASS_HEADER_MAX_WIDTH / 8)

typedef enum {
  TY_ERROR,
  TY_BYPASS,
  TY_ONCRPC_CALL,
} lauberhorn_pkt_desc_type_t;

// descriptor for one packet / transaction
// must be allocated / freed by the respective functions, since we
// want to decouple implementation size choices from application
typedef struct {
  lauberhorn_pkt_desc_type_t type;
  // transaction metadata (packet header, RPC session data, etc.)
  union {
    struct {
      enum {
        HDR_ETHERNET,
        HDR_IP,
        HDR_UDP,
        HDR_ONCRPC_CALL,
      } header_type;
      uint8_t header[LAUBERHORN_BYPASS_HEADER_SIZE];
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
} lauberhorn_pkt_desc_t;

lauberhorn_pkt_desc_t *lauberhorn_alloc_pkt_desc();
void lauberhorn_free_pkt_desc(lauberhorn_pkt_desc_t *desc);

// open/close device
struct lauberhorn_dev;
typedef struct lauberhorn_dev *lauberhorn_dev_t;

int lauberhorn_dev_open(lauberhorn_dev_t *d, const char *dev);
void lauberhorn_dev_close(lauberhorn_dev_t *d);


// create/destroy threads
struct lauberhorn_thd;
typedef struct lauberhorn_thd *lauberhorn_thd_t;

int lauberhorn_thd_create(lauberhorn_dev_t d, lauberhorn_thd *t);
void lauberhorn_thd_destroy(lauberhorn_thd *t);


// receive packet
bool lauberhorn_thd_rx(lauberhorn_thd t, lauberhorn_pkt_desc_t *desc);
// acknowledge received packet (for NIC to free packet)
void lauberhorn_thd_rx_ack(lauberhorn_thd t, lauberhorn_pkt_desc_t *desc);


// prepare TX packet descriptor (desc->type must be set to correctly set up
// header/args pointers)
void lauberhorn_thd_tx_prepare_desc(lauberhorn_thd t, lauberhorn_pkt_desc_t *desc);
// send packet
void lauberhorn_thd_tx(lauberhorn_thd t, lauberhorn_pkt_desc_t *desc);



#endif  // __LAUBERHORN_H__


