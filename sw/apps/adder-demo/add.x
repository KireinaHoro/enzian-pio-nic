struct add_call {
    unsigned int request_id;
    int a;
    int b;
};

struct add_resp {
    unsigned int request_id;
    int sum;
};

program ADD_PROG {
    version ADD_VERS {
        add_resp ADD(add_call) = 1;
    } = 1;
} = 0x23451111;
