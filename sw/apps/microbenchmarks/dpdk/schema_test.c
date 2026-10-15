#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../bench.h"
#include "echo_registry.h"
#include "benchmark.h"
/* Compare raw packet body to rpcgen's real XDR codecs, including void. */
static bool_t test_void(XDR *x, char *data) {
    (void)x; (void)data; return xdr_void();
}
#define xdr_void test_void
int main(void) {
#define CHECK(label, size, procedure, codec) { \
    char data[1432], encoded[1432]; uint8_t call[1472]; \
    const struct benchmark *b=benchmark_find(#label); \
    for(unsigned id=1;id<=256;id++) { \
        assert(b->encode(call,sizeof(call),id)==40+size); \
        memcpy(data,call+40,size); XDR x; \
        xdrmem_create(&x,encoded,sizeof(encoded),XDR_ENCODE); \
        assert(codec(&x,data)); \
        assert(xdr_getpos(&x)==size && !memcmp(encoded,call+40,size)); \
        xdr_destroy(&x); \
        memset(data,0,sizeof(data)); \
        xdrmem_create(&x,encoded,size,XDR_DECODE); \
        assert(codec(&x,data)); \
        assert(xdr_getpos(&x)==size && !memcmp(data,call+40,size)); \
        xdr_destroy(&x); \
    } \
}
    ECHO_VARIANTS(CHECK)
    puts("rpcgen/raw-wire echo compatibility: passed");
}
