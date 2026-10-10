#ifndef LAUBERHORN_RT_RPC_TYPES_H
#define LAUBERHORN_RT_RPC_TYPES_H

#include <stdlib.h>

typedef enum {
	RPC_FREE_CALL = 0, // unmarshalled call
	RPC_FREE_RESP = 1, // unmarshalled response from handler function
} rpc_free_kind_t;

typedef enum {
    RPC_ALLOC_CALL = 0, 
    RPC_ALLOC_RESP = 1,
} rpc_alloc_kind_t;


typedef struct rpc_ops {
    /**
     * Serializers called to serialize outgoing calls / replies
     * 
     * @in_buf: data to be serialized 
     * @out_buf: data buffer to serialize into
     * @out_capacity: writable bytes in out_buf; never write beyond this bound
     * @in_buf_len: length of in_buf
     * @priv: filled by the runtime with `rpc_codec_t->priv`
     * returns: bytes written or -1 on error
     */
	int (*marshal_call)(void *in_buf, void *out_buf, size_t in_buf_len, size_t out_capacity, void *priv);
    int (*marshal_resp)(void *in_buf, void *out_buf, size_t in_buf_len, size_t out_capacity, void *priv);

	/**
     * Deserializers called to deserialize incoming calls / replies
     *
     * @in_buf: borrowed transport bytes, valid only during this call. Do not
     * retain this pointer or store aliases into out_buf. Decode/copy retained
     * data into owned storage: deferred requests and futures outlive RX release.
     * @out_buf: data buffer to deserialize into
     * @in_buf_len: length of in_buf
     * @priv: filled by the runtime with `rpc_codec_t->priv`
     * returns: 0 on success, -1 on failure
     */
	int (*unmarshal_call)(void *in_buf, void *out_buf, size_t in_buf_len, void *priv);
	int (*unmarshal_resp)(void *in_buf, void *out_buf, size_t in_buf_len, void *priv);

	/**
     * Function that is called by runtime to free memory allocated by deserializers
     * 
     * @kind: RPC_FREE_CALL (to free memory allocated  by `unmarshal_call`),
     * RPC_FREE_RESP (to free memory allocated by `unmarshal_resp`)
     * @priv: filled by the runtime with `rpc_codec_t->priv`
     */
	void (*free)(void *ptr, rpc_free_kind_t kind, void *priv);
    void* (*alloc)(rpc_alloc_kind_t, void *priv);

    /* Optional encoded reply size (or safe upper bound), not the C object size.
     * Called before reservation; must not consume or mutate the input. Without
     * this callback the runtime reserves the maximum overflow capacity. */
    size_t (*response_capacity)(void *in_buf, size_t in_buf_len, void *priv);
} rpc_ops_t; 


typedef struct {
    rpc_ops_t *ops;
    void *priv; 
} rpc_codec_t; 

#endif // LAUBERHORN_RT_RPC_TYPES_H