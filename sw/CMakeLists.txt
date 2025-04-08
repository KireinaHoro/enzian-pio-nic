cmake_minimum_required(VERSION 3.5)

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(TOOLCHAIN_PREFIX aarch64-linux-gnu-)
set(CMAKE_C_COMPILER   ${TOOLCHAIN_PREFIX}gcc    )
set(CMAKE_ASM_COMPILER ${TOOLCHAIN_PREFIX}gcc    )
set(CMAKE_CXX_COMPILER ${TOOLCHAIN_PREFIX}g++    )

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

# pionic-rt
set(RT_COMMON_SRC
        rt/common/cmac.c
        rt/common/config.c
        rt/common/diag.c
        rt/common/profile.c)

add_library(pionic-rt-eci
        ${RT_COMMON_SRC}
        rt/eci.c
        ${DEV_HEADERS})
target_include_directories(pionic-rt-eci PRIVATE
        rt-include
        core
        usr-include  # rt implements usr as a part of it
        ${CMAKE_SOURCE_DIR}/../hw/gen/eci
        ${MACKEREL_ROOT})
target_compile_definitions(pionic-rt-eci PRIVATE
        NIC_IMPL=eci)

# TODO: pionic-rt-pcie

# apps

#add_executable(pionic-rt-eci-test
#        apps/rt-eci-test/rt-eci-test.c)
#target_include_directories(pionic-rt-eci-test PRIVATE
#        rt-include
#        usr-include
#        ${CMAKE_SOURCE_DIR}/../hw/gen/eci)
#target_link_libraries(pionic-rt-eci-test
#        pionic-rt-eci)
#target_compile_definitions(pionic-rt-eci-test PRIVATE
#        NIC_IMPL=eci)
