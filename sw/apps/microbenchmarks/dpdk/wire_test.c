#include <assert.h>
#include <stdio.h>
#include "wire.h"
#include "benchmark.h"
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
    puts("UDP framing and add/mul codecs: passed");
}
