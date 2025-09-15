// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// CMAC driver

#include "common.h"
#include "cmac.h"

#define LINE_UP_MAX_ATTEMPTS 100

int start_cmac(cmac_t *cmac, bool loopback)
{
	cmac_stat_rx_status_t status;

	cmac_conf_rx_1_rawwr(cmac, cmac_conf_rx_1_ctl_rx_enable_insert(
					   0, 1)); // ctl_rx_enable
	cmac_conf_tx_1_rawwr(cmac, cmac_conf_tx_1_ctl_tx_send_rfi_insert(
					   0, 1)); // ctl_tx_send_rfi

	int attempts = 0;

	while (++attempts < LINE_UP_MAX_ATTEMPTS) {
		cmac_tick_rawwr(cmac, cmac_tick_tick_reg_insert(0, 1));
		status = cmac_stat_rx_status_rd(cmac);
		if (cmac_stat_rx_status_stat_rx_aligned_extract(status))
			break; // RX_aligned
		usleep(1000);
	}

	if (attempts == LINE_UP_MAX_ATTEMPTS) {
		pr_err("Wait for RX_aligned timed out\n");
		return -1;
	}
	attempts = 0;

	cmac_conf_tx_1_rawwr(cmac,
			     cmac_conf_tx_1_ctl_tx_enable_insert(
				     0, 1)); // ctl_tx_enable, !ctl_tx_send_rfi

	// FIXME: why do we need this?
	while (++attempts < LINE_UP_MAX_ATTEMPTS) {
		cmac_tick_rawwr(cmac, cmac_tick_tick_reg_insert(0, 1));
		status = cmac_stat_rx_status_rd(cmac);
		if (cmac_stat_rx_status_stat_rx_aligned_extract(status) &&
		    cmac_stat_rx_status_stat_rx_status_extract(status))
			break; // RX_aligned && RX_status
		usleep(1000);
	}

	if (attempts == LINE_UP_MAX_ATTEMPTS) {
		pr_err("Wait for RX_aligned && RX_status timed out\n");
		return -1;
	}

	cmac_gt_loopback_rawwr(
		cmac, cmac_gt_loopback_ctl_gt_loopback_insert(0, loopback));

	pr_info("Loopback enabled: %s\n", loopback ? "true" : "false");

	// flow control disabled - skipping regs
	return 0;
}

void stop_cmac(cmac_t *cmac)
{
	cmac_conf_rx_fc_ctl_1_rawwr(cmac, 0); // !ctl_rx_enable
	cmac_conf_tx_1_rawwr(cmac, 0); // !ctl_tx_enable
}
