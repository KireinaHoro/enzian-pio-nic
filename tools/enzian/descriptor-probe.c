/* Host-side regression for generated nested TX/RX descriptor accessors.
 * Compile with -I sw/include -I PATH_TO_MATCHED_GENERATED_HEADERS.
 * This deliberately fails on job 2812450's headers; do not edit generated code.
 */
#include <stdint.h>
#include <stddef.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>
#include <lauberhorn_eci_dev.h>

/* Independent bit-level oracle: offsets below come from the RTL layout. */
static uint64_t get_bits(const uint8_t *p, unsigned offset, unsigned width)
{
    uint64_t value = 0;
    for (unsigned i = 0; i < width; ++i)
        value |= (uint64_t)((p[(offset+i)/8] >> ((offset+i)%8)) & 1) << i;
    return value;
}

static void put_bits(uint8_t *p, unsigned offset, unsigned width, uint64_t value)
{
    for (unsigned i = 0; i < width; ++i) {
        unsigned bit = offset+i;
        p[bit/8] = (p[bit/8] & ~(1u << (bit%8))) | (((value >> i)&1) << (bit%8));
    }
}

#define CHECK(kind, field, offset, width) do { \
    for (unsigned i = 0; i < sizeof(values)/sizeof(values[0]); ++i) { \
        _Alignas(64) uint8_t actual[64], expected[64]; \
        memset(actual, 0xa5 ^ i, sizeof actual); \
        memcpy(expected, actual, sizeof actual); \
        put_bits(expected, offset, width, values[i]); \
        lauberhorn_eci_host_ctrl_info_onc_rpc_##kind##_##field##_insert(actual, values[i]); \
        if (memcmp(actual, expected, sizeof actual) || \
            lauberhorn_eci_host_ctrl_info_onc_rpc_##kind##_##field##_extract(expected) != \
            get_bits(expected, offset, width)) { \
            fprintf(stderr, #kind "." #field " failed pattern %u\n", i); \
            failures++; \
        } \
        checks++; \
    } \
} while (0)

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
    unsigned failures = rtl_field != input, checks = 0;
    /* Test numeric bit preservation, including both host- and wire-order IP
     * representations. This accessor must not perform an endian conversion. */
    const uint64_t values[] = {0, UINT64_MAX, UINT64_C(0xdeadbeef89abcdef),
        UINT64_C(0x8000000080000000), UINT64_C(0x0123456789abcdef),
        UINT64_C(0xc0a88181), UINT64_C(0x8181a8c0)};
    CHECK(call_tx, ty, 0, 3);
    CHECK(call_tx, len, 3, 16);
    CHECK(call_tx, pid, 32, 16);
    CHECK(call_tx, cookie, 48, 32);
    CHECK(call_tx, xid, 80, 32);
    CHECK(call_tx, daddr, 112, 32);
    CHECK(call_tx, sport, 144, 16);
    CHECK(call_tx, dport, 160, 16);
    CHECK(call_tx, prog_num, 176, 32);
    CHECK(call_tx, prog_ver, 208, 32);
    CHECK(call_tx, proc, 240, 32);
    CHECK(call_tx, data0, 320, 64);
    CHECK(call_tx, data1, 384, 64);
    CHECK(call_tx, data2, 448, 64);
    CHECK(reply_rx, valid, 0, 1);
    CHECK(reply_rx, ty, 1, 3);
    CHECK(reply_rx, len, 4, 16);
    CHECK(reply_rx, pid, 32, 16);
    CHECK(reply_rx, cookie, 48, 32);
    CHECK(reply_rx, xid, 80, 32);
    printf("%u descriptor checks, %u failures\n", checks, failures);
    return failures != 0;
}
