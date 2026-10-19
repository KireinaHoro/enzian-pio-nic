/* Minimal Ethernet/IPv4/UDP + AUTH_NONE ONC-RPC benchmark codec.
 * Byte access is deliberate: wire offsets need not be naturally aligned. */
#ifndef BENCH_WIRE_H
#define BENCH_WIRE_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>
struct endpoint { uint8_t mac[6], ip[4]; uint16_t port; };
static inline uint32_t get32(const uint8_t *p) {
    return (uint32_t)p[0]<<24 | (uint32_t)p[1]<<16 | (uint32_t)p[2]<<8 | p[3];
}
static inline unsigned get16(const uint8_t *p) { return p[0]*256u+p[1]; }
static inline void put16(uint8_t *p, unsigned v) { p[0]=v>>8; p[1]=v; }
static inline void put32(uint8_t *p, uint32_t v) { put16(p,v>>16); put16(p+2,v); }
static inline uint16_t checksum(const uint8_t *p, size_t n, uint32_t sum) {
    while (n>1) { sum+=get16(p); p+=2; n-=2; }
    if(n) sum+=(unsigned)*p<<8;
    while(sum>>16) sum=(sum&65535)+(sum>>16);
    return (uint16_t)~sum;
}
/* Enclose a benchmark's UDP payload; caller supplies at least 42+len bytes. */
static inline size_t make_udp_header(uint8_t *p, const struct endpoint *src,
        const struct endpoint *dst, size_t len) {
    memset(p,0,42);
    memcpy(p,dst->mac,6); memcpy(p+6,src->mac,6); put16(p+12,0x0800);
    p[14]=0x45; put16(p+16,28+len); put16(p+20,0x4000);
    p[22]=64; p[23]=17; memcpy(p+26,src->ip,4); memcpy(p+30,dst->ip,4);
    put16(p+24,checksum(p+14,20,0));
    put16(p+34,src->port); put16(p+36,dst->port); put16(p+38,8+len);
    return 42+len;
}
static inline size_t make_udp(uint8_t *p, const struct endpoint *src,
        const struct endpoint *dst, const uint8_t *payload, size_t len) {
    memcpy(p+42,payload,len);
    return make_udp_header(p,src,dst,len);
}
/* 0 unrelated, -1 malformed endpoint packet, 1 valid UDP payload. */
static inline int parse_udp(const uint8_t *p, size_t n,
        const struct endpoint *src, const struct endpoint *dst,
        const uint8_t **payload, size_t *payload_len) {
    if(n<42 || get16(p+12)!=0x0800 || (p[14]>>4)!=4 || p[23]!=17) return 0;
    size_t ihl=(p[14]&15)*4, u=14+ihl;
    if(ihl<20 || n<u+8 || memcmp(p+26,dst->ip,4) || memcmp(p+30,src->ip,4)) return 0;
    if(get16(p+u)!=dst->port || get16(p+u+2)!=src->port) return 0;
    unsigned total=get16(p+16), len=get16(p+u+4);
    if(total<ihl+8 || 14+total>n || len<8 || len!=total-ihl ||
       (get16(p+20)&0x3fff) || checksum(p+14,ihl,0)) return -1;
    if(get16(p+u+6) && checksum(p+u,len,
        get16(p+26)+get16(p+28)+get16(p+30)+get16(p+32)+17+len)) return -1;
    *payload=p+u+8; *payload_len=len-8;
    return 1;
}
#endif
