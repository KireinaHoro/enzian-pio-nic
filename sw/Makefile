CROSS_COMPILE ?= aarch64-linux-gnu-
CC := $(CROSS_COMPILE)gcc
AR := $(CROSS_COMPILE)ar
CFLAGS := -O2 -g -pipe -Wall

SRCS := $(wildcard *.c)

# https://make.mad-scientist.net/papers/advanced-auto-dependency-generation/
DEPDIR := .deps
DEPFLAGS = -MT $@ -MMD -MP -MF $(DEPDIR)/$*.d

COMPILE.c = $(CC) $(DEPFLAGS) $(CFLAGS) $(CPPFLAGS) -c

all: pionic-test

%.o: %.c
%.o: %.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<

$(DEPDIR): ; @mkdir -p $@

DEPFILES := $(SRCS:%.c=$(DEPDIR)/%.d)
$(DEPFILES):

libpionic.a: pionic.o
	$(AR) rcs $@ $^

pionic-test: test.o libpionic.a
	$(CC) $(CFLAGS) $< -L. -lpionic -o $@

clean:
	rm -f *.o *.a pionic-test
	rm -rf $(DEPDIR)

.PHONY: all clean
-include $(wildcard $(DEPFILES))
