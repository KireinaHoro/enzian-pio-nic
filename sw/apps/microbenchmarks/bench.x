struct bench_call {
    unsigned int request_id;
    int a;
    int b;
};

struct bench_resp {
    unsigned int request_id;
    int result;
};

program ADD_PROG {
    version ADD_VERS {
        bench_resp ADD(bench_call) = 1;
    } = 1;
} = 0x23451111;

program MUL_PROG {
    version MUL_VERS {
        bench_resp MUL(bench_call) = 1;
    } = 1;
} = 0x23451112;

/* Paper echo workloads. Body sizes exclude the ONC-RPC envelope; correlation
 * uses the envelope XID even for the true void request and response. */
typedef opaque echo8[8];
typedef opaque echo128[128];
typedef opaque echo1432[1432];
program ECHO_PROG {
    version ECHO_VERS {
        void ECHO0(void) = 1;
        echo8 ECHO8(echo8) = 2;
        echo128 ECHO128(echo128) = 3;
        echo1432 ECHO1432(echo1432) = 4;
    } = 1;
} = 0x23451114;
