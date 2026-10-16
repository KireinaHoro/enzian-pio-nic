/* The instrumented PMD's scratch record is per-thread; the off build has none. */
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <rte_pmd_mlx5_lh.h>
static void *other(void *parent) {
    struct rte_pmd_mlx5_lh_sample *s=rte_pmd_mlx5_lh_sample();
    assert(s && s!=parent && s->rx_cqe==0);
    s->rx_cqe=42;
    return NULL;
}
int main(int argc,char **argv) {
    assert(argc==2);
    struct rte_pmd_mlx5_lh_sample *s=rte_pmd_mlx5_lh_sample();
    if(!atoi(argv[1])) { assert(!s); return 0; }
    assert(s);s->rx_cqe=17;
    pthread_t t;assert(!pthread_create(&t,NULL,other,s));assert(!pthread_join(t,NULL));
    assert(s->rx_cqe==17);
    return 0;
}
