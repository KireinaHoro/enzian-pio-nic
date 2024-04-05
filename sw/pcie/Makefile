CROSS_COMPILE ?= aarch64-linux-gnu-
CC := $(CROSS_COMPILE)gcc
AR := $(CROSS_COMPILE)ar
CFLAGS := -O2 -pipe -Wall -I../include

DRIVERS := cmac

SRCS := $(wildcard *.c) $(DRIVERS:%=../%.c)
DRIVER_OBJS := $(DRIVERS:%=%.o)

# https://make.mad-scientist.net/papers/advanced-auto-dependency-generation/
DEPDIR := .deps
DEPFLAGS = -MT $@ -MMD -MP -MF $(DEPDIR)/$*.d

COMPILE.c = $(CC) $(DEPFLAGS) $(CFLAGS) $(CPPFLAGS) -c

all: pionic-test

debug: CFLAGS += -DDEBUG -DDEBUG_REG -g
debug: pionic-test

%.o: %.c
%.o: %.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

$(DRIVER_OBJS): %.o: ../%.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

$(DEPDIR): ; @mkdir -p $@

DEPFILES := $(foreach s,$(SRCS),$(patsubst %.c,$(DEPDIR)/%.d,$(notdir $(s))))
$(DEPFILES):

libpionic.a: pionic.o $(DRIVER_OBJS)
	$(AR) rcs $@ $^

pionic-test: test.o libpionic.a
	$(CC) $(CFLAGS) $< -L. -lpionic -o $@

clean:
	rm -f *.o *.a pionic-test
	rm -rf $(DEPDIR)

.PHONY: all clean
-include $(wildcard $(DEPFILES))
