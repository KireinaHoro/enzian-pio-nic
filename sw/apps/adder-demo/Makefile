# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2025 Pengcheng Xu

ifndef LAUBERHORN_RT
$(error LAUBERHORN_RT is not set, build with Nix)
endif

CFLAGS  := -O2 -g -pipe \
		   -march=armv8-a+crc+lse -mtune=thunderxt88 \
		   -Wall \
		   -Iinclude/ -I../include/ $(shell pkg-config --cflags libtirpc)
LDFLAGS := -g -L:$(LAUBERHORN_RT) $(shell pkg-config --libs libtirpc)

APP := adder-demo

all: $(APP)

ALL_SRCS := $(wildcard *.c) add_xdr.c

add_xdr.c: add.x add.h
	rpcgen -c $< -o $@
add.h: add.x
	rpcgen -h $< -o $@

include ../../usr-common.mk

$(APP): $(ALL_OBJS)
	$(LINK.c) $^ -o $@
	
clean:
	rm -f *.o $(APP)
	rm -rf $(DEPDIR)