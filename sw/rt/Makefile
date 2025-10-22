CROSS_COMPILE ?= aarch64-unknown-linux-gnu-
CC := $(CROSS_COMPILE)gcc
CFLAGS  := -O2 -pipe -Wall -Iinclude/ -I../include/ -fpic
LDFLAGS := -shared

DEPDIR := .deps
DEPFLAGS = -MT $@ -MMD -MP -MF $(DEPDIR)/$*.d

COMPILE.c = $(CC) $(DEPFLAGS) $(CFLAGS) $(CPPFLAGS) -c
LINK.c = $(CC) $(LDFLAGS)

$(DEPDIR): ; @mkdir -p $@
	
%.o: %.c
%.o: %.c $(DEPDIR)/%.d | $(DEPDIR)
	$(COMPILE.c) $(OUTPUT_OPTION) $<
	
ALL_SRCS := $(wildcard *.c)

liblauberhorn_eci.so: eci.o
	$(LINK.c) $^ -o $@
	
DEPFILES := $(foreach s,$(ALL_SRCS),$(patsubst %.c,$(DEPDIR)/%.d,$(notdir $(s))))
$(DEPFILES):
	
clean:
	rm -f *.o *.so
	rm -rf $(DEPDIR)
.PHONY: clean

-include $(wildcard $(DEPFILES))