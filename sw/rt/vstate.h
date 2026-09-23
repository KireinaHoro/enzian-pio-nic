#ifndef LAUBERHORN_RT_VSTATE_H
#define LAUBERHORN_RT_VSTATE_H

#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>


typedef _Atomic uint64_t vstate_t;
typedef uint32_t vgen_t;

// 8 possible states, 32 bit generation counter
#define VSTATE_BITS 3
#define VSTATE_STATE(w) ((uint32_t)((w) & ((1u << VSTATE_BITS) - 1)))
#define VSTATE_GEN(w) ((vgen_t)((w) >> VSTATE_BITS))
#define VSTATE_MAKE(gen, state) (((uint64_t)(gen) << VSTATE_BITS) | (uint32_t)(state))

static inline uint64_t vstate_load(vstate_t *v) {
    return atomic_load_explicit(v, memory_order_acquire);
}

static inline uint32_t vstate_state(vstate_t *v) {
    return VSTATE_STATE(vstate_load(v));
}

static inline vgen_t vstate_gen(vstate_t *v) {
    return VSTATE_GEN(vstate_load(v));
}

static inline void vstate_init(vstate_t *v, uint32_t state) {
    atomic_store_explicit(v, VSTATE_MAKE(0, state), memory_order_release);
}

static inline void vstate_set(vstate_t *v, uint32_t state) {
    uint64_t w = vstate_load(v);
    atomic_store_explicit(v, VSTATE_MAKE(VSTATE_GEN(w), state),
                          memory_order_release);
}

static inline bool vstate_cas(vstate_t *v, vgen_t gen, uint32_t expected,
                              uint32_t desired) {
    uint64_t exp = VSTATE_MAKE(gen, expected);
    uint64_t des = VSTATE_MAKE(gen, desired);
    return atomic_compare_exchange_strong_explicit(
        v, &exp, des, memory_order_acq_rel, memory_order_acquire);
}

static inline bool vstate_cas_state(vstate_t *v, uint32_t expected, uint32_t desired) {
    vgen_t gen = vstate_gen(v);
    uint64_t exp = VSTATE_MAKE(gen, expected);
    uint64_t des = VSTATE_MAKE(gen, desired);
    return atomic_compare_exchange_strong_explicit(
        v, &exp, des, memory_order_acq_rel, memory_order_acquire);

}

static inline void vstate_reinit(vstate_t *v, uint32_t state) {
    uint64_t w = vstate_load(v);
    atomic_store_explicit(v, VSTATE_MAKE(VSTATE_GEN(w) + 1, state),
                          memory_order_release);
}

#endif // LAUBERHORN_RT_VSTATE_H
