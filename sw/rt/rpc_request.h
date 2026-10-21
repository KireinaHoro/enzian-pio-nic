/* Shared RPC request ownership and codec path; included by transport runtimes. */
struct request {
    lauberhorn_pkt_desc_t desc;
    rpc_codec_t *codec;
    void *args;
#if LH_ENABLE_TRACING
    struct server_trace_row trace;
#endif
};
static void destroy_outcome(lauberhorn_outcome_t *o) {
    if (o->destroy) o->destroy(o->data);
}
static void release_request(struct request *r) {
    r->codec->ops->free(r->args, RPC_FREE_CALL, r->codec->priv);
}
static void *decode(void *data, size_t len, rpc_codec_t *codec, bool call, struct server_trace_row *trace) {
#if LH_ENABLE_TRACING
    if (trace) trace->alloc_enter_ns = server_trace_stamp(trace);
#else
    (void)trace;
#endif
    void *out = codec->ops->alloc(call ? RPC_ALLOC_CALL : RPC_ALLOC_RESP, codec->priv);
#if LH_ENABLE_TRACING
    if (trace) trace->alloc_exit_ns = server_trace_stamp(trace);
#endif
    if (!out) { errno = ENOMEM; return NULL; }
#if LH_ENABLE_TRACING
    if (trace) trace->decode_enter_ns = server_trace_stamp(trace);
#endif
    int ret = call ? codec->ops->unmarshal_call(data, out, len, codec->priv)
                   : codec->ops->unmarshal_resp(data, out, len, codec->priv);
#if LH_ENABLE_TRACING
    if (trace) trace->decode_exit_ns = server_trace_stamp(trace);
#endif
    if (ret < 0) {
        codec->ops->free(out, call ? RPC_FREE_CALL : RPC_FREE_RESP, codec->priv);
        errno = EINVAL;
        return NULL;
    }
    return out;
}
