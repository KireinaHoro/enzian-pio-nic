// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu
// Copyright (c) 2025 Zikai Liu
#include "common.h"

int do_fpi_irq_activate(void *data) {
    unsigned irq_no = (u64)data;
    enable_percpu_irq(irq_no, 0);
    return 0;
}

int do_fpi_irq_deactivate(void *data) {
    unsigned irq_no = (u64)data;
    disable_percpu_irq(irq_no);
    return 0;
}

// Module initialization
static int __init mod_init(void) {
  int err;
  probe_versions();

  err = init_workers();
  if (err != 0) {
    pr_err("init_workers failed: err = %d\n", err);
    deinit_workers();
    return -1;
  }

  err = init_bypass();
  if (err != 0) {
    pr_err("init_bypass failed: err = %d\n", err);
    deinit_bypass();
    return -1;
  }

  err = create_devices();
  if (err != 0) {
    pr_err("create_devices failed: err = %d\n", err);
    remove_devices();
    return -1;
  }

  init_datapath();

  pr_info("Lauberhorn initialized\n");
  return 0;
}

// Module exit
static void __exit mod_exit(void) {
  pr_info("Lauberhorn exiting...\n");

  remove_devices();
  deinit_workers();
  deinit_bypass();
  deinit_datapath();

  pr_info("Lauberhorn unloaded\n");
}

MODULE_AUTHOR("Pengcheng Xu");
MODULE_AUTHOR("Zikai Liu");
MODULE_DESCRIPTION("Lauberhorn kernel driver");
MODULE_LICENSE("Dual BSD/GPL");

module_init(mod_init);
module_exit(mod_exit);
