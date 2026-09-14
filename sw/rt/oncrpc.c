#include "oncrpc.h"
#include "lauberhorn/rpc.h"
#include "lauberhorn/xdr_rpc.h"
#include <stdatomic.h>

int xdr_marshal_call(void *in_buf, void *out_buf, size_t in_buf_len,
                     void *priv) {
    xdr_adapter_t *p = priv;
    XDR xdr;
    xdrmem_create(&xdr, out_buf, p->call_size, XDR_ENCODE);
    if (!p->call_xdrproc(&xdr, in_buf)) {
        return -1;
    }
    return xdr_getpos(&xdr);
}

int xdr_marshal_resp(void *in_buf, void *out_buf, size_t in_buf_len,
                     void *priv) {
    xdr_adapter_t *p = priv;
    XDR xdr;
    xdrmem_create(&xdr, out_buf, p->resp_size, XDR_ENCODE);
    if (!p->resp_xdrproc(&xdr, in_buf)) {
        return -1;
    }
    return xdr_getpos(&xdr);
}

int xdr_unmarshal_call(void *in_buf, void *out_buf, size_t in_buf_len,
                       void *priv) {
    xdr_adapter_t *p = priv;
    XDR xdr;
    xdrmem_create(&xdr, in_buf, in_buf_len, XDR_DECODE);
    if (!p->call_xdrproc(&xdr, out_buf)) {
        return -1;
    }
    return 0;
}

int xdr_unmarshal_resp(void *in_buf, void *out_buf, size_t in_buf_len,
                       void *priv) {
    xdr_adapter_t *p = priv;
    XDR xdr;
    xdrmem_create(&xdr, in_buf, in_buf_len, XDR_DECODE);
    if (!p->resp_xdrproc(&xdr, out_buf)) {
        return -1;
    }
    return 0;
}

void xdr_free_func(void *ptr, rpc_free_kind_t kind, void *priv) {
    xdr_adapter_t *p = priv;
    switch (kind) {
    // free xdr_unmarshal_call
    case RPC_FREE_CALL:
        // free potentially allocated nested structures
        xdr_free(p->call_xdrproc, ptr);
        // free root
        free(ptr);
        break;
    case RPC_FREE_RESP:
        xdr_free(p->resp_xdrproc, ptr);
        free(ptr);
        break;
    }
}

void *xdr_alloc_func(rpc_alloc_kind_t kind, void *priv) {
    xdr_adapter_t *p = priv;
    switch (kind) {
    case RPC_ALLOC_CALL:
        return calloc(1, p->call_size);
    case RPC_ALLOC_RESP:
        return calloc(1, p->resp_size);
    }
    return NULL;
}

rpc_codec_t oncrpc_codec(xdr_adapter_t *adapter) {
    static rpc_ops_t xdr_ops = {.marshal_call = xdr_marshal_call,
                                .unmarshal_call = xdr_unmarshal_call,
                                .marshal_resp = xdr_marshal_resp,
                                .unmarshal_resp = xdr_unmarshal_resp,
                                .free = xdr_free_func,
                                .alloc = xdr_alloc_func};

    return (rpc_codec_t){.ops = &xdr_ops, .priv = adapter};
}
