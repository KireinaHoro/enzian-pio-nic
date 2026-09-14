#include "lauberhorn/xdr_rpc.h"
#include "oncrpc.h"
#include <assert.h>
#include <string.h>
static bool_t string_xdr(XDR *xdr, void *p) { return xdr_string(xdr, p, 32); }
int main(void)
{
    xdr_adapter_t adapter = {.call_size = 64, .resp_size = 64,
        .call_xdrproc = (xdrproc_t)string_xdr, .resp_xdrproc = (xdrproc_t)string_xdr};
    rpc_codec_t codec = oncrpc_codec(&adapter);
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
