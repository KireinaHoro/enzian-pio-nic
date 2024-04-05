#include "debug.h"
#include "hal.h"

#include "regs.h"

#include <stdio.h>
#include <unistd.h>

void pionic_dump_glb_stats(pionic_ctx_t ctx) {
#define READ_PRINT(name) printf("%s\t: %#lx\n", #name, read64(ctx, name));
  READ_PRINT(PIONIC_GLOBAL_RX_OVERFLOW_COUNT)
#undef READ_PRINT
}

void pionic_dump_core_stats(pionic_ctx_t ctx, int cid) {
#define READ_PRINT(name) printf("core %d: %s\t: %#lx\n", cid, #name, read64(ctx, name(cid)));
  READ_PRINT(PIONIC_CONTROL_RX_PACKET_COUNT)
  READ_PRINT(PIONIC_CONTROL_TX_PACKET_COUNT)
  READ_PRINT(PIONIC_CONTROL_RX_DMA_ERROR_COUNT)
  READ_PRINT(PIONIC_CONTROL_TX_DMA_ERROR_COUNT)
  READ_PRINT(PIONIC_CONTROL_RX_ALLOC_OCCUPANCY_UP_TO_128)
  READ_PRINT(PIONIC_CONTROL_RX_ALLOC_OCCUPANCY_UP_TO_1518)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__ENTRY)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_QUEUE)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__READ_START)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_DMA_WRITE)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_READ)
  READ_PRINT(PIONIC_CONTROL_HOST_RX_LAST_PROFILE__AFTER_RX_COMMIT)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__ACQUIRE)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_TX_COMMIT)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__AFTER_DMA_READ)
  READ_PRINT(PIONIC_CONTROL_HOST_TX_LAST_PROFILE__EXIT)
#undef READ_PRINT
}

void pionic_reset_pkt_alloc(pionic_ctx_t ctx, int cid) {
  write64(ctx, PIONIC_CONTROL_ALLOC_RESET(cid), 1);
  usleep(1); // arbitrary
  write64(ctx, PIONIC_CONTROL_ALLOC_RESET(cid), 0);
}
