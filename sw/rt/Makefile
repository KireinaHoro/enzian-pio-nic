# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2025 Pengcheng Xu

ifndef HW_CFG_HDRS
$(error HW_CFG_HDRS is not set, build with Nix)
endif

ifndef MACKEREL_DEV_HDRS
$(error MACKEREL_DEV_HDRS is not set, build with Nix)
endif

CROSS_COMPILE ?= aarch64-unknown-linux-gnu-
CC := $(CROSS_COMPILE)gcc
CFLAGS  := -O2 -pipe -Wall -fpic -Wno-maybe-uninitialized \
		   -Iinclude/ -I../include/ -I../core/ \
		   -I$(HW_CFG_HDRS) -I$(MACKEREL_DEV_HDRS)
LDFLAGS := -shared -Wl,-z,defs

DEPDIR := .deps
DEPFLAGS = -MT $@ -MMD -MP -MF $(DEPDIR)/$*.d

COMPILE.c = $(CC) $(DEPFLAGS) $(CFLAGS) $(CPPFLAGS) -c
LINK.c = $(CC) $(LDFLAGS)

$(DEPDIR): ; @mkdir -p $@

%.o: %.c
%.o: %.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

ALL_SRCS := $(wildcard *.c)
ALL_OBJS := $(patsubst %.c,%.o,$(ALL_SRCS))

liblauberhorn_eci.so: $(ALL_OBJS)
	$(LINK.c) $^ -o $@

DEPFILES := $(foreach s,$(ALL_SRCS),$(patsubst %.c,$(DEPDIR)/%.d,$(notdir $(s))))
$(DEPFILES):

clean:
	rm -f *.o *.so
	rm -rf $(DEPDIR)
.PHONY: clean

-include $(wildcard $(DEPFILES))
