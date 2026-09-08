#ifndef LAUBERHORN_RT_FUTURE_H
#define LAUBERHORN_RT_FUTURE_H

#include <stdatomic.h>
#include <stdint.h>
#include <stdbool.h>
#include "lauberhorn/fiber_handle.h"
#include "lauberhorn/rpc.h"
#include "lauberhorn/common.h"

typedef enum {
    LAUBERHORN_RPC_SUCCESS = 0,
    LAUBERHORN_RPC_TIMEOUT,
    LAUBERHORN_RPC_ERROR,
    LAUBERHORN_RPC_CANCELLED,
} lauberhorn_rpc_status_t;

typedef struct {
	void *data;
	rpc_codec_t *codec;
    lauberhorn_rpc_status_t code;
} rpc_result_t;

#define LAUBERHORN_FUTURE_RESULT(f) ((f)->result)
#define LAUBERHORN_FUTURE_OK(f) ((f)->result.code == LAUBERHORN_RPC_SUCCESS)
#define LAUBERHORN_FUTURE_TIMEOUT(f) ((f)->result.code == LAUBERHORN_RPC_TIMEOUT)

typedef struct {
    // waiter
    struct fiber_handle owner; 

    uint32_t xid; 
    rpc_result_t result; 

    // where to extract result from
    struct pending_call *c; 

} lauberhorn_future_t; 

typedef struct {
    lauberhorn_future_t **f;
    int n; 
    int cap; 
} lauberhorn_future_set_t; 

#define FUTURE_SET(name, size)                                              \
    lauberhorn_future_t *name##_slots[size];                                   \
    lauberhorn_future_set_t name = {                                           \
        .f = name##_slots, .n = 0, .cap = (size)                               \
    }                                                                           

#define future_set_add(s, ...)                                                \
    do {                                                                      \
        lauberhorn_future_t *_tmp[] = {__VA_ARGS__};                           \
        for (size_t _i = 0; _i < sizeof(_tmp)/sizeof(_tmp[0]); _i++)          \
            (s)->f[(s)->n++] = _tmp[_i];                                      \
    } while (0)

LAUBERHORN_API lauberhorn_future_t *future_set_await_any(lauberhorn_future_set_t *s);

// await a single future
LAUBERHORN_API void lauberhorn_future_await(lauberhorn_future_t *f);

// await any future and return its position in the array
LAUBERHORN_API int lauberhorn_future_select(lauberhorn_future_t **f, int n);

// await all futures
LAUBERHORN_API void lauberhorn_future_join(lauberhorn_future_t **f, int n);

// TODO: not implemented yet
LAUBERHORN_API bool lauberhorn_future_cancel(lauberhorn_future_t *f);


#endif // LAUBERHORN_RT_FIBER_EVENT_H