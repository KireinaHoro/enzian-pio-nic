CROSS_COMPILE ?= aarch64-linux-gnu-
CC := $(CROSS_COMPILE)gcc
AR := $(CROSS_COMPILE)ar
CFLAGS ?= -O2 -pipe -Wall -I../include

DRIVERS := $(patsubst ../%.c,%,$(wildcard ../*.c))

SRCS := $(wildcard *.c) $(DRIVERS:%=../%.c)
DRIVER_OBJS := $(DRIVERS:%=%.o)

# https://make.mad-scientist.net/papers/advanced-auto-dependency-generation/
DEPDIR := .deps
DEPFLAGS = -MT $@ -MMD -MP -MF $(DEPDIR)/$*.d

COMPILE.c = $(CC) $(DEPFLAGS) $(CFLAGS) $(CPPFLAGS) -c

all: $(APP)

debug: CFLAGS += -DDEBUG -DDEBUG_REG -g
debug: $(APP)

%.o: %.c
%.o: %.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

$(DRIVER_OBJS): %.o: ../%.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) -I../../hw/gen/$(NIC_IMPL)/ $(OUTPUT_OPTION) $<

$(DEPDIR): ; @mkdir -p $@

DEPFILES := $(foreach s,$(SRCS),$(patsubst %.c,$(DEPDIR)/%.d,$(notdir $(s))))
$(DEPFILES):

libpionic.a: core.o $(DRIVER_OBJS)
	$(AR) rcs $@ $^

$(APP): $(APP).o libpionic.a
	$(CC) $(CFLAGS) $< -L. -lpionic -o $@

clean:
	rm -f *.o *.a $(APP)
	rm -rf $(DEPDIR)

.PHONY: all clean
-include $(wildcard $(DEPFILES))
