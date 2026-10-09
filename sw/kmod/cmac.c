// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// CMAC driver

#include "common.h"
#include "cmac_dev.h"

// Total wait time 10 seconds
#define LINE_UP_MAX_ATTEMPTS 100
#define LINE_UP_WAIT_MS 100

static void reset_cmac(cmac_t *cmac)
{
	// reset GT and the MAC
	cmac_gt_reset_all_wrf(cmac, 1); // clear on write
	cmac_reset_usr_rx_serdes_wrf(cmac, 0b1111111111);
	cmac_reset_usr_rx_wrf(cmac, 1);
	cmac_reset_usr_tx_wrf(cmac, 1);
	msleep(LINE_UP_WAIT_MS);
	cmac_reset_usr_rx_serdes_wrf(cmac, 0);
	cmac_reset_usr_rx_wrf(cmac, 0);
	cmac_reset_usr_tx_wrf(cmac, 0);
	msleep(LINE_UP_WAIT_MS);

	// force RX resync
	cmac_conf_rx_1_force_resync_wrf(cmac, 1);
	udelay(10);
	cmac_conf_rx_1_force_resync_wrf(cmac, 0);
	msleep(LINE_UP_WAIT_MS);
}

int start_cmac(cmac_t *cmac, bool loopback)
{
	// CMAC lane alignment handshake:
	// - enable RX and send RFI
	// - wait RX_aligned
	// - stop sending RFI
	// - wait RX_status

	int attempts = 0;
	cmac_core_version_t version = cmac_core_version_rd(cmac);
	cmac_conf_rsfec_enable_t fec;
	cmac_stat_rx_status_t status;

	if (!version || version == ~0U) {
		pr_err("CMAC: invalid version %#x\n", version);
		return -ENODEV;
	}
	pr_info("CMAC version %u.%u\n",
		cmac_core_version_major_extract(version),
		cmac_core_version_minor_extract(version));

	// Pull resets so we start clean
	reset_cmac(cmac);

	// enable RS-FEC
	cmac_conf_rsfec_enable_rx_wrf(cmac, 1);
	cmac_conf_rsfec_enable_tx_wrf(cmac, 1);
	cmac_conf_rsfec_ind_corr_rx_ieee_error_indication_mode_wrf(cmac, 1);
	cmac_conf_rsfec_ind_corr_rx_enable_correction_wrf(cmac, 1);
	cmac_conf_rsfec_ind_corr_rx_enable_indication_wrf(cmac, 1);

	/* Fail here rather than misdiagnosing an unmapped FEC bank as a bad link. */
	fec = cmac_conf_rsfec_enable_rd(cmac);
	if (!cmac_conf_rsfec_enable_rx_extract(fec) ||
	    !cmac_conf_rsfec_enable_tx_extract(fec)) {
		pr_err("CMAC: RS-FEC enable readback failed: %#x\n", fec);
		stop_cmac(cmac);
		return -EIO;
	}

	cmac_gt_loopback_enable_wrf(cmac, loopback);
	pr_info("Loopback enabled: %s\n", loopback ? "true" : "false");

	// ctl_rx_enable
	cmac_conf_rx_1_enable_wrf(cmac, 1);

	// !ctl_tx_enable, ctl_tx_send_rfi
	cmac_conf_tx_1_t tx_1_val = 0;
	tx_1_val = cmac_conf_tx_1_enable_insert(0, 0);
	tx_1_val = cmac_conf_tx_1_send_rfi_insert(tx_1_val, 1);
	cmac_conf_tx_1_rawwr(cmac, tx_1_val);

	while (++attempts < LINE_UP_MAX_ATTEMPTS) {
		if (cmac_stat_rx_status_aligned_rdf(cmac))
			break; // RX_aligned
		msleep(LINE_UP_WAIT_MS);
	}

	if (attempts == LINE_UP_MAX_ATTEMPTS) {
		pr_err("Wait for RX_aligned timed out\n");
		pr_err("CMAC diagnostics: RX=%#x FEC=%#x block-lock=%#x\n",
		       cmac_stat_rx_status_rd(cmac),
		       cmac_stat_rsfec_status_rd(cmac),
		       cmac_stat_rx_block_lock_rd(cmac));
		stop_cmac(cmac);
		return -ETIMEDOUT;
	}

	// ctl_tx_enable, !ctl_tx_send_rfi
	tx_1_val = cmac_conf_tx_1_enable_insert(0, 1);
	tx_1_val = cmac_conf_tx_1_send_rfi_insert(tx_1_val, 0);
	cmac_conf_tx_1_rawwr(cmac, tx_1_val);

	attempts = 0;
	while (++attempts < LINE_UP_MAX_ATTEMPTS) {
		status = cmac_stat_rx_status_rd(cmac);
		if (cmac_stat_rx_status_status_extract(status) &&
		    cmac_stat_rx_status_aligned_extract(status))
			break; // RX_status
		msleep(LINE_UP_WAIT_MS);
	}

	if (attempts == LINE_UP_MAX_ATTEMPTS) {
		pr_err("Wait for RX_status timed out\n");
		pr_err("CMAC diagnostics: RX=%#x FEC=%#x block-lock=%#x\n",
		       cmac_stat_rx_status_rd(cmac),
		       cmac_stat_rsfec_status_rd(cmac),
		       cmac_stat_rx_block_lock_rd(cmac));
		stop_cmac(cmac);
		return -ETIMEDOUT;
	}

	pr_info("CMAC RX aligned and ready\n");
	// flow control disabled - skipping regs
	return 0;
}

void stop_cmac(cmac_t *cmac)
{
	cmac_conf_rx_1_enable_wrf(cmac, 0); // !ctl_rx_enable
	cmac_conf_tx_1_enable_wrf(cmac, 0); // !ctl_tx_enable
}
