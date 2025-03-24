# https://stackoverflow.com/a/324782
SW_ROOT := $(patsubst %/,%,$(dir $(lastword $(MAKEFILE_LIST))))

# NIC_IMPL must be defined
# ifndef NIC_IMPL
# $(error Please set NIC_IMPL to either eci or pcie)
# endif

CROSS_COMPILE ?= aarch64-linux-gnu-
CC := $(CROSS_COMPILE)gcc
AR := $(CROSS_COMPILE)ar
CFLAGS ?= -pipe -Wall -Wno-unused-function -I$(SW_ROOT)/usr-include -static

CARGO := cargo
MACKEREL_ROOT := $(SW_ROOT)/../deps/mackerel2
MACKEREL := $(MACKEREL_ROOT)/target/release/mackerel2

RT_HEADERS := $(SW_ROOT)/rt-include
RT_HEADERS_GEN := $(RT_HEADERS)/gen

DEVFILES_DIR := $(SW_ROOT)/devices
DEVICES := $(patsubst $(DEVFILES_DIR)/%.dev,%,$(wildcard $(DEVFILES_DIR)/*.dev))
DEVICE_HEADERS := $(DEVICES:%=$(RT_HEADERS_GEN)/%.h)

DRIVERS_DIR := $(SW_ROOT)/drivers
DRIVER_SRCS := $(wildcard $(DRIVERS_DIR)/*.c)
DRIVER_OBJS := $(patsubst $(DRIVERS_DIR)/%.c,%.o,$(DRIVER_SRCS))

IMPL_DIR := $(SW_ROOT)/$(NIC_IMPL)
IMPL_SRCS := $(wildcard $(IMPL_DIR)/*.c)
IMPL_OBJS := $(patsubst $(IMPL_DIR)/%.c,%.o,$(IMPL_SRCS))

RT_SRCS := $(DRIVER_SRCS) $(IMPL_SRCS)
RT_OBJS := $(DRIVER_OBJS) $(IMPL_OBJS)

ALL_SRCS := $(wildcard *.c) $(RT_SRCS)

# https://make.mad-scientist.net/papers/advanced-auto-dependency-generation/
DEPDIR := .deps
DEPFLAGS = -MT $@ -MMD -MP -MF $(DEPDIR)/$*.d

COMPILE.c = $(CC) $(DEPFLAGS) $(CFLAGS) $(CPPFLAGS) -c

headers: $(DEVICE_HEADERS)

$(DEVICE_HEADERS): $(RT_HEADERS_GEN)/%.h: $(SW_ROOT)/devices/%.dev $(MACKEREL) | $(RT_HEADERS_GEN)
	$(MACKEREL) -c $< -I$(SW_ROOT)/devices/ -o $@

all: CFLAGS += -O2
all: $(APP)

debug: CFLAGS += -DDEBUG -DDEBUG_REG -g -O0
debug: $(APP)

# application object files -- no runtime headers allowed
%.o: %.c
%.o: %.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

# runtime object files -- include runtime headers
# escape percent sign to pass it actually to filter
PERCENT := %
.SECONDEXPANSION:
$(RT_OBJS): %.o: $$(filter $$(PERCENT)/%.c,$$(ALL_SRCS)) $(DEPDIR)/%.d $(DEVICE_HEADERS) | $(DEPDIR)
	$(COMPILE.c) -I$(SW_ROOT)/../hw/gen/$(NIC_IMPL)/ -I$(RT_HEADERS) -I$(MACKEREL_ROOT) $(OUTPUT_OPTION) $<

$(MACKEREL):
	$(CARGO) build --release --manifest-path=$(MACKEREL_ROOT)/Cargo.toml

$(DEPDIR) $(RT_HEADERS_GEN): ; @mkdir -p $@

DEPFILES := $(foreach s,$(ALL_SRCS),$(patsubst %.c,$(DEPDIR)/%.d,$(notdir $(s))))
$(DEPFILES):

libpionic.a: core.o $(DRIVER_OBJS)
	$(AR) rcs $@ $^

$(APP): $(APP).o libpionic.a
	$(CC) $(CFLAGS) $< -L. -lpionic -o $@

clean:
	rm -f *.o *.a $(APP)
	rm -rf $(RT_HEADERS_GEN)
	rm -rf $(DEPDIR)

.PHONY: all clean
-include $(wildcard $(DEPFILES))
