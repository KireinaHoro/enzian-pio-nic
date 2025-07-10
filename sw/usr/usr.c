
#include "core-eci.h"
#include "pionic.h"

int pionic_thd_create(pionic_dev_t d, pionic_thd *t) { return -1; }
void pionic_thd_destroy(pionic_thd *t) {}

int pionic_dev_open(pionic_dev_t *d, const char *dev) { return -1; }

void pionic_dev_close(pionic_dev_t *d) {}

bool pionic_thd_rx(pionic_thd t, pionic_pkt_desc_t *desc) {

  volatile bool rx_parity_ptr = false;

  bool result = core_eci_rx(thd->pionic_base, &t->rx_parity, desc);
  assert(result);

  return result;
}

void pionic_thd_rx_ack(pionic_thd t, pionic_pkt_desc_t *desc) {
  core_eci_rx_ack(desc);
}

void pionic_thd_tx_prepare_desc(pionic_thd t, pionic_pkt_desc_t *desc) {

  core_eci_tx_prepare_desc(desc);
}
void pionic_thd_tx(pionic_thd t, pionic_pkt_desc_t *desc) {

  bool result = core_eci_tx(thd->pionic_base, &t->tx_parity, desc);
  assert(result);

  return result;
}

bool pionic_oncrpc_listen_port_open(pionic_dev_t d, int port) { return false; }

void pionic_oncrpc_listen_port_close(pionic_dev_t d, int port) {}

int pionic_oncrpc_service_register(pionic_dev_t d, int prog_num, int ver,
                                   int proc, void *func_ptr) {
  return -1;
}

void pionic_oncrpc_service_deregister(pionic_dev_t d, int idx) {}

