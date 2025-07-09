#ifndef __PIONIC_HAL_H__
#define __PIONIC_HAL_H__

// Mackerel
#ifndef NIC_IMPL

#error "NIC_IMPL must be defined as eci or pcie"

#elif NIC_IMPL == eci

struct pionic_eci_global_t;
typedef struct pionic_eci_global_t pionic_global_t;
#define pionic_global(f) pionic_eci_global_ ## f

struct pionic_eci_core_t;
typedef struct pionic_eci_core_t pionic_core_t;
#define pionic_core(f) pionic_eci_core_ ## f

#elif NIC_IMPL == pcie

struct pionic_pcie_global_t;
typedef struct pionic_pcie_global_t pionic_global_t;
#define pionic_global(f) pionic_pcie_global_ ## f

struct pionic_pcie_core_t;
typedef struct pionic_pcie_core_t pionic_core_t;
#define pionic_core(f) pionic_pcie_core_ ## f

#endif

#endif // __PIONIC_HAL_H__
