#include "lauberhorn/xdr_rpc.h"
#include "oncrpc.h"
#include "lauberhorn_eci_dev.h"
#include <assert.h>
#include <string.h>
static bool_t string_xdr(XDR *xdr, void *p) { return xdr_string(xdr, p, 32); }
int main(void)
{
    _Static_assert(lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_size == 16, "reply inline offset");
    _Static_assert(lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_size == 16, "completion inline offset");
    unsigned char descriptor[16];
    memset(descriptor, 0xa5, sizeof(descriptor));
    lauberhorn_eci_host_ctrl_info_onc_rpc_reply_tx_status_insert(descriptor, 5);
    assert(descriptor[3] == 5);
    assert(lauberhorn_eci_host_ctrl_info_onc_rpc_reply_rx_status_extract(descriptor) == 5);
    for (int i = 0; i < sizeof(descriptor); ++i)
        if (i != 3) assert(descriptor[i] == 0xa5);
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
