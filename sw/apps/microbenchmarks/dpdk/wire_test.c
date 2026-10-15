#include <assert.h>
#include <stdio.h>
#include "wire.h"
#include "benchmark.h"
#include "echo_registry.h"
int main(void) {
    struct endpoint a={{1,2,3,4,5,6},{192,168,1,1},12345};
    struct endpoint b={{6,5,4,3,2,1},{192,168,1,2},9999};
    uint8_t p[1600],payload[64]; const uint8_t *r; size_t len;
    for(size_t k=0;k<2;k++) {
        const struct benchmark *bench=&benchmarks[k];
        size_t n=bench->encode(payload,sizeof(payload),9);
        assert(n==52 && get32(payload)==9 && get32(payload+40)==9);
        assert(get32(payload+12)==(k?0x23451112u:0x23451111u));
        assert(!bench->encode(payload,10,9));
        assert(make_udp(p,&a,&b,payload,n)==94);
        assert(!checksum(p+14,20,0));
        memset(payload,0,32); put32(payload,9); put32(payload+4,1);
        put32(payload+24,9); put32(payload+28,k?77:18);
        n=make_udp(p,&b,&a,payload,32);
        assert(parse_udp(p,n,&a,&b,&r,&len)==1);
        assert(bench->validate(r,len,9)==1);
        assert(bench->validate(r,len,10)==0);
        for(size_t i=0;i<n;i++) assert(parse_udp(p,i,&a,&b,&r,&len)!=1);
        for(size_t i=4;i<32;i++) assert(bench->validate(payload,i,9)==-1);
        payload[31]^=1; assert(bench->validate(payload,32,9)==-1); payload[31]^=1;
        put32(payload+16,0xffffffff); assert(bench->validate(payload,32,9)==-1);
        p[24]^=1; assert(parse_udp(p,n,&a,&b,&r,&len)==-1);
    }
    const struct benchmark *echo=benchmark_find("echo");
    assert(echo->encode(payload,sizeof(payload),42)==64);
    assert(echo->validate(payload,64,42)==1);
    payload[63]^=1; assert(echo->validate(payload,64,42)==-1);
    assert(!benchmark_find("absent"));
    const unsigned sizes[] = {8, 64, 128, 512, 1432};
    for (size_t k = 0; k < sizeof(sizes) / sizeof(sizes[0]); k++) {
        char name[32]; uint8_t call[1472], reply[1472];
        snprintf(name, sizeof(name), "loopback%u", sizes[k]);
        const struct benchmark *bench = benchmark_find(name);
        assert(bench);
        const uint32_t ids[]={1,255,256,257,65535,UINT32_MAX};
        for(size_t t=0;t<sizeof(ids)/sizeof(ids[0]);t++) {
            assert(bench->encode(call,sizeof(call),ids[t])==40+sizes[k]);
            assert(get32(call+40)==ids[t]);
            for(size_t j=4;j<sizes[k];j++) assert(call[40+j]==(uint8_t)(ids[t]+j));
            memset(reply,0,24);put32(reply,ids[t]);put32(reply+4,1);
            memcpy(reply+24,call+40,sizes[k]);
            assert(bench->validate(reply,24+sizes[k],ids[t])==1);
        }
        size_t n = bench->encode(call, sizeof(call), 91);
        assert(n == 40 + sizes[k]);
        assert(!bench->encode(call, n - 1, 91));
        assert(get32(call + 12) == 0x23451113);
        assert(make_udp(p, &a, &b, call, n) == 42 + n);
        assert(parse_udp(p, 42 + n, &b, &a, &r, &len) == 1);
        memset(reply, 0, 24); put32(reply, 91); put32(reply + 4, 1);
        memcpy(reply + 24, call + 40, sizes[k]);
        n = 24 + sizes[k];
        assert(bench->validate(reply, n, 91) == 1);
        assert(bench->validate(reply, n, 92) == 0);
        for (size_t j = 4; j < n; j++) assert(bench->validate(reply, j, 91) == -1);
        for (size_t j = 24; j < n; j++) {
            reply[j] ^= 1;
            assert(bench->validate(reply, n, 91) == -1);
            reply[j] ^= 1;
        }
    }
    /* Schema registry drives coverage; mutate every opaque response byte. */
#define CHECK_ECHO(label, size, procedure, codec) { \
        uint8_t call[1473], reply[1457]; \
        const struct benchmark *b = benchmark_find(#label); \
        assert(b && b->encode(call, sizeof(call), 0xabcdef01) == 40+size); \
        assert(!b->encode(call, 39+size, 1)); \
        assert(get32(call+12)==ECHO_PROGRAM && get32(call+20)==procedure); \
        memset(reply, 0, sizeof(reply)); put32(reply, 0xabcdef01); put32(reply+4, 1); \
        memcpy(reply+24, call+40, size); \
        assert(b->validate(reply, 24+size, 0xabcdef01)==1); \
        assert(b->validate(reply, 24+size, 2)==0); \
        assert(b->validate(reply, 25+size, 0xabcdef01)==-1); \
        for(size_t j=24; j<24+size; j++) { \
            reply[j]^=1; assert(b->validate(reply, 24+size, 0xabcdef01)==-1); reply[j]^=1; \
        } \
        assert(make_udp(p, &a, &b_endpoint, call, 40+size)<=1514); \
    }
    struct endpoint b_endpoint=b;
    ECHO_VARIANTS(CHECK_ECHO)
    puts("UDP framing and add/mul codecs: passed");
}
