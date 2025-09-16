// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// Implement the bypass core functions in kernel.  Main functionalities:
// - fetch TX packets from network device and send through bypass core
// - hook into ARP stack:
//   - sync host ARP table entries to HW
//   - expire ARP table entries and delete them
// - run poll loop for bypass core to fetch (as part of NAPI)
//   - bypass packets: feed into a network device
//   - ARP resolve request from TX pipeline: send out ARP request
//
// The bypass core polling loop does not delay responses.  The FPI number
// 15 to core 0 will be used to notify the CPU that the bypass queue is
// now non-empty, and is used to drive NAPI scheduling.
//

#include "common.h"

#include <linux/etherdevice.h>
#include <linux/netdevice.h>

#include "cmac.h"
#include "eci/config.h"
#include "eci/core.h"
#include "eci/regblock_bases.h"
#include "lauberhorn_eci_preempt.h"

#define FPGA_MEM_BASE (0x10000000000UL)
#define CMAC_BASE 0x200000UL

static u64 irq_no;

struct netdev_priv {
	struct napi_struct napi;

	// Mackerel devices
	lauberhorn_eci_preempt_t reg_dev;
	cmac_t cmac_dev;

	// Datapath state
	lauberhorn_core_state_t ctx;
};

static struct net_device *netdev;
static DEFINE_PER_CPU_READ_MOSTLY(int, bypass_fpi_cookie);

static irqreturn_t bypass_fpi_handler(int irq, void *unused)
{
	struct netdev_priv *priv = netdev_priv(netdev);

	pr_info("%s.%d[%2d]: bypass IRQ (FPI %d)\n", __func__, __LINE__,
		smp_processor_id(), irq);

	// Mask interrupt and call napi_schedule
	lauberhorn_eci_preempt_irq_en_wr(&priv->reg_dev, 0);
	napi_schedule(&priv->napi);

	return IRQ_HANDLED;
}

static int init_bypass_fpi(void)
{
	int err;
	struct irq_data *gic_irq_data;
	struct irq_domain *gic_domain;
	struct fwnode_handle *fwnode;
	static struct irq_fwspec fwspec_fpi;

	// Get the fwnode for the GIC.  A hack here to find the fwnode through IRQ
	// 1, since we don't have a device tree node.  We assuming that fwnode is
	// the first element of structure gic_chip_data
	gic_irq_data = irq_get_irq_data(1U);
	gic_domain = gic_irq_data->domain;
	fwnode = *(struct fwnode_handle **)(gic_domain->host_data);

	// Allocate an IRQ number for SGI #15 for bypass core
	fwspec_fpi.fwnode = fwnode;
	fwspec_fpi.param_count = 1;
	fwspec_fpi.param[0] = 15;
	err = irq_create_fwspec_mapping(&fwspec_fpi);
	if (err < 0) {
		pr_warn("irq_create_fwspec_mapping returns %d\n", err);
	}
	irq_no = err;
	pr_info("Allocated interrupt number = %llu\n", irq_no);
	smp_wmb();

	// Register handler for bypass IRQ
	err = request_percpu_irq(irq_no, bypass_fpi_handler,
				 "Lauberhorn Bypass IRQ", &bypass_fpi_cookie);
	if (err < 0) {
		pr_warn("failed to allocate bypass IRQ: err %d\n", err);
		return err;
	}

	// Enable SGI #15 on core 0
	err = smp_call_on_cpu(0, do_fpi_irq_activate, (void *)irq_no, true);
	if (err < 0) {
		pr_warn("failed to invoke CPU 0 to activate bypass IRQ: err %d\n",
			err);
		return err;
	}

	return 0;
}

static void deinit_bypass_fpi(void)
{
	int err;

	err = smp_call_on_cpu(0, do_fpi_irq_deactivate, (void *)irq_no, true);
	WARN_ON(err < 0);
	free_percpu_irq(irq_no, &bypass_fpi_cookie);
	irq_dispose_mapping(irq_no);
}

static int netdev_open(struct net_device *dev)
{
	struct netdev_priv *priv = netdev_priv(dev);

	napi_enable(&priv->napi);
	netif_start_queue(dev);

	lauberhorn_eci_preempt_irq_en_wr(&priv->reg_dev, 1);

	start_cmac(&priv->cmac_dev, 0);

	return 0;
}

static int netdev_setaddr(struct net_device *dev, void *addr)
{
	// update MAC address for the interface

	return 0;
}

static void netdev_rx_mode(struct net_device *dev)
{
	struct netdev_priv *priv = netdev_priv(dev);

	if (dev->flags & IFF_PROMISC) {
		pr_info("enabling promisc mode\n");

	} else {
		pr_info("disabling promisc mode\n");
	}
}

// TODO: register handler for IP address update, program into device

static int netdev_stop(struct net_device *dev)
{
	struct netdev_priv *priv = netdev_priv(dev);

	stop_cmac(&priv->cmac_dev);

	lauberhorn_eci_preempt_irq_en_wr(&priv->reg_dev, 0);

	napi_disable(&priv->napi);
	netif_stop_queue(dev);

	return 0;
}

