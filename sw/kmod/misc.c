// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

#include "common.h"

#include "lauberhorn_eci_profiler_dev.h"
#include "eci/config.h"
#include "eci/regblock_bases.h"

#define SHELL_REGS_BASE (0x97EFFFFFF000UL)
#define SHELL_REGS_VERSION_ADDR (0x3fe)

int probe_versions(void)
{
	u64 nic_ver;
	u32 shell_ver;
	lauberhorn_eci_profiler_t prof_dev;
	u32 *shell_regs_virt;

	union {
		u64 v;
		char str[9];
	} magic_str_cast;
	magic_str_cast.str[8] = '\0';

	// check static shell version
	// Map only one page
	shell_regs_virt = ioremap(SHELL_REGS_BASE, PAGE_SIZE);
	if (!shell_regs_virt) {
		pr_err("Failed to map static shell registers!\n");
		return -1;
	}

	shell_ver = ioread32(&shell_regs_virt[SHELL_REGS_VERSION_ADDR]);
	pr_info("Static shell version: %04x\n", shell_ver);

	iounmap(shell_regs_virt);

	// check running hardware magic and version
	lauberhorn_eci_profiler_initialize(&prof_dev,
					   LAUBERHORN_ECI_PROFILER_BASE);

	magic_str_cast.v = lauberhorn_eci_profiler_magic_rd(&prof_dev);
	if (strcmp(magic_str_cast.str, "LBERHORN") != 0) {
		pr_err("Unexpected magic string: %s\n", magic_str_cast.str);
		return -1;
	}

	nic_ver = lauberhorn_eci_profiler_git_version_rd(&prof_dev);
	pr_info("Lauberhorn NIC version: %08llx\n", nic_ver);

	return 0;
}

int do_fpi_irq_activate(void *data)
{
	unsigned irq_no = (u64)data;
	enable_percpu_irq(irq_no, 0);
	return 0;
}

int do_fpi_irq_deactivate(void *data)
{
	unsigned irq_no = (u64)data;
	disable_percpu_irq(irq_no);
	return 0;
}

void __iomem *io_base_node1;
void *mem_base_node1;

int map_node1(void)
{
	pr_info("Mapping I/O memory from node 1\n");

	// Map
	io_base_node1 = ioremap(STATIC_SHELL_IO_BASE, STATIC_SHELL_IO_SIZE);
	if (!io_base_node1) {
		pr_err("Failed to map Lauberhorn registers!\n");
		return -1;
	}

	mem_base_node1 = memremap(FPGA_MEM_BASE, FPGA_MEM_SIZE, MEMREMAP_WB);
	if (!mem_base_node1) {
		pr_err("Failed to map Lauberhorn memory area!\n");
		return -1;
	}

	return 0;
}