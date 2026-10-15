#include "benchmark.h"
#include "wire.h"
#include "echo_registry.h"
/* The payload pattern repeats every 256 IDs. Prepare its byte ramp before
 * measurement; memcpy/memcmp still generate and check every payload byte. */
#define RAMP16(b) (b),(b)+1,(b)+2,(b)+3,(b)+4,(b)+5,(b)+6,(b)+7, \
                  (b)+8,(b)+9,(b)+10,(b)+11,(b)+12,(b)+13,(b)+14,(b)+15
#define RAMP256 RAMP16(0),RAMP16(16),RAMP16(32),RAMP16(48), \
                RAMP16(64),RAMP16(80),RAMP16(96),RAMP16(112), \
                RAMP16(128),RAMP16(144),RAMP16(160),RAMP16(176), \
                RAMP16(192),RAMP16(208),RAMP16(224),RAMP16(240)
static const uint8_t loopback_pattern[]={RAMP256,RAMP256,RAMP256,RAMP256,RAMP256,RAMP256,RAMP256};
#undef RAMP256
#undef RAMP16
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
/* Fixed opaque XDR bodies: sizes exclude the ONC-RPC envelope. */
static size_t loopback_encode(uint8_t *p, size_t cap, uint32_t id, size_t size) {
    if (cap < 40 + size) return 0;
    memset(p, 0, 40);
    put32(p, id); put32(p + 8, 2); put32(p + 12, 0x23451113);
    put32(p + 16, 1); put32(p + 20, 1);
    memcpy(p+40,loopback_pattern+(id&255),size);
    put32(p + 40, id);
    return 40 + size;
}
static int loopback_validate(const uint8_t *p, size_t n, uint32_t id, size_t size) {
    if (n < 4 || get32(p) != id) return 0;
    if (n != 24 + size || get32(p + 4) != 1 || get32(p + 8) ||
        get32(p + 12) || get32(p + 16) || get32(p + 20)) return -1;
    if(get32(p+24)!=id) return -1;
    return memcmp(p+28,loopback_pattern+(id&255)+4,size-4) ? -1 : 1;
}
#define LOOPBACK(N) \
static size_t loopback_##N##_encode(uint8_t *p, size_t cap, uint32_t id) { \
    return loopback_encode(p, cap, id, N); \
} \
static int loopback_##N##_validate(const uint8_t *p, size_t n, uint32_t id) { \
    return loopback_validate(p, n, id, N); \
}
LOOPBACK(8)
LOOPBACK(64)
LOOPBACK(128)
LOOPBACK(512)
LOOPBACK(1432)
#define LOOPBACK_ENTRY(N) {"loopback" #N, loopback_##N##_encode, loopback_##N##_validate}
/* Schema-defined RPC echo. No application-level request ID or length word. */
#define RPC_ECHO(label, size, procedure, codec) \
static size_t label##_encode(uint8_t *p, size_t cap, uint32_t id) { \
    if (cap < 40 + size) return 0; \
    memset(p, 0, 40); put32(p, id); put32(p+8, 2); \
    put32(p+12, ECHO_PROGRAM); put32(p+16, ECHO_VERSION); put32(p+20, procedure); \
    memcpy(p+40, loopback_pattern+(id&255), size); return 40+size; \
} \
static int label##_validate(const uint8_t *p, size_t n, uint32_t id) { \
    if (n < 4 || get32(p) != id) return 0; \
    if (n == 24 && get32(p+4)==1 && !get32(p+8) && !get32(p+12) && \
        !get32(p+16) && get32(p+20)==5) return 2; \
    if (n != 24+size || get32(p+4)!=1 || get32(p+8) || get32(p+12) || \
        get32(p+16) || get32(p+20)) return -1; \
    return memcmp(p+24, loopback_pattern+(id&255), size) ? -1 : 1; \
}
ECHO_VARIANTS(RPC_ECHO)
#define RPC_ECHO_ENTRY(label, size, procedure, codec) {#label, label##_encode, label##_validate},
const struct benchmark benchmarks[]={
    {"add",add_encode,add_validate}, {"mul",mul_encode,mul_validate},
    {"echo",echo_encode,echo_validate},
    ECHO_VARIANTS(RPC_ECHO_ENTRY)
    LOOPBACK_ENTRY(8), LOOPBACK_ENTRY(64), LOOPBACK_ENTRY(128),
    LOOPBACK_ENTRY(512), LOOPBACK_ENTRY(1432),
};
const size_t benchmark_count=sizeof(benchmarks)/sizeof(benchmarks[0]);
const struct benchmark *benchmark_find(const char *name) {
    for(size_t i=0;i<benchmark_count;i++) if(!strcmp(name,benchmarks[i].name)) return &benchmarks[i];
    return NULL;
}
