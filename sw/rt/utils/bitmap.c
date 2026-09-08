#include <stdint.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <assert.h>

#include "bitmap.h"

// Returns the number of trailing 0-bits in x,
// starting at the least significant bit position. If x is 0, the result is
// undefined. 
// https://gcc.gnu.org/onlinedocs/gcc/Bit-Operation-Builtins.html
static inline int ctzll(uint64_t x) {
#if defined(__GNUC__) || defined(__clang__)
	return __builtin_ctzll(x);
#else
	int idx = 0;
	while ((x & 1) == 0) {
		x >>= 1;
		idx++;
	}
	return idx;
#endif
}

static int get_first_zero(_Atomic uint64_t *w) {
    uint64_t inverted = ~atomic_load_explicit(w, memory_order_relaxed);
    if (inverted == 0)
        return -1;
    return ctzll(inverted);
}

// size % 64 == 0
void bitmap_init(bitmap_t *m, int n_words) {
    assert(n_words > 0 && n_words <= 64);
    m->n_words = n_words;
    m->words = calloc(n_words, sizeof(uint64_t));
    atomic_init(&m->free_words_hint, 0);
}

void bitmap_destroy(bitmap_t *m) {
    free(m->words);
}

static bool acquire_first_zero(_Atomic uint64_t *w, int *idx) {
    uint64_t w_old = atomic_load_explicit(w, memory_order_relaxed); 
    uint64_t w_new; 
    int found_idx = -1; 
    do {
        uint64_t inverted = ~w_old; 
        if (inverted == 0) {
            *idx = found_idx; 
            return false; 
        }
        found_idx = ctzll(inverted);
        w_new = w_old | (1ULL << found_idx);
    } while (!atomic_compare_exchange_weak_explicit(w, &w_old, w_new, memory_order_acquire, memory_order_relaxed));

    *idx = found_idx; 
    return true; 
}

void bitmap_release(bitmap_t *m, int idx) {
    int word_idx = idx >> 6;
    int bit_idx = idx & 63;

    atomic_fetch_and_explicit(&m->words[word_idx], ~(1ULL << bit_idx),
                              memory_order_release);

    if (atomic_load_explicit(&m->free_words_hint, memory_order_relaxed) & (1ULL << word_idx)) {
            atomic_fetch_and_explicit(&m->free_words_hint, ~(1ULL << word_idx),
                              memory_order_relaxed); 
    }
}

int bitmap_acquire(bitmap_t *m) {

    for (;;) {
        int free_word_idx = get_first_zero(&m->free_words_hint);

        // bitmap is supposedly full
        // check to make sure
        if (free_word_idx < 0 || free_word_idx >= m->n_words) {
            bool found = false; 
            for (uint64_t i = 0; i < m->n_words; i++) {
                if (~atomic_load_explicit(&m->words[i], memory_order_relaxed)) {
                    atomic_fetch_and_explicit(&m->free_words_hint, ~(1ULL << i), 
                        memory_order_relaxed);
                    found = true; 
                }
            }
            if (!found) {
                return -1; 
            }
            // reevaluate free_word_idx
            continue; 
        }

        int bit_idx;
        if (acquire_first_zero(&m->words[free_word_idx], &bit_idx)) {
            return (free_word_idx << 6) + bit_idx; 
        }
        // word was full. record hint
        atomic_fetch_or_explicit(&m->free_words_hint, 1ULL << free_word_idx, memory_order_relaxed);
    }
}