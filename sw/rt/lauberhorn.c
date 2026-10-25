// Lauberhorn userspace runtime: init/fini and global state

#include "lauberhorn.h"

#include "config.h"
#include "fiber/fiber_pool.h"
#include "ioctl.h"
#include "lauberhorn_state.h"
#include "log.h"
#include "pending_call_table.h"
#include "srv_table.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#define LAUBERHORN_DEV_PATH "/dev/lauberhorn"

volatile sig_atomic_t lauberhorn_is_running;
int lauberhorn_fd;
struct pending_call_table pending_call_table;
struct srv_table srv_table;

static void shutdown_signal(int signo) {
    /* Unsupported exit fences the domain; the kernel retains unresolved
     * resources and lifetime identities until full coherence reset. */
    _exit(128 + signo);
}

int lauberhorn_init(lauberhorn_t *ctx) {
#if LH_ENABLE_TRACING
    server_trace_init();
#endif
    ctx->_workers = 0;
    ctx->_created = 0;
    ctx->_declared_workers = 0;
    ctx->_watchdog_enabled = false;
    struct sigaction sa = {
        .sa_handler = shutdown_signal,
        .sa_flags = SA_RESTART, // resume join in main
    };
    int err;


    LOG("initializing");

    ctx->fd = open(LAUBERHORN_DEV_PATH, O_RDWR);
    lauberhorn_fd = ctx->fd;
    if (ctx->fd < 0) {
        PERROR("open device file");
        return -1;
    }

    uint32_t backend;
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_BACKEND, &backend) ||
        backend != (LH_BACKEND_VM ? LAUBERHORN_BACKEND_VM : LAUBERHORN_BACKEND_ECI)) {
        close(ctx->fd);
        ctx->fd = -1;
        errno = EPROTO;
        return -1;
    }

    ctx->_aliases = mmap(NULL, LAUBERHORN_NUM_WORKER_CORES * LAUBERHORN_ECI_CORE_OFFSET,
        PROT_READ | PROT_WRITE, MAP_SHARED, ctx->fd, 0);
    if (ctx->_aliases == MAP_FAILED) {
        close(ctx->fd);
        return -1;
    }

    // register SIGINT handler
    err = sigaction(SIGINT, &sa, NULL);
    if (!err) err = sigaction(SIGTERM, &sa, NULL);
    if (err) {
        PERROR("handle SIGINT");
        munmap(ctx->_aliases, LAUBERHORN_NUM_WORKER_CORES * LAUBERHORN_ECI_CORE_OFFSET);
        close(ctx->fd);
        return -1;
    }

    // initialize global state
    pending_call_table_init(&pending_call_table);
    srv_table_init(&srv_table);

    LOG("app initialized");
    return 0;
}

int lauberhorn_set_worker_count(lauberhorn_t *ctx, uint32_t count) {
    if (!ctx || !count || count > LAUBERHORN_NUM_WORKER_CORES) { errno = EINVAL; return -1; }
    if (ctx->_declared_workers || ctx->_created) { errno = EBUSY; return -1; }
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_SET_WORKER_COUNT, &count)) return -1;
    ctx->_declared_workers = count;
    return 0;
}

int lauberhorn_set_watchdog(lauberhorn_t *ctx, uint32_t limit_us) {
    if (!ctx) { errno = EINVAL; return -1; }
    if (ctx->_workers) { errno = EBUSY; return -1; }
    lauberhorn_watchdog_t cfg = {.limit_us = limit_us};
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_WATCHDOG, &cfg)) return -1;
    ctx->_watchdog_enabled = limit_us != 0;
    return 0;
}
int lauberhorn_stop(lauberhorn_t *ctx) {
    if (!ctx) { errno = EINVAL; return -1; }
    return ioctl(ctx->fd, LAUBERHORN_IOCTL_STOP_PROCESS);
}

