#ifndef LAUBERHORN_RT_CS
#define LAUBERHORN_RT_CS

#include <stdatomic.h>

typedef _Atomic uint8_t atomic_u8;

static inline void enter_cs(void *base) {
  atomic_u8 *wc_base = (atomic_u8 *)base + LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET;

  // not using mackerel here since we need CAS

  // read once at the beginning
  uint8_t busy_ready = atomic_load(wc_base);

  while (true) {
    // not in critical section yet, BUSY must not be high
    assert(!(busy_ready & 0x1));
    if (busy_ready & 0x2) {
      // READY is set, set BUSY
      if (atomic_compare_exchange_weak(wc_base, &busy_ready,
                                       busy_ready ^ 0x1)) {
        // BUSY is set, now inside critical section
        return;
      }
    } // otherwise wait for READY
  }
}

static inline void exit_cs(void *base) {
  atomic_u8 *wc_base = (atomic_u8 *)base + LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET;

  uint8_t busy_ready = atomic_load(wc_base);

  while (true) {
    // already in critical section, BUSY must be high
    assert(busy_ready & 0x1);
    // unset BUSY
    if (atomic_compare_exchange_weak(wc_base, &busy_ready, busy_ready ^ 0x1)) {
      // BUSY is unset, now outside critical section
      return;
    }
  }
}

#endif