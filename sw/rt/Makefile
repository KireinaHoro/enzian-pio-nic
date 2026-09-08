# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2025 Pengcheng Xu

ifndef HW_CFG_HDRS
	$(error HW_CFG_HDRS is not set, build with Nix)
endif

ifndef MACKEREL_DEV_HDRS
	$(error MACKEREL_DEV_HDRS is not set, build with Nix)
endif

ARCH ?= arm64

# Base flags shared across all architectures
CFLAGS  := -O2 -g -pipe -fpic \
           -Wall -Wno-maybe-uninitialized \
           -Iinclude/ -I../include/ -I../core/ -I./ \
           -I$(HW_CFG_HDRS) -I$(MACKEREL_DEV_HDRS)
LDFLAGS := -g -shared -Wl,-z,defs

# architecture specific flags 
ifeq ($(ARCH),arm64)
	CFLAGS += -march=armv8-a+crc+lse -mtune=thunderxt88
else ifeq ($(ARCH),x86_64)
	CFLAGS += -march=native
	CROSS_COMPILE :=
endif 

ifdef LAUBERHORN_SIMULATION
$(error This runtime targets the real ECI transport; the kernel mock is not imported)
endif

all: liblauberhorn.so
ALL_SRCS := $(wildcard *.c) $(wildcard fiber/*.c) $(wildcard utils/*.c)

include ../usr-common.mk

fiber/%.o: fiber/%.c | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

utils/%.o: utils/%.c | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $< 

liblauberhorn.so: $(ALL_OBJS)
	$(CC) $^ -o $@ $(LDFLAGS)

clean:
	rm -f *.o *.so
	rm -rf $(DEPDIR)
	rm -f ./fiber/*.o
	
.PHONY: clean all