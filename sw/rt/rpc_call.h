/* Shared dispatch/decode/handler/outcome/cleanup hot path. */
static void rpc_handle_call(lauberhorn_worker_t w, lauberhorn_pkt_desc_t *desc) {
#if LH_ENABLE_TRACING
    w->rx_trace.rpc_enter_ns = server_trace_stamp(&w->rx_trace);
#endif
    // Hardware begins on incoming call delivery; completion RX alone does not
    // imply a fiber resumed. Immediate inline-to-fiber deferral keeps this interval.
    w->execution_active = w->ctx->_watchdog_enabled;
    struct lauberhorn_hw_handler *hw = desc->oncrpc_call_rx.func_ptr;
    struct request r = {.desc = *desc, .codec = &hw->srv->codec
#if LH_ENABLE_TRACING
        , .trace = w->rx_trace
#endif
    };
#if LH_ENABLE_TRACING
    r.trace.request_context = desc->oncrpc_call_rx.request_token;
    r.trace.request_bytes = desc->payload_len;
    r.trace.unmarshal_enter_ns = server_trace_stage(&r.trace, LH_PMU_unmarshal_enter);
#endif
    r.args = decode(w->dp.rx_view.data, desc->payload_len, r.codec, true, LH_TRACE_PTR(&r.trace));
#if LH_ENABLE_TRACING
    r.trace.unmarshal_exit_ns = server_trace_stage(&r.trace, LH_PMU_unmarshal_exit);
#endif
    if (!r.args) { fail_request(desc, errno == ENOMEM ? LAUBERHORN_RPC_SYSTEM_ERR : LAUBERHORN_RPC_GARBAGE_ARGS, LH_TRACE_PTR(&r.trace)); return; }
#if LH_ENABLE_TRACING
    struct server_trace_row *previous = server_trace_inline;
    server_trace_inline = &r.trace;
    r.trace.handler_enter_ns = server_trace_stage(&r.trace, LH_PMU_handler_enter);
    r.trace.entry_enter_ns = r.trace.handler_enter_ns;
#endif
    lauberhorn_outcome_t outcome = hw->handler.func(hw->handler.data, r.args,
                                                 desc->oncrpc_call_rx.request_token);
#if LH_ENABLE_TRACING
    server_trace_event(&r.trace, LH_TRACE_ENTRY_RETURN, outcome.kind, 0);
    r.trace.handler_exit_ns = server_trace_stage(&r.trace, LH_PMU_handler_exit);
    r.trace.entry_exit_ns = r.trace.handler_exit_ns;
    r.trace.handler_active_ns = r.trace.handler_exit_ns - r.trace.handler_enter_ns;
    server_trace_inline = previous;
#endif
#if LH_ENABLE_TRACING
    r.trace.outcome_enter_ns = server_trace_stamp(&r.trace);
#endif
    if (!consume_outcome(&r, outcome, true)) release_request(&r);
}
