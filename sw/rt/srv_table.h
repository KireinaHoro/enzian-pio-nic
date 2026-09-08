#ifndef LAUBERHORN_RT_SCHEMA_H
#define LAUBERHORN_RT_SCHEMA_H

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>

#include "config.h"
#include "lauberhorn.h"
#include "utils/bitmap.h"

// allocate 64 slots for now
// (even though less hw slots to make bitmap allocator happy)
// as it must currently be sized in multiple of 64
#define SRV_TABLE_SIZE 64

// holds all registered services
struct srv {
    // runtime-owned pointer to the allocated HW handler for this service
    struct lauberhorn_hw_handler *hw;

    int idx;
    rpc_codec_t codec;
    // service_id returned by hw on registration, used for deregistration
    int srv_id; 
    int prog_num;
    int prog_ver;
};

struct srv_table {
    struct srv srvs[SRV_TABLE_SIZE];
    bitmap_t bitmap; 
};

struct srv *srv_table_acquire(struct srv_table *tbl);
void srv_table_release(struct srv_table *tbl, struct srv *sreg);
struct srv *srv_table_get(struct srv_table *tbl, int idx);
void srv_table_init(struct srv_table *tbl);

#endif // LAUBERHORN_RT_SCHEMA_H
