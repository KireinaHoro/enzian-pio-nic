// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2026 Pengcheng Xu

#include "common.h"

#include <linux/bitfield.h>
#include <linux/pci.h>

#define L2C_TAD_UNITS 8
#define PCI_VENDOR_ID_CAVIUM 0x177d
#define PCI_DEVICE_ID_THUNDER_L2C_TAD 0xa02e
#define L2C_TAD_BASE 0x87e050000000ULL
#define L2C_TAD_STRIDE 0x1000000ULL
#define L2C_TAD_MAP_SIZE 0x60000

#define L2C_TAD_PRF_SEL_E 0x10000
#define L2C_TAD_PFC(n) (0x10100 + (n) * sizeof(u64))
#define L2C_TAD_STAT 0x20008
#define L2C_TAD_TIMEOUT 0x50100

#define L2C_TAD_STAT_LFB_VALID_CNT GENMASK_ULL(13, 8)
#define L2C_TAD_STAT_VBF_INUSE_CNT GENMASK_ULL(4, 0)

#define L2C_TAD_EVENT_LFB_OCC 0x07
#define L2C_TAD_EVENT_WAIT_LFB 0x08
#define L2C_TAD_EVENT_WAIT_VAB 0x09
#define L2C_TAD_EVENT_OPEN_CCPI 0x0a

static bool l2c_tad_debug_tx;
module_param(l2c_tad_debug_tx, bool, 0644);
MODULE_PARM_DESC(l2c_tad_debug_tx,
		 "print L2C-TAD STAT and PRF counters on every bypass TX");

static uint l2c_tad_debug_tx_interval = 1;
module_param(l2c_tad_debug_tx_interval, uint, 0644);
MODULE_PARM_DESC(l2c_tad_debug_tx_interval,
		 "print one L2C-TAD debug sample every N bypass TX packets");

static void __iomem *l2c_tad_regs[L2C_TAD_UNITS];
static resource_size_t l2c_tad_phys[L2C_TAD_UNITS];
static atomic64_t l2c_tad_tx_seq = ATOMIC64_INIT(0);

static void l2c_tad_program_prf(void __iomem *regs)
{
	u64 prf_sel;
	int i;

	prf_sel = ((u64)L2C_TAD_EVENT_LFB_OCC << 0) |
		  ((u64)L2C_TAD_EVENT_WAIT_LFB << 8) |
		  ((u64)L2C_TAD_EVENT_WAIT_VAB << 16) |
		  ((u64)L2C_TAD_EVENT_OPEN_CCPI << 24);

	for (i = 0; i < 4; i++)
		writeq(0, regs + L2C_TAD_PFC(i));

	writeq(prf_sel, regs + L2C_TAD_PRF_SEL_E);
}

static int l2c_tad_index_from_bar(resource_size_t phys)
{
	resource_size_t off;

	if (phys < L2C_TAD_BASE)
		return -EINVAL;

	off = phys - L2C_TAD_BASE;
	if (off % L2C_TAD_STRIDE != 0)
		return -EINVAL;
	if (off / L2C_TAD_STRIDE >= L2C_TAD_UNITS)
		return -EINVAL;

	return off / L2C_TAD_STRIDE;
}

static int l2c_tad_map_from_pci(void)
{
	struct pci_dev *pdev = NULL;
	int found = 0;

	while ((pdev = pci_get_device(PCI_VENDOR_ID_CAVIUM,
				      PCI_DEVICE_ID_THUNDER_L2C_TAD, pdev))) {
		resource_size_t phys = pci_resource_start(pdev, 0);
		resource_size_t len = pci_resource_len(pdev, 0);
		int idx = l2c_tad_index_from_bar(phys);

		if (idx < 0) {
			pr_warn("ignoring L2C-TAD PCI device %s with unexpected BAR0 %pa len %#llx\n",
				pci_name(pdev), &phys, (u64)len);
			continue;
		}

		if (l2c_tad_regs[idx]) {
			pr_warn("ignoring duplicate L2C_TAD%d PCI device %s at BAR0 %pa\n",
				idx, pci_name(pdev), &phys);
			continue;
		}

		if (len <= L2C_TAD_TIMEOUT) {
			pr_warn("ignoring L2C_TAD%d PCI device %s with too-small BAR0 len %#llx\n",
				idx, pci_name(pdev), (u64)len);
			continue;
		}

		l2c_tad_regs[idx] = pci_iomap(pdev, 0, 0);
		if (!l2c_tad_regs[idx]) {
			pr_err("failed to iomap L2C_TAD%d PCI device %s BAR0 %pa\n",
			       idx, pci_name(pdev), &phys);
			continue;
		}

		l2c_tad_phys[idx] = phys;
		l2c_tad_program_prf(l2c_tad_regs[idx]);
		found++;
		pr_info("mapped L2C_TAD%d from PCI device %s BAR0 %pa; STAT=%pa TIMEOUT=%pa\n",
			idx, pci_name(pdev), &phys,
			&(resource_size_t){ phys + L2C_TAD_STAT },
			&(resource_size_t){ phys + L2C_TAD_TIMEOUT });
	}

	return found == L2C_TAD_UNITS ? 0 : -ENODEV;
}

