# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2025 Pengcheng Xu

ifndef LAUBERHORN_RT
$(error LAUBERHORN_RT is not set, build with Nix)
endif

CFLAGS  := -O2 -g -pipe \
		   -march=armv8-a+crc+lse -mtune=thunderxt88 \
		   -Wall -Wno-unused-variable -Wno-unused-label \
		   -Iinclude/ -I../../include/
LDFLAGS := -g -L$(LAUBERHORN_RT) -llauberhorn

APP := microbenchmarks

all: $(APP)

ALL_SRCS := $(wildcard *.c) bench_xdr.c

bench_xdr.c: bench.x bench.h
	rpcgen -c $< -o $@
bench.h: bench.x
	rpcgen -h $< -o $@
main.c: bench.h

include ../../usr-common.mk

$(APP): $(ALL_OBJS)
	$(LINK.c) $^ -o $@
	
clean:
	rm -f *.o $(APP) bench.h bench_xdr.c
	rm -rf $(DEPDIR)
