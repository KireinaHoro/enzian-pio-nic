/* Host-side diagnostic for the generated nested-call destination accessor.
 * Compile with -I sw/include -I PATH_TO_MATCHED_GENERATED_HEADERS.
 * This deliberately fails on job 2812450's headers; do not edit generated code.
 */
#include <stdint.h>
#include <stddef.h>
#include <inttypes.h>
#include <stdio.h>
#include <lauberhorn_eci_dev.h>

int main(void)
{
    _Alignas(64) uint8_t ctrl[64] = {0};
    uint32_t input = 0xc0a88181, rtl_field;
    lauberhorn_eci_host_ctrl_info_onc_rpc_call_tx_daddr_insert(ctrl, input);
    /* EciHostTxCtrlInfo: ty+len+reserved=32, PID=16, cookie=32, XID=32.
     * Decode the field at bit 112 independently of the generated extractor.
     */
    rtl_field = (uint32_t)ctrl[14] | ((uint32_t)ctrl[15] << 8)
              | ((uint32_t)ctrl[16] << 16) | ((uint32_t)ctrl[17] << 24);
    printf("input=%08" PRIx32 " rtl_daddr=%08" PRIx32
           " bytes=%02x %02x %02x %02x\n",
           input, rtl_field, ctrl[14], ctrl[15], ctrl[16], ctrl[17]);
    return rtl_field != input;
}
