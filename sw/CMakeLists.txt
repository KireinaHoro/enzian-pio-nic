cmake_minimum_required(VERSION 3.10)

project(lauberhorn-sw C)

# Mackerel
set(MACKEREL_ROOT ${CMAKE_SOURCE_DIR}/../deps/mackerel2)
set(MACKEREL ${MACKEREL_ROOT}/target/release/mackerel2)
add_custom_command(OUTPUT ${MACKEREL}
        COMMAND echo cargo build --release --manifest-path=${MACKEREL_ROOT}/Cargo.toml
        COMMAND cargo build --release --manifest-path=${MACKEREL_ROOT}/Cargo.toml)
add_custom_target(mackerel2 DEPENDS ${MACKEREL})

# Generated headers will be put into this directory
set(GENERATED_INCLUDE_DIR ${CMAKE_BINARY_DIR}/gen)
file(MAKE_DIRECTORY ${GENERATED_INCLUDE_DIR})

include_directories(${GENERATED_INCLUDE_DIR})

# Mackerel headers - let's specify each file we expect manually - there are not that many
set(DEV_BASENAMES
        cmac
        lauberhorn_eci
        lauberhorn_eci_core
        lauberhorn_eci_global
        #lauberhorn_pcie
        #lauberhorn_pcie_core
        #lauberhorn_pcie_global
)
SET(DEV_HEADERS)
foreach (basename ${DEV_BASENAMES})
    set(dev_file ${CMAKE_SOURCE_DIR}/devices/${basename}.dev)
    set(output_file ${GENERATED_INCLUDE_DIR}/${basename}.h)
    add_custom_command(OUTPUT ${output_file}
            COMMAND ${MACKEREL} -c ${dev_file} -I${CMAKE_SOURCE_DIR}/devices/ -o ${output_file}
            MAIN_DEPENDENCY ${dev_file}
            DEPENDS ${MACKEREL})
    list(APPEND DEV_HEADERS ${output_file})
endforeach ()
add_custom_target(gen_headers DEPENDS ${DEV_HEADERS})

# lauberhorn-rt
set(RT_COMMON_SRC
        rt/common/cmac.c
        rt/common/config.c
        rt/common/diag.c
        rt/common/profile.c)

add_library(lauberhorn-rt-eci
        ${RT_COMMON_SRC}
        rt/eci.c
        ${DEV_HEADERS})
target_include_directories(lauberhorn-rt-eci PRIVATE
        rt-include
        core
        usr-include  # rt implements usr as a part of it
        ${CMAKE_SOURCE_DIR}/../hw/gen/eci
        ${MACKEREL_ROOT})
target_compile_definitions(lauberhorn-rt-eci PRIVATE
        NIC_IMPL=eci)

# lauberhorn-user
set(USR_COMMON_SRC)

add_library(lauberhorn-usr-eci
        ${USR_COMMON_SRC}
		usr/usr.c
        ${DEV_HEADERS})
target_include_directories(lauberhorn-usr-eci PRIVATE
        usr-include
        rt-include
        core
        usr-include  # usr implements usr as a pausr of it
        ${CMAKE_SOURCE_DIR}/../hw/gen/eci
        ${MACKEREL_ROOT})
target_compile_definitions(lauberhorn-usr-eci PRIVATE
        NIC_IMPL=eci)

# TODO: lauberhorn-rt-pcie

# apps

#add_executable(lauberhorn-rt-eci-test
#        apps/rt-eci-test/rt-eci-test.c)
#target_include_directories(lauberhorn-rt-eci-test PRIVATE
#        rt-include
#        usr-include
#        ${CMAKE_SOURCE_DIR}/../hw/gen/eci)
#target_link_libraries(lauberhorn-rt-eci-test
#        lauberhorn-rt-eci)
#target_compile_definitions(lauberhorn-rt-eci-test PRIVATE
#        NIC_IMPL=eci)
