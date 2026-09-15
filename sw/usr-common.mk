# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2025 Pengcheng Xu

CROSS_COMPILE ?= aarch64-unknown-linux-gnu-
ifeq ($(origin CC),default)
CC = $(CROSS_COMPILE)gcc
endif
PKG_CONFIG ?= pkg-config

override CFLAGS += $(shell $(PKG_CONFIG) --cflags libtirpc)
override LDLIBS += $(shell $(PKG_CONFIG) --libs libtirpc)

COMPILE.c = $(CC) $(DEPFLAGS) $(CFLAGS) $(CPPFLAGS) -c
LINK.c = $(CC) $(LDFLAGS)

DEPDIR := .deps
$(DEPDIR): ; @mkdir -p $@

DEPFLAGS = -MT $@ -MMD -MP -MF $(DEPDIR)/$*.d

DEPFILES := $(foreach s,$(ALL_SRCS),$(patsubst %.c,$(DEPDIR)/%.d,$(notdir $(s))))
$(DEPFILES):

%.o: %.c
%.o: %.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<
	
# whoever includes us need to define ALL_SRCS
ifndef ALL_SRCS
$(error ALL_SRCS not defined)
endif

ALL_OBJS := $(patsubst %.c,%.o,$(ALL_SRCS))

-include $(wildcard $(DEPFILES))