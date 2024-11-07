#ifndef __PIONIC_API_H__
#define __PIONIC_API_H__

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct pionic_ctx;
typedef struct pionic_ctx *pionic_ctx_t;

// descriptor for one packet / transaction
// must be allocated / freed by the respective functions, since we
// want to decouple implementation size choices from application
typedef struct {
  enum {
    TY_ERROR,
    TY_BYPASS,
    TY_ONCRPC_CALL,
  } type;

  // transaction metadata (packet header, RPC session data, etc.)
  union {
    struct {
      enum {
        HDR_ETHERNET,
        HDR_IP,
        HDR_UDP,
        HDR_ONCRPC_CALL,
      } header_type;
      uint8_t *header_buf;
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
pionic_pkt_desc_t *pionic_alloc_pkt_desc();
void pionic_free_pkt_desc(pionic_pkt_desc_t *desc);

// implementation details
int pionic_get_mtu();

// allocate and release ctx
int pionic_init(pionic_ctx_t *ctx, const char *dev, bool loopback);
void pionic_fini(pionic_ctx_t *ctx);

// global configurations
void pionic_set_rx_block_cycles(pionic_ctx_t ctx, int cycles);
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

// receive packet
bool pionic_rx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);
// acknowledge received packet (for NIC to free packet)
void pionic_rx_ack(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);

// prepare TX packet descriptor (get output buffer addr)
void pionic_tx_prepare_desc(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);
// send packet
void pionic_tx(pionic_ctx_t ctx, int cid, pionic_pkt_desc_t *desc);

#endif // __PIONIC_API_H__
