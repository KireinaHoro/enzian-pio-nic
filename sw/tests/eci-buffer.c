/* Exercise production cache-line arithmetic and ownership against byte-addressed
 * memory; coherence, writeback and wire behavior are covered by RTL tests. */
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include "eci/core.h"

static unsigned probes;
static uint64_t before_doorbell(void) { probes++; return 123456; }

int main(void) {
    unsigned char *base = calloc(1, LAUBERHORN_ECI_CORE_OFFSET);
    uint8_t rx = 0, tx = 0;
    lauberhorn_core_state_t state = {.rx_next_cl=&rx, .tx_next_cl=&tx};
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
            for(size_t j=0;j<length;j++) assert(state.rx_view.data[j]==(unsigned char)j);
            if (length) {
                state.rx_view.data[length-1] = 0x42;
                assert(data[length-1] == 0x42);
            }
            core_eci_rx_release(&state);
            assert(!state.rx_view.pending);
        }
    }
    for (unsigned parity=0; parity<2; parity++) {
        for (unsigned placement=0; placement<2; placement++) {
            bool inline_payload = placement;
            size_t capacity = inline_payload ? 64 : LAUBERHORN_MTU;
            for (unsigned i=0; i<sizeof(lengths)/sizeof(*lengths); i++) {
                size_t length = lengths[i];
                if (length > capacity) continue;
                tx = parity;
                unsigned char *ctrl=base+LAUBERHORN_ECI_TX_BASE+parity*128;
                unsigned char *next=base+LAUBERHORN_ECI_TX_BASE+(!parity)*128;
                *next=CORE_TX_ACCEPTED;
                void *data=core_eci_tx_get_buffer(base,&state,inline_payload);
                assert(state.tx_capacity == capacity);
                assert(data==(inline_payload?ctrl+64:base+LAUBERHORN_ECI_TX_BASE+256));
                assert(!core_eci_tx_get_buffer(base,&state,inline_payload));
                memset(data,0x73,capacity);
                lauberhorn_pkt_desc_t desc={.type=TY_NATIVE_UDP_TX,
                    .payload_len=length,.udp={.endpoint=17,.dport=11211}};
                uint64_t stamp = 0;
                unsigned before = probes;
                assert(core_eci_tx_commit_traced(base,&state,&desc,&stamp,before_doorbell)==CORE_TX_ACCEPTED);
                assert(stamp == 123456 && probes == before + 1);
                assert(!state.tx_reserved && tx==!parity);
                assert(lauberhorn_eci_host_tx_placement_inline_payload_extract(ctrl)==inline_payload);
                assert(lauberhorn_eci_host_ctrl_info_native_udp_tx_len_extract(ctrl)==length);
            }
        }
    }
    assert(core_eci_tx_get_buffer(base,&state,true));
    lauberhorn_pkt_desc_t too_big={.type=TY_NATIVE_UDP_TX,.payload_len=65};
    assert(core_eci_tx_commit(base,&state,&too_big)==CORE_TX_ERROR && state.tx_reserved);
    core_eci_tx_cancel(&state);
    assert(core_eci_tx_get_buffer(base,&state,false));
    too_big.payload_len = LAUBERHORN_MTU + 1;
    assert(core_eci_tx_commit(base,&state,&too_big)==CORE_TX_ERROR && state.tx_reserved);
    core_eci_tx_cancel(&state);
    free(base);
}
