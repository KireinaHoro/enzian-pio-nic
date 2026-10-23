/* Shared synchronous REPLY handling; transport supplies buffer/submit operations. */
    case LAUBERHORN_REPLY: {
        size_t capacity = r->codec->response_inline ? 64 : REPLY_CAPACITY;
#if LH_ENABLE_TRACING
        r->trace.reserve_enter_ns = server_trace_stamp(&r->trace);
#endif
        w->dp.tx_buf = rpc_tx_reserve(w, r->codec->response_inline);
#if LH_ENABLE_TRACING
        r->trace.reserve_exit_ns = server_trace_stamp(&r->trace);
#endif
        if (!w->dp.tx_buf) { LOG("Cannot reserve response TX"); break; }
#if LH_ENABLE_TRACING
        r->trace.encode_enter_ns = server_trace_stamp(&r->trace);
#endif
        int len = r->codec->ops->marshal_resp(o.data, w->dp.tx_buf, o.len,
                                             capacity, r->codec->priv);
#if LH_ENABLE_TRACING
        r->trace.encode_exit_ns = server_trace_stamp(&r->trace);
#endif
        if (len < 0 || (size_t)len > capacity) {
            rpc_tx_cancel(w);
            fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace));
        }
        else send_reply(&r->desc, 0, len, LH_TRACE_PTR(&r->trace));
        break;
    }
