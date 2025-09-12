// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

#ifndef LAUBERHORN_MACKEREL_H
#define LAUBERHORN_MACKEREL_H

// Implement register access in the kernel.  All addresses passed from Mackerel are in
// the device address space, i.e. they do not have the I/O space offset.
#ifndef __KERNEL__
#error Should only be used inside the kernel module...
#endif

#include <linux/types.h>
#include <asm/io.h>

// We will pass the init functions register block bases as defined in regblock_bases.h.
// These are uint64_t offsets.  We add the I/O base address before accessing.
typedef uint64_t mackerel_addr_t;
#define STATIC_SHELL_IO_BASE (0x900000000000UL)

/*
 * Reading from memory
 */
static inline uint8_t mackerel_read_addr_8( mackerel_addr_t base, int offset)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return ioread8(p);
}

static inline uint16_t mackerel_read_addr_16( mackerel_addr_t base, int offset)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return ioread16(p);
}
static inline uint32_t mackerel_read_addr_32( mackerel_addr_t base, int offset)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return ioread32(p);
}
static inline uint64_t mackerel_read_addr_64( mackerel_addr_t base, int offset)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return ioread64(p);
}

/*
 * Writing to memory
 */
static inline void mackerel_write_addr_8( mackerel_addr_t base,
                                             int offset, uint8_t v)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return iowrite8(v, p);
}

static inline void mackerel_write_addr_16( mackerel_addr_t base,
                                               int offset, uint16_t v)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return iowrite16(v, p);
}
static inline void mackerel_write_addr_32( mackerel_addr_t base,
                                               int offset, uint32_t v)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return iowrite32(v, p);
}
static inline void mackerel_write_addr_64( mackerel_addr_t base,
                                               int offset, uint64_t v)
{
    void *p = (void *)(STATIC_SHELL_IO_BASE + base + offset);
    return iowrite64(v, p);
}


#endif // LAUBERHORN_MACKEREL_H