static int l2c_tad_map_from_fixed_addresses(void)
{
	int i;

	for (i = 0; i < L2C_TAD_UNITS; i++) {
		phys_addr_t phys = L2C_TAD_BASE + i * L2C_TAD_STRIDE;

		l2c_tad_regs[i] = ioremap(phys, L2C_TAD_MAP_SIZE);
		if (!l2c_tad_regs[i]) {
			pr_err("failed to map L2C_TAD%d at %pa\n", i, &phys);
			return -ENOMEM;
		}

		l2c_tad_phys[i] = phys;
		l2c_tad_program_prf(l2c_tad_regs[i]);
		pr_info("mapped L2C_TAD%d from fixed BAR0 %pa; STAT=%pa TIMEOUT=%pa\n",
			i, &phys, &(phys_addr_t){ phys + L2C_TAD_STAT },
			&(phys_addr_t){ phys + L2C_TAD_TIMEOUT });
	}

	return 0;
}

int init_l2c_tad_debug(void)
{
	int err;

	err = l2c_tad_map_from_pci();
	if (err) {
		deinit_l2c_tad_debug();
		pr_warn("could not map all L2C-TAD BARs through PCI, falling back to fixed physical addresses\n");
		err = l2c_tad_map_from_fixed_addresses();
	}
	if (err)
		deinit_l2c_tad_debug();

	pr_info("mapped L2C_TAD0..7 debug registers; TX printing %s\n",
		l2c_tad_debug_tx ? "enabled" : "disabled");
	return err;
}

void deinit_l2c_tad_debug(void)
{
	int i;

	for (i = 0; i < L2C_TAD_UNITS; i++) {
		if (l2c_tad_regs[i]) {
			if (l2c_tad_phys[i])
				iounmap(l2c_tad_regs[i]);
			l2c_tad_regs[i] = NULL;
			l2c_tad_phys[i] = 0;
		}
	}
}

void l2c_tad_debug_print_tx(u32 tx_len)
{
	u64 seq;
	int i;

	if (!READ_ONCE(l2c_tad_debug_tx))
		return;

	seq = atomic64_inc_return(&l2c_tad_tx_seq);
	if (l2c_tad_debug_tx_interval > 1 &&
	    seq % l2c_tad_debug_tx_interval != 0)
		return;

	for (i = 0; i < L2C_TAD_UNITS; i++) {
		void __iomem *regs = l2c_tad_regs[i];
		u64 stat, timeout, lfb_occ, wait_lfb, wait_vab, open_ccpi;
		u64 lfb_valid_cnt, vbf_inuse_cnt;

		if (!regs)
			continue;

		stat = readq(regs + L2C_TAD_STAT);
		timeout = readq(regs + L2C_TAD_TIMEOUT);
		lfb_occ = readq(regs + L2C_TAD_PFC(0));
		wait_lfb = readq(regs + L2C_TAD_PFC(1));
		wait_vab = readq(regs + L2C_TAD_PFC(2));
		open_ccpi = readq(regs + L2C_TAD_PFC(3));
		lfb_valid_cnt = FIELD_GET(L2C_TAD_STAT_LFB_VALID_CNT, stat);
		vbf_inuse_cnt = FIELD_GET(L2C_TAD_STAT_VBF_INUSE_CNT, stat);

		pr_info("tx=%llu len=%u TAD%d BAR0=%pa STAT=%016llx TIMEOUT=%016llx LFB_VALID_CNT=%llu VBF_INUSE_CNT=%llu LFB_OCC=%llu WAIT_LFB=%llu WAIT_VAB=%llu OPEN_CCPI=%llu\n",
			seq, tx_len, i, &l2c_tad_phys[i], stat, timeout,
			lfb_valid_cnt, vbf_inuse_cnt, lfb_occ, wait_lfb,
			wait_vab, open_ccpi);
	}
}