static netdev_tx_t netdev_xmit(struct sk_buff *skb, struct net_device *dev)
{
	struct netdev_priv *priv = netdev_priv(netdev);
	lauberhorn_pkt_desc_t desc;

	core_eci_tx_prepare_desc(&desc, &priv->ctx);

	// send the skb as a bypass Ethernet packet
	desc.type = TY_BYPASS;
	desc.bypass.header_type = HDR_ETHERNET;

	BUG_ON(skb->len < ETH_HLEN);
	memcpy(desc.bypass.header, eth_hdr(skb), ETH_HLEN);

	desc.payload_len = skb->len - ETH_HLEN;
	memcpy(desc.payload_buf, skb->data + ETH_HLEN, desc.payload_len);

	core_eci_tx(phys_to_virt(FPGA_MEM_BASE), &priv->ctx, &desc);

	// free skb and return
	dev_kfree_skb(skb);
	dev->stats.tx_packets++;
	dev->stats.tx_bytes += skb->len;
	return NETDEV_TX_OK;
}

static int napi_poll(struct napi_struct *n, int budget)
{
}

static const struct net_device_ops netdev_ops = {
	.ndo_open = netdev_open,
	.ndo_stop = netdev_stop,
	.ndo_start_xmit = netdev_xmit,
	.ndo_set_mac_address = netdev_setaddr,
	.ndo_set_rx_mode = netdev_rx_mode,
};

static void init_netdev(struct net_device *dev)
{
	ether_setup(dev);
	dev->netdev_ops = &netdev_ops;
	dev->mtu = LAUBERHORN_MTU;
}

static inline void cl_hit_inv(u64 phys_addr)
{
	u64 virt = (u64)phys_to_virt(phys_addr);
	asm volatile("sys #0,c11,c1,#1,%0 \n" ::"r"(virt));
}

int init_bypass(void)
{
	int err, cl_id;
	struct netdev_priv *priv;
	cmac_core_version_t ver;
	u8 ver_maj, ver_min;

	u64 rx_base = FPGA_MEM_BASE + LAUBERHORN_ECI_RX_BASE;
	u64 tx_base = FPGA_MEM_BASE + LAUBERHORN_ECI_TX_BASE;

	// Create netdev
	netdev = alloc_netdev(sizeof(struct netdev_priv), "lauberhorn%d",
			      NET_NAME_UNKNOWN, init_netdev);
	if (!netdev) {
		pr_err("failed to allocate netdev\n");
		return -ENOMEM;
	}

	// TODO: Read out default MAC address from HW
	// dev->dev_addr = ;

	priv = netdev_priv(netdev);

	// Create Mackerel devices
	lauberhorn_eci_preempt_initialize(&priv->reg_dev,
					  LAUBERHORN_ECI_PREEMPT_BASE(0));
	cmac_initialize(&priv->cmac_dev, CMAC_BASE);

	// Verify CMAC version
	ver = cmac_core_version_rd(&priv->cmac_dev);
	ver_maj = cmac_core_version_major_extract(ver);
	ver_min = cmac_core_version_minor_extract(ver);
	if (ver == 0) {
		pr_err("CMAC version register all zero!\n");
		free_netdev(netdev);
		return -1;
	}
	pr_info("CMAC version: %d.%d (raw %#x)\n", ver_maj, ver_min, ver);

	// Initialize datapath core state
	priv->ctx.rx_next_cl = priv->ctx.tx_next_cl = 0;
	priv->ctx.rx_overflow_buf_size = priv->ctx.tx_overflow_buf_size =
		LAUBERHORN_MTU;
	priv->ctx.rx_overflow_buf =
		kmalloc(priv->ctx.rx_overflow_buf_size, GFP_KERNEL);
	priv->ctx.tx_overflow_buf =
		kmalloc(priv->ctx.tx_overflow_buf_size, GFP_KERNEL);

	// Invalidate control and bypass CLs
	for (cl_id = 0; cl_id < 2; ++cl_id) {
		cl_hit_inv(rx_base + 0x80 * cl_id);
		cl_hit_inv(tx_base + 0x80 * cl_id);
	}

	for (cl_id = 0; cl_id < LAUBERHORN_ECI_NUM_OVERFLOW_CL; ++cl_id) {
		cl_hit_inv(rx_base + LAUBERHORN_ECI_OVERFLOW_OFFSET +
			   0x80 * cl_id);
		cl_hit_inv(tx_base + LAUBERHORN_ECI_OVERFLOW_OFFSET +
			   0x80 * cl_id);
	}

	// Register netdev
	netif_napi_add(netdev, &priv->napi, napi_poll);

	err = register_netdev(netdev);
	if (err < 0) {
		pr_err("failed to register netdev: err %d\n", err);
		netif_napi_del(&priv->napi);
		free_netdev(netdev);
		return err;
	}

	// Enable FIFO non-empty interrupt
	err = init_bypass_fpi();
	if (err < 0)
		return err;

	return 0;
}

void deinit_bypass(void)
{
	struct netdev_priv *priv = netdev_priv(netdev);

	// Disable interrupts
	deinit_bypass_fpi();

	// Free overflow buffers
	kfree(priv->ctx.rx_overflow_buf);
	kfree(priv->ctx.tx_overflow_buf);

	// Destroy netdev
	unregister_netdev(netdev);
	netif_napi_del(&priv->napi);
	free_netdev(netdev);
}