void lauberhorn_fini(lauberhorn_t *ctx) {
#if LH_ENABLE_TRACING
    server_trace_fini();
#endif
    munmap(ctx->_aliases, LAUBERHORN_NUM_WORKER_CORES * LAUBERHORN_ECI_CORE_OFFSET);
    ctx->_aliases = NULL;
    close(ctx->fd);
    ctx->fd = -1;
}

int lauberhorn_reg_srv(lauberhorn_t *ctx, lauberhorn_handler_t handler,
                       uint32_t prog_num, uint32_t prog_ver,
                       uint32_t proc_num, uint16_t listen_port,
                       rpc_codec_t *codec) {
    if (!ctx || !handler.func || !codec || !codec->ops ||
        !codec->ops->unmarshal_call || !codec->ops->marshal_resp ||
        !codec->ops->alloc || !codec->ops->free) {
        errno = EINVAL;
        return -1;
    }
    int err;

    // atomically acquire a slot in the service registry and populate it
    struct srv *srv_reg = srv_table_acquire(&srv_table);
    if (!srv_reg) {
        PERROR("no more free srv slots");
        return -1;
    }
    srv_reg->codec = *codec;
    srv_reg->prog_num = prog_num;
    srv_reg->prog_ver = prog_ver;

    // Create hardware handler
    struct lauberhorn_hw_handler *hw = calloc(1, sizeof(*hw));
    if (!hw) {
        PERROR("allocate hw handler");
        srv_table_release(&srv_table, srv_reg);
        return -1;
    }

    hw->handler = handler;
    hw->srv = srv_reg;
    srv_reg->hw = hw;

    // Register with hardware
    lauberhorn_reg_srv_t cmd = {.prog_num = prog_num,
                                .prog_ver = prog_ver,
                                .proc_num = proc_num,
                                .port = listen_port,
                                .func_ptr = hw};

    err = ioctl(ctx->fd, LAUBERHORN_IOCTL_REG_SRV, &cmd);
    if (err) {
        PERROR("register service with hardware");
        goto free_hw;
    }

    // Register this service with the local port mapper, so that the remote
    // client can find it automatically
    // pmap_set returns 1 on succes, 0 on failure
    err = pmap_set(prog_num, prog_ver, IPPROTO_UDP, listen_port);
    if (!err) {
        LOG("failed to register with portmapper");
        goto dereg_srv;
    }
    srv_reg->srv_id = cmd.id;
    return srv_reg->idx;

dereg_srv: {
    lauberhorn_dereg_srv_t dereg = {.id = cmd.id};
    if (ioctl(ctx->fd, LAUBERHORN_IOCTL_DEREG_SRV, &dereg)) {
        /* Retain callable storage when hardware deregistration failed. */
        srv_reg->srv_id = cmd.id;
        return -1;
    }
}

free_hw:
    free(hw);
    srv_table_release(&srv_table, srv_reg);
    return -1; 
}

// can only be called once after successful registration
// anything else is undefined behavior
int lauberhorn_dereg_srv(lauberhorn_t *ctx, int srv_id) {
    int err;

    struct srv *srv_reg = srv_table_get(&srv_table, srv_id);

    if (!srv_reg) {
        LOG("noregistered service for srv_id %d", srv_id);
        return -1;
    }

    // Deregister with portmapper
    // pmap_unset returns 1 on success, 0 on failure
    err = pmap_unset(srv_reg->prog_num, srv_reg->prog_ver);
    if (!err) {
        LOG("failed to deregister with portmapper");
    }

    // Deregister with hardware
    lauberhorn_dereg_srv_t cmd = {.id = srv_reg->srv_id};
    err = ioctl(ctx->fd, LAUBERHORN_IOCTL_DEREG_SRV, &cmd);
    if (err) {
        PERROR("deregister service");
        return -1;
    }

    // unsafe !
    if (srv_reg->hw) {
        free(srv_reg->hw);
        srv_reg->hw = NULL;
    }
    srv_table_release(&srv_table, srv_reg);

    return err ? -1 : 0;
}
