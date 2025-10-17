// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// CMAC driver

#include "common.h"
#include "cmac_dev.h"

// Total wait time 1000 ms
#define LINE_UP_MAX_ATTEMPTS 1000
#define LINE_UP_WAIT_MS 1

int start_cmac(cmac_t *cmac, bool loopback)
{
	// CMAC lane alignment handshake:
	// - enable RX and send RFI
	// - wait RX_aligned
	// - stop sending RFI
	// - wait RX_status

	cmac_stat_rx_status_t status;
	int attempts = 0;

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

	cmac_gt_loopback_ctl_gt_loopback_wrf(cmac, loopback);
	pr_info("Loopback enabled: %s\n", loopback ? "true" : "false");

	// flow control disabled - skipping regs
	return 0;
}

void stop_cmac(cmac_t *cmac)
{
	cmac_conf_rx_1_ctl_rx_enable_wrf(cmac, 0); // !ctl_rx_enable
	cmac_conf_tx_1_ctl_tx_enable_wrf(cmac, 0); // !ctl_tx_enable
}
