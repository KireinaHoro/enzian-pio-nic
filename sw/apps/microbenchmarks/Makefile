# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2025 Pengcheng Xu

PKG_CONFIG ?= pkg-config

override CFLAGS += -O2 -g -pipe \
		   -march=armv8-a+crc+lse -mtune=thunderxt88 \
		   -Wall -Wno-unused-variable -Wno-unused-label \
		   -Iinclude/ $(shell $(PKG_CONFIG) --cflags lauberhorn)
override LDFLAGS += -g
override LDLIBS += $(shell $(PKG_CONFIG) --libs lauberhorn)

APP := microbenchmarks

all: $(APP)

ALL_SRCS := $(wildcard *.c) bench_xdr.c

bench_xdr.c: bench.x bench.h
	rpcgen -c $< > $@.tmp && mv $@.tmp $@
bench.h: bench.x
	rpcgen -h $< > $@.tmp && mv $@.tmp $@
echo_registry.h: bench.x echo_registry.py
	python3 echo_registry.py bench.x $@
main.c: bench.h echo_registry.h

include ../../usr-common.mk

$(APP): $(ALL_OBJS)
	$(LINK.c) $^ -o $@ $(LDLIBS)
	
clean:
	rm -f *.o $(APP) bench.h bench_xdr.c echo_registry.h
	rm -rf $(DEPDIR)
