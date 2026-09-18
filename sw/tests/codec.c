#include "lauberhorn/xdr_rpc.h"
#include "oncrpc.h"
#include "lauberhorn_eci_dev.h"
#include <stdlib.h>
/* This test exercises register bitfields, never MMIO. */
typedef uintptr_t mackerel_addr_t;
static inline uint64_t mackerel_read_addr_64(mackerel_addr_t base, int offset) { abort(); }
static inline void mackerel_write_addr_64(mackerel_addr_t base, int offset, uint64_t value) { abort(); }
#include "lauberhorn_eci_preempt_dev.h"
#include "config.h"
#include "eci/core.h"
#include <assert.h>
#include <string.h>
static bool_t string_xdr(XDR *xdr, void *p) { return xdr_string(xdr, p, 32); }
int main(void)
{
    uint64_t killed_cmd = (UINT64_C(1) << LAUBERHORN_PID_WIDTH) | 0x1234;
    assert(lauberhorn_eci_preempt_sched_cmd_killed_extract(killed_cmd) == 1);
    assert(lauberhorn_eci_preempt_sched_cmd_next_pid_extract(killed_cmd) == 0x1234);
    unsigned char control[8] = {0};
    lauberhorn_eci_host_worker_ctrl_busy_insert(control, 1);
    assert(control[0] == 1);
    lauberhorn_eci_host_worker_ctrl_ready_insert(control, 1);
    assert(control[0] == 3);
    _Static_assert(lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_size == 16, "reply inline offset");
    _Static_assert(lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_size == 16, "completion inline offset");
    _Static_assert(lauberhorn_eci_host_ctrl_info_onc_rpc_call_rx_size == 16, "call inline offset");
    unsigned char descriptor[16];
    memset(descriptor, 0xa5, sizeof(descriptor));
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_status_insert(descriptor, 5);
    assert(descriptor[3] == 5);
    assert(lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_status_extract(descriptor) == 5);
    for (int i = 0; i < sizeof(descriptor); ++i)
        if (i != 3) assert(descriptor[i] == 0xa5);
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_request_token_insert(descriptor, 0x1234abcd);
    assert(lauberhorn_eci_host_ctrl_info_onc_rpc_call_rx_request_token_extract(descriptor) == 0x1234abcd);
    assert(descriptor[4] == 0xcd && descriptor[7] == 0x12);
    assert(descriptor[3] == 5 && descriptor[8] == 0xa5);
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_execution_insert(descriptor, 3);
    assert(lauberhorn_eci_host_ctrl_info_execution_tx_execution_extract(descriptor) == 3);
    assert(lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_execution_extract(descriptor) == 3);
    assert(descriptor[3] == 5 && descriptor[4] == 0xcd && descriptor[8] == 0xa5);
    _Static_assert(lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_size == 64, "nested call descriptor width");
    unsigned char nested_descriptor[64] = {0};
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_data0_insert(nested_descriptor, UINT64_C(0x12345678));
    assert(nested_descriptor[40] == 0x78 && nested_descriptor[43] == 0x12);
    unsigned char payload[24];
    for (unsigned i = 0; i < sizeof(payload); ++i) payload[i] = 0x31 + 7*i;
    unsigned char cl[128];
    for (unsigned len = 1; len <= sizeof(payload); ++len) {
        memset(cl, 0xa5, sizeof(cl));
        core_eci_copy_nested_inline(cl, payload, len);
        assert(!memcmp(cl + 40, payload, len));
        for (unsigned i = 0; i < sizeof(cl); ++i)
            if (i < 40 || i >= 40 + len) assert(cl[i] == 0xa5);
    }
    xdr_adapter_t adapter = {.call_size = 64, .resp_size = 64,
        .call_xdrproc = (xdrproc_t)string_xdr, .resp_xdrproc = (xdrproc_t)string_xdr};
    rpc_codec_t codec = oncrpc_codec(&adapter);
    unsigned char buffer[16];
    char *value = "12345678";
    memset(buffer, 0xa5, sizeof(buffer));
    assert(codec.ops->marshal_call(&value, buffer, sizeof(value), 7, codec.priv) == -1);
    for (int i = 7; i < sizeof(buffer); ++i) assert(buffer[i] == 0xa5);
    assert(codec.ops->marshal_resp(&value, buffer, sizeof(value), 12, codec.priv) == 12);
    for (int i = 12; i < sizeof(buffer); ++i) assert(buffer[i] == 0xa5);
    for (int kind = RPC_ALLOC_CALL; kind <= RPC_ALLOC_RESP; ++kind) {
        void *p = codec.ops->alloc(kind, codec.priv);
        assert(p);
        for (int i = 0; i < 64; ++i) assert(((unsigned char *)p)[i] == 0);
        /* Truncated XDR string must be safely freeable after failed decode. */
        unsigned char truncated[] = {0, 0, 0, 16};
        assert(codec.ops->unmarshal_call(truncated, p, sizeof(truncated), codec.priv) == -1);
        codec.ops->free(p, kind == RPC_ALLOC_CALL ? RPC_FREE_CALL : RPC_FREE_RESP, codec.priv);
    }
}
