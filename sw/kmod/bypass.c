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

#include <linux/netdevice.h>
#include <linux/etherdevice.h>

#include "eci/config.h"
#include "eci/regblock_bases.h"
#include "eci/core.h"
#include "lauberhorn_eci_preempt.h"

static u64 irq_no;

struct netdev_priv {
    struct napi_struct napi;
       
    // Mackerel devices
    lauberhorn_eci_preempt_t reg_dev;
};

static struct net_device *netdev;
static DEFINE_PER_CPU_READ_MOSTLY(int, bypass_fpi_cookie);

static irqreturn_t bypass_fpi_handler(int irq, void *unused) {
    struct netdev_priv *priv = netdev_priv(netdev);

    pr_info("%s.%d[%2d]: bypass IRQ (FPI %d)\n", __func__, __LINE__, smp_processor_id(), irq);
    
    // Mask interrupt and call napi_schedule
    lauberhorn_eci_preempt_irq_en_wr(&priv->reg_dev, 0);
    napi_schedule(&priv->napi);

    return IRQ_HANDLED;
}

static int init_bypass_fpi(void) {
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
    err = request_percpu_irq(irq_no, bypass_fpi_handler, "Lauberhorn Bypass IRQ", &bypass_fpi_cookie);
    if (err < 0) {
        pr_warn("failed to allocate bypass IRQ: err %d\n", err);
        return err;
    }
    
    // Enable SGI #15 on core 0
    err = smp_call_on_cpu(0, do_fpi_irq_activate, (void *)irq_no, true);
    if (err < 0) {
        pr_warn("failed to invoke CPU 0 to activate bypass IRQ: err %d\n", err);
        return err;
    }

    return 0;
}

static void deinit_bypass_fpi(void) {
    int err;

    err = smp_call_on_cpu(0, do_fpi_irq_deactivate, (void *)irq_no, true);
    WARN_ON(err < 0);
    free_percpu_irq(irq_no, &bypass_fpi_cookie);
    irq_dispose_mapping(irq_no);
}

static int netdev_open(struct net_device *dev) {
    struct netdev_priv *priv = netdev_priv(dev);
    
    napi_enable(&priv->napi);
    netif_start_queue(dev);
    
    lauberhorn_eci_preempt_irq_en_wr(&priv->reg_dev, 1);
    
    return 0;
}

static int netdev_stop(struct net_device *dev) {
    struct netdev_priv *priv = netdev_priv(dev);
    
    lauberhorn_eci_preempt_irq_en_wr(&priv->reg_dev, 0);
    
    napi_disable(&priv->napi);
    netif_stop_queue(dev);
    
    return 0;
}

static netdev_tx_t netdev_xmit(struct sk_buff *skb, struct net_device *dev) {

}

static int napi_poll(struct napi_struct *n, int budget) {

}

static const struct net_device_ops netdev_ops = {
    .ndo_open = netdev_open,
    .ndo_stop = netdev_stop,
    .ndo_start_xmit = netdev_xmit,
};

static void init_netdev(struct net_device *dev) {
    ether_setup(dev);
    dev->netdev_ops = &netdev_ops;
    dev->mtu = LAUBERHORN_MTU;
}

int init_bypass(void) {
    int err;
    struct netdev_priv *priv;

    // Create netdev
    netdev = alloc_netdev(sizeof(struct netdev_priv), "lauberhorn%d",
        NET_NAME_UNKNOWN, init_netdev);
    if (!netdev) {
        pr_err("failed to allocate netdev\n");
        return -ENOMEM;
    }
    
    priv = netdev_priv(netdev);
    
    // Create Mackerel devices
    lauberhorn_eci_preempt_initialize(&priv->reg_dev, LAUBERHORN_ECI_PREEMPT_BASE(0));
    
    netif_napi_add(netdev, &priv->napi, napi_poll);
    
    // Register netdev
    err = register_netdev(netdev);
    if (err < 0) {
        pr_err("failed to register netdev: err %d\n", err);
        netif_napi_del(&priv->napi);
        free_netdev(netdev);
        return err;
    }
    
    // Enable FIFO non-empty interrupt
    err = init_bypass_fpi();
    if (err < 0) return err;

    return 0;
}

void deinit_bypass(void) {
    struct netdev_priv *priv = netdev_priv(netdev);

    // Disable interrupts
    deinit_bypass_fpi();

    // Destroy netdev
    unregister_netdev(netdev);
    netif_napi_del(&priv->napi);
    free_netdev(netdev);
}