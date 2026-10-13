/* Bounded, closed-loop load. Included after sample, now(), and number().
 * Requests retain unique IDs and timestamps; responses may complete out of order.
 * This measures achieved load, not an open-loop offered-rate/SLO curve. */
static unsigned load_phase(uint16_t port, struct rte_mempool *pool,
        struct endpoint src, struct endpoint dst, const struct benchmark *bench,
        struct sample *samples, unsigned first, unsigned count, unsigned window,
        uint64_t gap, uint64_t timeout, uint64_t *unrelated, int *failed) {
    unsigned sent=0, done=0, oldest=0;
    uint64_t next_send=0;
    while(done<count && !*failed) {
        uint64_t clock=now();
        /* Limit each enqueue batch so RX is serviced under continuous load. */
        unsigned budget=32;
        while(sent<count && sent-done<window && budget-- && clock>=next_send) {
            struct rte_mbuf *tx=rte_pktmbuf_alloc(pool);
            if(!tx) rte_exit(1,"load TX allocation failed\n");
            uint8_t payload[1472];
            struct sample *s=&samples[first+sent];
            s->enter=now();
            size_t n=bench->encode(payload,sizeof(payload),first+sent+1);
            if(!n || n>sizeof(payload)) rte_exit(1,"load codec size\n");
            uint8_t *p=(uint8_t *)rte_pktmbuf_append(tx,42+n);
            if(!p) rte_exit(1,"load TX append failed\n");
            make_udp(p,&src,&dst,payload,n);
            s->ready=now();
            if(!rte_eth_tx_burst(port,0,&tx,1)) {
                rte_pktmbuf_free(tx);
                /* Preserve failed admission; never reduce the denominator. */
                s->status=2; s->submitted=s->exit=now();
                sent++; *failed=1; break;
            }
            s->submitted=now();
            next_send=s->enter+gap;
            sent++; clock=now();
        }
        struct rte_mbuf *rx[32];
        uint16_t n=rte_eth_rx_burst(port,0,rx,32);
        uint64_t received=n?now():0;
        for(uint16_t j=0;j<n;j++) {
            uint8_t scratch[2048]; size_t len=rte_pktmbuf_pkt_len(rx[j]);
            const uint8_t *data=len<=sizeof(scratch)?rte_pktmbuf_read(rx[j],0,len,scratch):NULL;
            const uint8_t *reply=NULL; size_t size=0;
            int parsed=data?parse_udp(data,len,&src,&dst,&reply,&size):0;
            if(!parsed) (*unrelated)++;
            else if(parsed!=1 || size<4) *failed=1;
            else {
                uint32_t id=get32(reply);
                if(id<=first || id>first+sent) *failed=1;
                else {
                    struct sample *s=&samples[id-1];
                    if(s->status || bench->validate(reply,size,id)!=1) {
                        if(!*failed) {
                            fprintf(stderr,"invalid_load_reply id=%u previous_status=%d bytes=%zu payload=",id,s->status,size);
                            for(size_t k=0;k<size && k<96;k++) fprintf(stderr,"%02x",reply[k]);
                            fputc('\n',stderr);
                        }
                        *failed=1;
                        if(!s->status) { s->status=3; s->received=received; s->exit=now(); }
                    } else {
                        s->received=received; s->status=1; s->exit=now(); done++;
                    }
                }
            }
            rte_pktmbuf_free(rx[j]);
        }
        while(oldest<sent && samples[first+oldest].status) oldest++;
        if(oldest<sent && now()-samples[first+oldest].enter>=timeout) *failed=1;
    }
    if(*failed) {
        uint64_t end=now();
        for(unsigned i=0;i<sent;i++) if(!samples[first+i].status) {
            samples[first+i].status=2; samples[first+i].exit=end;
        }
    }
    return sent;
}
