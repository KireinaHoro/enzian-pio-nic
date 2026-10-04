#include "benchmark.h"
#include "wire.h"
static size_t rpc_call(uint8_t *p, size_t cap, uint32_t id, uint32_t prog) {
    if(cap<52) return 0;
    memset(p,0,52);
    put32(p,id); put32(p+8,2); put32(p+12,prog);
    put32(p+16,1); put32(p+20,1);
    put32(p+40,id); put32(p+44,7); put32(p+48,11);
    return 52;
}
static size_t add_encode(uint8_t *p,size_t cap,uint32_t id) {
    return rpc_call(p,cap,id,0x23451111);
}
static size_t mul_encode(uint8_t *p,size_t cap,uint32_t id) {
    return rpc_call(p,cap,id,0x23451112);
}
static int rpc_reply(const uint8_t *r,size_t n,uint32_t id,int32_t expected) {
    if(n<4 || get32(r)!=id) return 0;
    if(n<32 || get32(r+4)!=1 || get32(r+8)!=0) return -1;
    uint32_t vl=get32(r+16);
    if(vl>400) return -1;
    size_t off=20+((vl+3)&~3u);
    if(off+12!=n || get32(r+off)!=0 || get32(r+off+4)!=id ||
       (int32_t)get32(r+off+8)!=expected) return -1;
    return 1;
}
static int add_validate(const uint8_t *p,size_t n,uint32_t id) { return rpc_reply(p,n,id,18); }
static int mul_validate(const uint8_t *p,size_t n,uint32_t id) { return rpc_reply(p,n,id,77); }
/* Transport calibration only: pair with a UDP echo responder or NIC loopback. */
static size_t echo_encode(uint8_t *p,size_t cap,uint32_t id) {
    if(cap<64) return 0;
    for(unsigned i=0;i<64;i++) p[i]=(uint8_t)(id+i);
    put32(p,id);
    return 64;
}
static int echo_validate(const uint8_t *p,size_t n,uint32_t id) {
    if(n<4 || get32(p)!=id) return 0;
    uint8_t expected[64]; echo_encode(expected,sizeof(expected),id);
    return n==sizeof(expected) && !memcmp(p,expected,n)?1:-1;
}
const struct benchmark benchmarks[]={
    {"add",add_encode,add_validate}, {"mul",mul_encode,mul_validate},
    {"echo",echo_encode,echo_validate},
};
const size_t benchmark_count=sizeof(benchmarks)/sizeof(benchmarks[0]);
const struct benchmark *benchmark_find(const char *name) {
    for(size_t i=0;i<benchmark_count;i++) if(!strcmp(name,benchmarks[i].name)) return &benchmarks[i];
    return NULL;
}
