// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

#include "common.h"

// Module initialization
static int __init mod_init(void)
{
	int err;

	pr_info("Lauberhorn kernel module init\n");

	err = map_node1();
	if (err != 0) {
		goto out;
	}

	err = probe_versions();
	if (err != 0) {
		pr_err("init_workers failed: err = %d\n", err);
		goto unmap;
	}

	err = init_workers();
	if (err != 0) {
		pr_err("init_workers failed: err = %d\n", err);
		goto unmap;
	}

	err = init_bypass();
	if (err != 0) {
		pr_err("init_bypass failed: err = %d\n", err);
		goto workers;
	}

	err = create_devices();
	if (err != 0) {
		pr_err("create_devices failed: err = %d\n", err);
		goto bypass;
	}

	pr_info("Lauberhorn initialized\n");
	return 0;

bypass:
	deinit_bypass();
workers:
	deinit_workers();
unmap:
	unmap_node1();
out:
	return -1;
}

// Module exit
static void __exit mod_exit(void)
{
	pr_info("Lauberhorn exiting...\n");

	remove_devices();
	deinit_workers();
	deinit_bypass();
	unmap_node1();

	pr_info("Lauberhorn unloaded\n");
}

MODULE_AUTHOR("Pengcheng Xu");
MODULE_AUTHOR("Zikai Liu");
MODULE_DESCRIPTION("Lauberhorn kernel driver");
MODULE_LICENSE("Dual BSD/GPL");

module_init(mod_init);
module_exit(mod_exit);
