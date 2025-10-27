# SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
# Copyright (c) 2025 Zikai Liu, Pengcheng Xu

ifndef HW_CFG_HDRS
$(error HW_CFG_HDRS is not set, build with Nix)
endif

ifndef MACKEREL_DEV_HDRS
$(error MACKEREL_DEV_HDRS is not set, build with Nix)
endif

KDIR ?= /lib/modules/`uname -r`/build

ccflags-y += -I$(MACKEREL_DEV_HDRS) -I$(HW_CFG_HDRS) -I$(M) -I$(M)/../core/

obj-m += lauberhorn.o
lauberhorn-y := main.o bypass.o misc.o worker.o sched.o chrdev.o cmac.o

kbuild:
	make -C $(KDIR) M=`pwd`

clean:
	make -C $(KDIR) M=`pwd` clean

insmod: kbuild
	sudo insmod lauberhorn.ko

rmmod:
	sudo rmmod lauberhorn
