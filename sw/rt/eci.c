#include "ioctl.h"
#include "lauberhorn.h"

int lauberhorn_init(lauberhorn_t *ctx) {}

void lauberhorn_fini(lauberhorn_t *ctx) {}

// Register service handler
int lauberhorn_reg_srv(lauberhorn_t *ctx, lauberhorn_handler_t func, void *data,
                       int prog_num, int prog_ver, int proc_num,
                       uint16_t listen_port, lauberhorn_schema_t *schema) {}

int lauberhorn_dereg_srv(lauberhorn_t *ctx, int srv_id) {}

// Create worker thread (spins and runs handler function)
int lauberhorn_create_worker(lauberhorn_t *ctx) {}