# SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
# Copyright (c) 2025 Zikai Liu

KDIR ?= /lib/modules/`uname -r`/build

ccflags-y += -I$(MACKEREL_DEV_HDRS)

obj-m += lauberhorn.o
lauberhorn-y := main.o ioctl.o bypass.o

kbuild:
	make -C $(KDIR) M=`pwd`

clean:
	make -C $(KDIR) M=`pwd` clean

insmod: kbuild
	sudo insmod lauberhorn.ko

rmmod:
	sudo rmmod lauberhorn
