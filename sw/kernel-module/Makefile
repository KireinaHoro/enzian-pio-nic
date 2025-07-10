# SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
# Copyright (c) 2025 Zikai Liu

KDIR ?= /lib/modules/`uname -r`/build

obj-m += pionic.o
pionic-y := main.o ioctl.o

kbuild:
	make -C $(KDIR) M=`pwd`

clean:
	make -C $(KDIR) M=`pwd` clean

insmod: kbuild
	sudo insmod pionic.ko

rmmod:
	sudo rmmod pionic
