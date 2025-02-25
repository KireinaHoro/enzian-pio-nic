KDIR ?= /lib/modules/`uname -r`/build

obj-m+=pionic.o

kbuild:
	make -C $(KDIR) M=`pwd`

clean:
	make -C $(KDIR) M=`pwd` clean