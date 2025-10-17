#ifndef LAUBERHORN_VIRT_NODE1_H
#define LAUBERHORN_VIRT_NODE1_H

#define STATIC_SHELL_IO_BASE (0x900000000000UL)
#define STATIC_SHELL_IO_SIZE \
	(1024 * PAGE_SIZE) // CMAC mapped at 0x200000 (512 pages)

#define FPGA_MEM_BASE (0x10000000000UL)
#define FPGA_MEM_SIZE (0x10000000000UL) // 1 TiB

// Get kernel virtual address for physical addrs in node 1
extern void __iomem *io_base_node1;
extern void *mem_base_node1;
static inline void *io_node1_off_to_virt(off_t x)
{
	BUG_ON(x >= STATIC_SHELL_IO_SIZE);
	return (void __iomem *)((u64)io_base_node1 + x);
}
static inline phys_addr_t mem_node1_off_to_phys(off_t x)
{
	BUG_ON(x >= FPGA_MEM_SIZE);
	return (phys_addr_t)(FPGA_MEM_BASE + x);
}
static inline void *mem_node1_off_to_virt(off_t x)
{
	BUG_ON(x >= FPGA_MEM_SIZE);
	return (void *)((u64)mem_base_node1 + x);
}

#endif // LAUBERHORN_VIRT_NODE1_H