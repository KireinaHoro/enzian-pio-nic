/* Shared synchronous REPLY handling; transport supplies buffer/submit operations. */
    case LAUBERHORN_REPLY: {
#if LH_ENABLE_TRACING
        r->trace.marshal_enter_ns = server_trace_stage(&r->trace, LH_PMU_marshal_enter);
#endif
        size_t capacity = r->codec->response_inline ? 64 : REPLY_CAPACITY;
        w->dp.tx_buf = rpc_tx_reserve(w, r->codec->response_inline);
        if (!w->dp.tx_buf) { LOG("Cannot reserve response TX"); break; }
        int len = r->codec->ops->marshal_resp(o.data, w->dp.tx_buf, o.len,
                                             capacity, r->codec->priv);
#if LH_ENABLE_TRACING
        r->trace.marshal_exit_ns = server_trace_stage(&r->trace, LH_PMU_marshal_exit);
#endif
        if (len < 0 || (size_t)len > capacity) {
            rpc_tx_cancel(w);
            fail_request(&r->desc, LAUBERHORN_RPC_SYSTEM_ERR, LH_TRACE_PTR(&r->trace));
        }
        else send_reply(&r->desc, 0, len, LH_TRACE_PTR(&r->trace));
        break;
    }
