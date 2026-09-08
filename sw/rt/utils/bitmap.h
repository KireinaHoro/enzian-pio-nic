#ifndef LAUBERHORN_RT_BITMAP_H
#define LAUBERHORN_RT_BITMAP_H

#include <stdint.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <assert.h>


typedef struct {
    _Atomic uint64_t *words; 
    _Atomic uint64_t free_words_hint; 
    uint64_t n_words; // size in steps of 64
} bitmap_t; 

void bitmap_init(bitmap_t *m, int n_words);

void bitmap_destroy(bitmap_t *m);

void bitmap_release(bitmap_t *m, int idx);

int bitmap_acquire(bitmap_t *m);

#endif // LAUBERHORN_RT_BITMAP_H
