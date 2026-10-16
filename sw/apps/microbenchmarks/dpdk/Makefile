CC ?= cc
PKG_CONFIG ?= pkg-config
CFLAGS ?= -O3 -g
CFLAGS += -Wall -Wextra -Werror -std=gnu11
echo_registry.h: ../bench.x ../echo_registry.py
	python3 ../echo_registry.py ../bench.x $@

.PHONY: all check clean
.DEFAULT_GOAL := all
all: bench_dpdk echo_dpdk echo_dpdk_uninstrumented
bench_dpdk: bench_dpdk.c benchmarks.c benchmark.h wire.h echo_registry.h load.h open_loop.h aggregate.h
	$(CC) $(CFLAGS) $(shell $(PKG_CONFIG) --cflags libdpdk) $< benchmarks.c -o $@ $(shell $(PKG_CONFIG) --libs libdpdk)
echo_dpdk: echo_server.c benchmarks.c benchmark.h wire.h echo_registry.h
	$(CC) $(CFLAGS) $(shell $(PKG_CONFIG) --cflags libdpdk) $< benchmarks.c -o $@ $(shell $(PKG_CONFIG) --libs libdpdk)
echo_dpdk_uninstrumented: echo_server.c benchmarks.c benchmark.h wire.h echo_registry.h
	$(CC) $(CFLAGS) -DLH_ENABLE_TRACING=0 $(shell $(PKG_CONFIG) --cflags libdpdk) $< benchmarks.c -o $@ $(shell $(PKG_CONFIG) --libs libdpdk)
wire_test: wire_test.c benchmarks.c benchmark.h wire.h echo_registry.h
	$(CC) $(CFLAGS) $< benchmarks.c -o $@
open_loop_test: open_loop_test.c open_loop.h
	$(CC) $(CFLAGS) $< -o $@
../bench.h: ../bench.x
	cd .. && rpcgen -h bench.x > bench.h.tmp && mv bench.h.tmp bench.h
../bench_xdr.c: ../bench.x ../bench.h
	cd .. && rpcgen -c bench.x > bench_xdr.c.tmp && mv bench_xdr.c.tmp bench_xdr.c
schema_test: schema_test.c benchmarks.c ../bench_xdr.c ../bench.h echo_registry.h
	$(CC) $(CFLAGS) -Wno-unused-variable -Wno-cast-function-type $(shell $(PKG_CONFIG) --cflags libtirpc) schema_test.c benchmarks.c ../bench_xdr.c -o $@ $(shell $(PKG_CONFIG) --libs libtirpc)
aggregate_test: aggregate_test.c bench_dpdk.c aggregate.h load.h open_loop.h benchmarks.c benchmark.h wire.h echo_registry.h
	$(CC) $(CFLAGS) $(shell $(PKG_CONFIG) --cflags libdpdk) $< benchmarks.c -o $@ $(shell $(PKG_CONFIG) --libs libdpdk)
check: wire_test open_loop_test schema_test aggregate_test
	./wire_test
	./open_loop_test
	./schema_test
	./aggregate_test
clean:
	rm -f bench_dpdk echo_dpdk echo_dpdk_uninstrumented wire_test open_loop_test schema_test echo_registry.h
