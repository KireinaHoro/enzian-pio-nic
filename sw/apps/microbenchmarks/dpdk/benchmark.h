/* Add a codec to benchmarks[]; transport, timestamps, timeouts and CSV stay shared.
 * Callbacks never retain buffers. IDs are unique across warmup/measurement.
 * encode returns UDP payload size or zero on error; validate: 0 unrelated,
 * -1 invalid matching reply, +1 correct reply, +2 valid SYSTEM_ERR rejection. No allocation/I/O in callbacks. */
#ifndef BENCHMARK_H
#define BENCHMARK_H
#include <stddef.h>
#include <stdint.h>
struct benchmark {
    const char *name;
    size_t (*encode)(uint8_t *out, size_t capacity, uint32_t id);
    int (*validate)(const uint8_t *reply, size_t length, uint32_t id);
};
extern const struct benchmark benchmarks[];
extern const size_t benchmark_count;
const struct benchmark *benchmark_find(const char *name);
#endif
