

// [lo, hi) bound of CPU cores used to handle RPC requests
static int worker_lo, worker_hi;
static DEFINE_PER_CPU_READ_MOSTLY(int, fpi_cpu_number);
static int irq_no;

static irqreturn_t worker_fpi_handler(int irq, void *data) {
    pr_info("%s.%d[%2d]: FPI %d\n", __func__, __LINE__, smp_processor_id(), irq);
    // wq_flag = 1;
    // smp_wmb();
    // wake_up_interruptible(&wq);
    // TODO: continue in the second half, to get a proper thread context
    return IRQ_HANDLED;
}

int init_workers() {
    int err;

    // Which cores are the worker cores?
    worker_hi = num_online_cpus();
    worker_lo = worker_hi - LAUBERHORN_NUM_WORKER_CORES;
    pr_info("Using %d cores %d-%d for RPC processing\n",
        LAUBERHORN_NUM_WORKER_CORES,
        worker_lo, worker_hi - 1);

    // enable interrupts for all worker cores
    err = init_worker_fpi();
    if (err != 0) return err;
}

void deinit_workers() {

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
static int init_worker_fpi() {
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
    pr_info("Allocated interrupt number = %d\n", fpi_irq_no);
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