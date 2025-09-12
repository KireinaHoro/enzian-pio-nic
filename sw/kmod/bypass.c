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

#include <linux/smp.h>
#include <linux/irq.h>
#include <linux/interrupt.h>
#include <linux/irqreturn.h>
#include <linux/irqdomain.h>

#include <asm/arch_gicv3.h>

static DEFINE_PER_CPU_READ_MOSTLY(int, bypass_fpi_cookie);
static int irq_no;

static irqreturn_t bypass_fpi_handler(int irq, void *data) {
    pr_info("%s.%d[%2d]: bypass IRQ (FPI %d)\n", __func__, __LINE__, smp_processor_id(), irq);

    // TODO: check if bypass FIFO has data, call NAPI schedule
    // 
    return IRQ_HANDLED;
}

int init_bypass(void) {
    int err, irq_no;
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
    pr_info("Allocated interrupt number = %d\n", irq_no);
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
}

static int deinit_bypass_fpi(void) {
    int err;

    err = smp_call_on_cpu(0, do_fpi_irq_deactivate, (void *)irq_no, true);
    WARN_ON(err < 0);
    free_percpu_irq(irq_no, &bypass_fpi_cookie);
    irq_dispose_mapping(irq_no);
}