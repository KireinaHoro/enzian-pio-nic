#ifndef LAUBERHORN_RT_XDRRPC_H
#define LAUBERHORN_RT_XDRRPC_H

#include "rpc.h"
#include <rpc/xdr.h> // remove this dependency
#include "common.h"

/**
 * adapter for RPC serialization / deserialization based on XDR
 * procs generated via rpcgen
 * @call_xdrproc: rpcgen generated xdr serializer for calls
 * @resp_xdrproc: rpcgen generated xdr serializer for resps
 * @call_size: struct size of call argument
 * @resp_size: struct size of response argument
 */
typedef struct {
	xdrproc_t call_xdrproc; 
	xdrproc_t resp_xdrproc; 
	size_t call_size; 
	size_t resp_size;
} xdr_adapter_t; 


// takes in an xdr_adapter_t and return rpc_ops_t from it
LAUBERHORN_API rpc_codec_t oncrpc_codec(xdr_adapter_t *adapter);

#endif // LAUBERHORN_RT_XDRRPC_H