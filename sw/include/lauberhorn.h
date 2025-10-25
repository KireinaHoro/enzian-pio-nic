#ifndef LAUBERHORN_H
#define LAUBERHORN_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "eci/core.h"

typedef struct {
  int fd; // to /dev/lauberhorn

  uint8_t *parity_page;
} lauberhorn_t;

typedef struct {

} lauberhorn_schema_t;

// Handler function takes two parameters: app data, request data
typedef void (*lauberhorn_handler_t)(void *, void *);

// Register application
int lauberhorn_init(lauberhorn_t *ctx);
void lauberhorn_fini(lauberhorn_t *ctx);

// Register service handler
int lauberhorn_reg_srv(lauberhorn_t *ctx, lauberhorn_handler_t func, void *data,
                       int prog_num, int prog_ver, int proc_num,
                       uint16_t listen_port, lauberhorn_schema_t *schema);

int lauberhorn_dereg_srv(lauberhorn_t *ctx, int srv_id);

// Create worker thread (spins and runs handler function)
int lauberhorn_create_worker(lauberhorn_t *ctx);

#endif // LAUBERHORN_H