/* A parked worker must observe READY being restored by hardware. This is a
 * native atomic/progress regression, not an ECI coherence simulation. */
#include <assert.h>
#include <pthread.h>
#include <stdlib.h>
#include <unistd.h>
#include "config.h"
#include "cs.h"

static void *base;
static pthread_barrier_t start;
static void *restore_ready(void *unused)
{
    pthread_barrier_wait(&start);
    usleep(10000);
    atomic_store((atomic_u8 *)((char *)base + LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET), 2);
    return NULL;
}

int main(void)
{
    alarm(5); /* The old implementation spins forever on its initial value. */
    base = calloc(1, LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET + 8);
    assert(base);
    pthread_t thread;
    assert(!pthread_barrier_init(&start, NULL, 2));
    assert(!pthread_create(&thread, NULL, restore_ready, NULL));
    pthread_barrier_wait(&start);
    enter_cs(base);
    atomic_u8 *control = (atomic_u8 *)((char *)base + LAUBERHORN_ECI_PREEMPT_CTRL_OFFSET);
    assert(atomic_load(control) == 3);
    exit_cs(base);
    assert(atomic_load(control) == 2);
    assert(!pthread_join(thread, NULL));
    pthread_barrier_destroy(&start);
    free(base);
    return 0;
}
