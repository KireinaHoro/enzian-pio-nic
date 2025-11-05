#include <stdio.h>
#include <threads.h>

#include "lauberhorn.h"

#include "add.h"

// handler will be executed multi-threaded!
// can't just use a static variable
thread_local add_resp resp;
lauberhorn_msg_t add_handler(void *data, lauberhorn_msg_t req, int xid) {
  add_call *call = req;

  printf("server called with %d + %d\n", call->a, call->b);
  resp.sum = call->a + call->b;
  printf("returning %d\n", resp.sum);

  return &resp;
}

// TODO: ideally the user app shouldn't need to write this,
//       but instead can just provide one .x file with all handlers
//       and in addition some callbacks for init and cleanup.
int main(int argc, char *argv[]) {
  int err, srv_id, i;
  lauberhorn_t ctx;
  lauberhorn_schema_t add_schema = {
      .call_size = sizeof(add_call),
      .call_func = (xdrproc_t)xdr_add_call,
      .resp_func = (xdrproc_t)xdr_add_resp,
  };
  lauberhorn_worker_t workers[4];

  printf("Adder app init\n");

  err = lauberhorn_init(&ctx);
  if (err) {
    fprintf(stderr, "Failed to initialize Lauberhorn! err=%d\n", err);
    return EXIT_FAILURE;
  }

  // XXX: can't automatically register with port mapper
  err = lauberhorn_reg_srv(&ctx, add_handler, NULL, ADD_PROG, ADD_VERS, ADD,
                           12345, &add_schema);
  if (err < 0) {
    fprintf(stderr, "Failed to register service! err=%d\n", err);
    goto fini;
  }
  srv_id = err;

  // run 4 threads
  for (i = 0; i < 4; ++i) {
    workers[i] = lauberhorn_create_worker(&ctx, noop_cb, noop_cb);
    if (!workers[i]) {
      fprintf(stderr, "Failed to launch worker #%d!\n", i);
    }
  }

  // wait for workers to finish
  for (i = 0; i < 4; ++i) {
    lauberhorn_join_worker(&ctx, workers[i]);
  }

  printf("Done, cleaning up\n");

dereg:
  lauberhorn_dereg_srv(&ctx, srv_id);
fini:
  lauberhorn_fini(&ctx);
}