// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

#include "common.h"

#include "lauberhorn_eci_profiler.h"
#include "eci/config.h"
#include "eci/regblock_bases.h"

#define SHELL_REGS_BASE (0x97EFFFFFF000UL)
#define SHELL_REGS_VERSION_ADDR (0xff8)

int probe_versions(void) {
  u64 nic_ver, shell_ver;
  lauberhorn_eci_profiler_t prof_dev;

  union {
    u64 v;
    char str[9];
  } magic_str_cast;
  magic_str_cast.str[8] = '\0';

  pr_info("Lauberhorn kernel module init (compiled for %s)\n", LAUBERHORN_SW_GIT_HASH);

  // check static shell version
  shell_ver = ioread64(phys_to_virt(SHELL_REGS_BASE + SHELL_REGS_VERSION_ADDR));
  pr_info("Static shell version: %08llx\n", shell_ver);

  // check running hardware magic and version
  lauberhorn_eci_profiler_initialize(&prof_dev, LAUBERHORN_ECI_PROFILER_BASE);

  magic_str_cast.v = lauberhorn_eci_profiler_magic_rd(&prof_dev);
  if (strcmp(magic_str_cast.str, "LBERHORN") != 0) {
    pr_err("Unexpected magic string: %s\n", magic_str_cast.str);
    return -1;
  }

  nic_ver = lauberhorn_eci_profiler_git_version_rd(&prof_dev);
  pr_info("Lauberhorn NIC version: %08llx\n", nic_ver);

  return 0;
}