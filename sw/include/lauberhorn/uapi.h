/* Lauberhorn user-space API */

#ifndef __LAUBERHOEN_UAPI_H__
#define __LAUBERHOEN_UAPI_H__

// Hidden structs
struct lauberhorn_dev;
struct lauberhorn_srv;
struct lauberhorn_thd;

/**
    Handle for the Lauberhorn device.
 */
typedef struct lauberhorn_dev *lauberhorn_dev_t;

/**
    Open the Lauberhorn device.
    @param dev: output device handle
    @return 0 on success.
 */
int lauberhorn_dev_open(lauberhorn_dev_t *dev);

/**
    Close the Lauberhorn device.
 */
void lauberhorn_dev_close(lauberhorn_dev_t *dev);


/**
    Handle for a Lauberhorn service.
 */
typedef struct lauberhorn_srv *lauberhorn_srv_t;

/**
    Register a serivce.
    @param srv: output serivce handle
    @param dev: input device handle
    @param prog_num: program number
    @param prog_ver: program version
    @param proc_num: process number
    @param func_ptr: function pointer
    @param listen_port: listen port number
    @return 0 on success
 */
int lauberhorn_onc_srv_register(lauberhorn_srv_t *srv, lauberhorn_dev_t dev, int prog_num, int prog_ver, int proc_num, void *func_ptr, int listen_port);


/**
    Deregister a service.
 */
int lauberhorn_onc_srv_deregister(lauberhorn_srv_t *srv);

/**
    Handle of a Lauberhorn client thread (process).
 */
typedef struct lauberhorn_thd *lauberhorn_thd_t;



#endif  // __LAUBERHOEN_UAPI_H__