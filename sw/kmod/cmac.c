// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// CMAC driver

#include "common.h"
#include "cmac_dev.h"

// Total wait time 10 seconds
#define LINE_UP_MAX_ATTEMPTS 1000
#define LINE_UP_WAIT_MS 10

static void reset_cmac(cmac_t *cmac)
{
	cmac_gt_reset_gt_reset_all_wrf(cmac, 1); // clear on write
	cmac_reset_usr_rx_serdes_reset_wrf(cmac, 0b1111111111);
	cmac_reset_usr_rx_reset_wrf(cmac, 1);
	cmac_reset_usr_tx_reset_wrf(cmac, 1);
	mdelay(1);
	cmac_reset_usr_rx_serdes_reset_wrf(cmac, 0);
	cmac_reset_usr_rx_reset_wrf(cmac, 0);
	cmac_reset_usr_tx_reset_wrf(cmac, 0);
	mdelay(1);
}

int start_cmac(cmac_t *cmac, bool loopback)
{
	// CMAC lane alignment handshake:
	// - enable RX and send RFI
	// - wait RX_aligned
	// - stop sending RFI
	// - wait RX_status

	cmac_stat_rx_status_t status;
	int attempts = 0;

	// Pull resets so we start clean
	reset_cmac(cmac);

	// enable RS-FEC
	cmac_conf_rsfec_enable_ctl_rx_rsfec_enable_wrf(cmac, 1);
	cmac_conf_rsfec_enable_ctl_tx_rsfec_enable_wrf(cmac, 1);
	cmac_conf_rsfec_ind_corr_ctl_rx_rsfec_ieee_error_indication_mode_wrf(
		cmac, 1);
	cmac_conf_rsfec_ind_corr_ctl_rx_rsfec_enable_correction_wrf(cmac, 1);
	cmac_conf_rsfec_ind_corr_ctl_rx_rsfec_enable_indication_wrf(cmac, 1);

	cmac_gt_loopback_ctl_gt_loopback_wrf(cmac, loopback);
	pr_info("Loopback enabled: %s\n", loopback ? "true" : "false");

	// ctl_rx_enable
	cmac_conf_rx_1_ctl_rx_enable_wrf(cmac, 1);
	// !ctl_tx_enable, ctl_tx_send_rfi
	cmac_conf_tx_1_ctl_tx_send_rfi_wrf(cmac, 1);

	while (++attempts < LINE_UP_MAX_ATTEMPTS) {
		status = cmac_stat_rx_status_rawrd(cmac);
		if (cmac_stat_rx_status_stat_rx_aligned_extract(status))
			break; // RX_aligned
		mdelay(LINE_UP_WAIT_MS);
	}

	if (attempts == LINE_UP_MAX_ATTEMPTS) {
		pr_err("Wait for RX_aligned timed out\n");
		return -EBUSY;
	}

	// ctl_tx_enable, !ctl_tx_send_rfi
	cmac_conf_tx_1_rawwr(cmac, cmac_conf_tx_1_ctl_tx_enable_insert(0, 1));

	attempts = 0;
	while (++attempts < LINE_UP_MAX_ATTEMPTS) {
		status = cmac_stat_rx_status_rawrd(cmac);
		if (cmac_stat_rx_status_stat_rx_aligned_extract(status) &&
		    cmac_stat_rx_status_stat_rx_status_extract(status))
			break; // RX_aligned && RX_status
		mdelay(LINE_UP_WAIT_MS);
	}

	if (attempts == LINE_UP_MAX_ATTEMPTS) {
		pr_err("Wait for RX_aligned && RX_status timed out\n");
		return -EBUSY;
	}

	// flow control disabled - skipping regs
	return 0;
}

void stop_cmac(cmac_t *cmac)
{
	cmac_conf_rx_1_ctl_rx_enable_wrf(cmac, 0); // !ctl_rx_enable
	cmac_conf_tx_1_ctl_tx_enable_wrf(cmac, 0); // !ctl_tx_enable
}
