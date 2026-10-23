#ifndef LAUBERHORN_RT_RPC_H
#define LAUBERHORN_RT_RPC_H

#include "worker.h"

void rpc_call_process_timeout();
/* Runtime owns the domain deadline while RPC workers run. Call after changing
 * a pending timeout through the private table API; no slot lock may be held. */
void rpc_deadline_refresh(void);
void rpc_deadline_expired(uint64_t generation);
void rpc_handle_packet(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc);
struct pending_call;
bool rpc_call_cancel(struct pending_call *call, uint32_t cookie);



#endif // LAUBERHORN_RT_RPC_H
