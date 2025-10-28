struct add_call {
    int a;
    int b;
};

struct add_resp {
    int sum;
};

program ADD_PROG {
    version ADD_VERS {
        add_resp ADD(add_call) = 1;
    } = 1;
} = 0x23451111;
