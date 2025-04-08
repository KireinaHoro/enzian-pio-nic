cmake_minimum_required(VERSION 3.5)
project(enzian-pio-nic-sw C)

# Mackerel
set(MACKEREL_ROOT ${CMAKE_SOURCE_DIR}/../deps/mackerel2)
set(MACKEREL ${MACKEREL_ROOT}/target/release/mackerel2)
add_custom_command(OUTPUT ${MACKEREL}
        COMMAND cargo build --release --manifest-path=${MACKEREL_ROOT}/Cargo.toml)
add_custom_target(mackerel2 DEPENDS ${MACKEREL})

# Mackerel headers - let's specify each file we expect manually - there are not that many
set(DEV_BASENAMES
        cmac
        pionic_eci
        pionic_eci_core
        pionic_eci_global
        pionic_pcie
        pionic_pcie_core
        pionic_pcie_global
)
SET(DEV_HEADERS)
foreach (basename ${DEV_BASENAMES})
    set(dev_file ${CMAKE_SOURCE_DIR}/devices/${basename}.dev)
    set(output_file ${CMAKE_SOURCE_DIR}/rt-include/gen/${basename}.h)
    add_custom_command(OUTPUT ${output_file}
            COMMAND ${MACKEREL} -c ${dev_file} -I${CMAKE_SOURCE_DIR}/devices/ -o ${output_file}
            MAIN_DEPENDENCY ${dev_file}
            DEPENDS ${MACKEREL})
    list(APPEND DEV_HEADERS ${output_file})
endforeach ()
add_custom_target(gen_headers DEPENDS ${DEV_HEADERS})

# rt
set(RT_COMMON_SRC
        rt/common/cmac.c
        rt/common/config.c
        rt/common/diag.c
        rt/common/profile.c)

add_library(rt-eci
        ${RT_COMMON_SRC}
        rt/eci.c
        ${DEV_HEADERS})
target_include_directories(rt-eci PRIVATE
        rt-include
        core
        usr-include  # rt implements usr as a part of it
        ${CMAKE_SOURCE_DIR}/../hw/gen/eci
        ${MACKEREL_ROOT})
target_compile_definitions(rt-eci PUBLIC
        NIC_IMPL=eci)


