# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2025 Pengcheng Xu

ifndef HW_CFG_HDRS
	$(error HW_CFG_HDRS is not set, build with Nix)
endif

ifndef MACKEREL_DEV_HDRS
	$(error MACKEREL_DEV_HDRS is not set, build with Nix)
endif

LH_ENABLE_TRACING ?= 1
LH_BACKEND_VM ?= 0
override CFLAGS += -DLH_BACKEND_VM=$(LH_BACKEND_VM)
override CFLAGS += -DLH_ENABLE_TRACING=$(LH_ENABLE_TRACING)

# Base flags shared across all architectures
override CFLAGS += -O2 -g -pipe -fpic \
           -Wall -Wno-maybe-uninitialized \
           -Iinclude/ -I../include/ -I../core/ -I./ \
           -I$(HW_CFG_HDRS) -I$(MACKEREL_DEV_HDRS)
override LDFLAGS += -g -shared -Wl,-z,defs


# Target architecture and CPU tuning are supplied by the Nix derivation.

ifdef LAUBERHORN_SIMULATION
$(error This runtime targets the real ECI transport; the kernel mock is not imported)
endif

all: liblauberhorn.so
ALL_SRCS := $(wildcard *.c) $(wildcard fiber/*.c) $(wildcard utils/*.c)

ifeq ($(LH_ENABLE_TRACING),0)
ALL_SRCS := $(filter-out server_trace.c pmu.c,$(ALL_SRCS))
endif

include ../usr-common.mk

fiber/%.o: fiber/%.c | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

utils/%.o: utils/%.c | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $< 

liblauberhorn.so: $(ALL_OBJS)
	$(LINK.c) $^ -o $@ $(LDLIBS)

clean:
	rm -f *.o *.so
	rm -rf $(DEPDIR)
	rm -f ./fiber/*.o
	
.PHONY: clean all
