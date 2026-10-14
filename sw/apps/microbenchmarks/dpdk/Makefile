CC ?= cc
PKG_CONFIG ?= pkg-config
CFLAGS ?= -O3 -g
CFLAGS += -Wall -Wextra -Werror -std=gnu11
.PHONY: all check clean
all: bench_dpdk nullrpc_dpdk
bench_dpdk: bench_dpdk.c benchmarks.c benchmark.h wire.h load.h
	$(CC) $(CFLAGS) $(shell $(PKG_CONFIG) --cflags libdpdk) $< benchmarks.c -o $@ $(shell $(PKG_CONFIG) --libs libdpdk)
nullrpc_dpdk: nullrpc_server.c wire.h
	$(CC) $(CFLAGS) $(shell $(PKG_CONFIG) --cflags libdpdk) $< -o $@ $(shell $(PKG_CONFIG) --libs libdpdk)
wire_test: wire_test.c benchmarks.c benchmark.h wire.h
	$(CC) $(CFLAGS) $< benchmarks.c -o $@
check: wire_test
	./wire_test
clean:
	rm -f bench_dpdk nullrpc_dpdk wire_test
