#include "common.h"

#include "eci/config.h"

// [lo, hi) bound of CPU cores used to handle RPC requests
static int worker_lo, worker_hi;
static DEFINE_PER_CPU_READ_MOSTLY(int, fpi_cpu_number);
static u64 irq_no;

static irqreturn_t worker_fpi_handler(int irq, void *data) {
    pr_info("%s.%d[%2d]: FPI %d\n", __func__, __LINE__, smp_processor_id(), irq);

    // Read out IRQ ACK register and decode next task
    
    // Mark current task as uninterruptible
    // Or, if task needs to be killed per IRQ ACK, kill the task

    // Mark new task as runnable *only on this core*

    // Manipulate page tables to unmap old core CLs and map new one
    // XXX: alternatively, implement an IOMMU and change mapping here

    return IRQ_HANDLED;
}

/**
 * Install handlers for the software-generated interrupts (SGI) that comes from
 * the FPGA, for the worker cores.  Adam's Linux Memory Driver calls these FPIs, 
 * probably FPGA peripheral interrupts.
 * 
 * The Lauberhorn NIC sends two SGI interrupts:
 * - #15  only to core 0: bypass core descriptor FIFO non-empty
 * - #8   to all worker cores: preemption interrupt for switching tasks
 * 
 * This function only handles the interrupt for the worker cores; the bypass core
 * interrupt is handled inside `init_bypass`.
 */
static int init_worker_fpi(void) {
    int err, cid;
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

    // Allocate an IRQ number for SGI #8 for all worker cores
    fwspec_fpi.fwnode = fwnode;
    fwspec_fpi.param_count = 1;
    fwspec_fpi.param[0] = 8;
    err = irq_create_fwspec_mapping(&fwspec_fpi);
    if (err < 0) {
        pr_warn("irq_create_fwspec_mapping returns %d\n", err);
        return err;
    }
    irq_no = err;
    pr_info("Allocated interrupt number = %llu\n", irq_no);
    smp_wmb();
    
    err = request_percpu_irq(irq_no, worker_fpi_handler, "Lauberhorn RPC Worker Preemption IRQ",
        &fpi_cpu_number);
    if (err < 0) {
        pr_warn("request_percpu_irq returns %d\n", err);
        return err;
    }

    for (cid = worker_lo; cid < worker_hi; ++cid) {
        err = smp_call_on_cpu(cid, do_fpi_irq_activate, (void *)irq_no, true);
        WARN_ON(err < 0);
    }

    return 0;
}

static void deinit_worker_fpi(void) {
    int err, cid;

    for (cid = worker_lo; cid < worker_hi; ++cid) {
        err = smp_call_on_cpu(cid, do_fpi_irq_deactivate, (void *)irq_no, true);
        WARN_ON(err < 0);
    }
    free_percpu_irq(irq_no, &fpi_cpu_number);
    irq_dispose_mapping(irq_no);
}

int init_workers() {
    int err;

    // Which cores are the worker cores?
    worker_hi = num_online_cpus();
    worker_lo = worker_hi - LAUBERHORN_NUM_WORKER_CORES;
    pr_info("Using %d cores %d-%d for RPC processing\n",
        LAUBERHORN_NUM_WORKER_CORES,
        worker_lo, worker_hi - 1);

    // Enable interrupts for all worker cores
    err = init_worker_fpi();
    if (err != 0) return err;

    // Promote ksoftirqd on this core to SCHED_FIFO with priority 80.
    // The RPC tasks will run with a priority of 70

    // We don't have any RPC handlers on these worker cores yet, so nothing
    // to do here yet.  Once a user-level application thread starts, it will
    // register itself with an ioctl to /dev/lauberhorn -- we then set their
    // affinity, scheduling policy and priority.

    return 0;
}

void deinit_workers() {
    // Check if we still have applications running
    // Refcount the module properly on application exit, this should not happen

    // Disable FPI interrupt for the core
    deinit_worker_fpi();

    // Restore ksoftirqd to SCHED_OTHER

}