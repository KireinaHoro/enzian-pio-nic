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
