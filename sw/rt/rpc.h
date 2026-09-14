#ifndef LAUBERHORN_RT_RPC_H
#define LAUBERHORN_RT_RPC_H

#include "worker.h"

void rpc_call_process_timeout();
void rpc_handle_packet(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc);

bool rpc_call_process_ready(void);

#endif // LAUBERHORN_RT_RPC_H