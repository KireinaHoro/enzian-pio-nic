#include "srv_table.h"
#include "log.h"

struct srv *srv_table_acquire(struct srv_table *tbl) {
    int idx = bitmap_acquire(&tbl->bitmap);
    if (idx < 0) {
        LOG("no free srv slots available");
        return NULL;
    }
    return &tbl->srvs[idx];
}

void srv_table_release(struct srv_table *tbl, struct srv *srv) {
    int idx = srv - tbl->srvs;
    bitmap_release(&tbl->bitmap, idx);
}

struct srv *srv_table_get(struct srv_table *tbl, int idx) {
    return &tbl->srvs[idx];
}

void srv_table_init(struct srv_table *tbl) {
    bitmap_init(&tbl->bitmap, SRV_TABLE_SIZE / 64);
    for (int i = 0; i < SRV_TABLE_SIZE; i++) {
        tbl->srvs[i].srv_id = -1;
        tbl->srvs[i].prog_num = -1;
        tbl->srvs[i].prog_ver = -1;
        tbl->srvs[i].hw = NULL;
    }
}