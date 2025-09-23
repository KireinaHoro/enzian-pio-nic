# SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
# Copyright (c) 2025 Zikai Liu, Pengcheng Xu

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
