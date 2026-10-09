/* Exercise production cache-line arithmetic and ownership against byte-addressed
 * memory; coherence, writeback and wire behavior are covered by RTL tests. */
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include "eci/core.h"

int main(void) {
    unsigned char *base = calloc(1, LAUBERHORN_ECI_CORE_OFFSET);
    unsigned char owned[LAUBERHORN_MTU];
    uint8_t rx = 0, tx = 0;
    lauberhorn_core_state_t state = {.rx_next_cl=&rx, .tx_next_cl=&tx,
        .rx_buf=owned, .rx_buf_size=sizeof(owned)};
    const size_t lengths[] = {0,1,63,64,65,127,128,129,1472,1518};
    for (unsigned parity=0; parity<2; parity++) {
        for (unsigned i=0;i<sizeof(lengths)/sizeof(*lengths);i++) {
            size_t length=lengths[i]; rx=parity;
            unsigned char *ctrl=base+LAUBERHORN_ECI_RX_BASE+parity*128;
            memset(base,0xa5,LAUBERHORN_ECI_TX_BASE);
            lauberhorn_eci_host_ctrl_info_native_udp_rx_valid_insert(ctrl,1);
            lauberhorn_eci_host_ctrl_info_native_udp_rx_ty_insert(ctrl,lauberhorn_eci_native_udp_rx);
            lauberhorn_eci_host_ctrl_info_native_udp_rx_len_insert(ctrl,length);
            lauberhorn_eci_host_ctrl_info_native_udp_rx_endpoint_insert(ctrl,17);
            unsigned char *data=length<=64?ctrl+64:base+LAUBERHORN_ECI_RX_BASE+256;
            for(size_t j=0;j<length;j++) data[j]=(unsigned char)j;
            lauberhorn_pkt_desc_t desc;
            assert(core_eci_rx_acquire(base,&state,&desc));
            assert(desc.payload_len==length && desc.udp.endpoint==17);
            assert(state.rx_view.data==data && state.rx_view.length==length);
            assert(state.rx_view.pending && rx==!parity);
            assert(!core_eci_rx_acquire(base,&state,&desc) && rx==!parity);
            if(length) {
                assert(!core_eci_rx_consume(&state,owned,length-1));
                assert(state.rx_view.pending);
            }
            assert(core_eci_rx_consume(&state,owned,sizeof(owned)));
            assert(!state.rx_view.pending);
            for(size_t j=0;j<length;j++) assert(owned[j]==(unsigned char)j);
            memset(data,0,length); // copied compatibility storage is independent
            for(size_t j=0;j<length;j++) assert(owned[j]==(unsigned char)j);
            // Exercise the old public operation, not only its copy helper.
            rx = parity;
            memcpy(data, owned, length);
            assert(core_eci_rx(base, &state, &desc) && !state.rx_view.pending);
            memset(data, 0, length);
            for(size_t j=0;j<length;j++) assert(owned[j]==(unsigned char)j);
        }
    }
    for(unsigned parity=0;parity<2;parity++) {
        for(unsigned i=0;i<sizeof(lengths)/sizeof(*lengths);i++) {
            size_t capacity=lengths[i]; tx=parity;
            unsigned char *ctrl=base+LAUBERHORN_ECI_TX_BASE+parity*128;
            unsigned char *next=base+LAUBERHORN_ECI_TX_BASE+(!parity)*128;
            *next=CORE_TX_ACCEPTED;
            void *data=core_eci_tx_get_buffer(base,&state,capacity);
            assert(data==(capacity<=64?ctrl+64:base+LAUBERHORN_ECI_TX_BASE+256));
            assert(!core_eci_tx_get_buffer(base,&state,capacity));
            if(capacity) memset(data,0x73,capacity);
            lauberhorn_pkt_desc_t desc={.type=TY_NATIVE_UDP_TX,
                .payload_len=capacity?1:0,.udp={.endpoint=17,.dport=11211}};
            assert(core_eci_tx_commit(base,&state,&desc)==CORE_TX_ACCEPTED);
            assert(!state.tx_reserved && tx==!parity);
            assert(lauberhorn_eci_host_tx_placement_inline_payload_extract(ctrl)==(capacity<=64));
            assert(lauberhorn_eci_host_ctrl_info_native_udp_tx_len_extract(ctrl)==desc.payload_len);
            assert(lauberhorn_eci_host_tx_reservation_overflow_lines_extract(ctrl)==(capacity<=64?0:(capacity+127)/128));
        }
    }
    assert(!core_eci_tx_get_buffer(base,&state,LAUBERHORN_MTU+1));
    assert(core_eci_tx_get_buffer(base,&state,1));
    lauberhorn_pkt_desc_t too_big={.type=TY_NATIVE_UDP_TX,.payload_len=2};
    assert(core_eci_tx_commit(base,&state,&too_big)==CORE_TX_ERROR && state.tx_reserved);
    core_eci_tx_abort(&state);
    free(base);
}
