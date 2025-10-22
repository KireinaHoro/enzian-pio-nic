# Lauberhorn kernel module

## Compiling

The easiest way to compile is to use the Nix flake.  On a `linux-amd64` Nix installation
(doesn't have to be NixOS!), execute:

```console
$ nix build ".#lauberhorn-kmod" -L
```

The result kernel module is in `result/lauberhorn.ko`, correctly cross-compiled and ready
to be loaded onto an Enzian.

## Usage

Install the kernel module and observe dmesg:

```console
# insmod lauberhorn.ko
# dmesg | tail -n 20
[...]
[  207.140871] lauberhorn: loading out-of-tree module taints kernel.
[  207.140893] lauberhorn: module verification failed: signature and/or required key missing - tainting kernel
[  207.142135] lauberhorn: Lauberhorn kernel module init (compiled for db3d5bdf2b460037)
[  207.142160] lauberhorn: Mapping I/O memory from node 1
[  207.142268] lauberhorn: Static shell version: e95338b6
[  207.142277] lauberhorn: Lauberhorn NIC version: 316d363876cb3bf
[  207.142283] lauberhorn: Using 4 cores 44-47 for RPC processing
[  207.142351] lauberhorn: Allocated interrupt number = 326
[  207.142865] lauberhorn: Setting ksoftirqd on core 44 (PID 332) to SCHED_FIFO
[  207.142884] lauberhorn: Setting ksoftirqd on core 45 (PID 339) to SCHED_FIFO
[  207.142895] lauberhorn: Setting ksoftirqd on core 46 (PID 346) to SCHED_FIFO
[  207.142905] lauberhorn: Setting ksoftirqd on core 47 (PID 353) to SCHED_FIFO
[  207.143649] net lauberhorn0: CMAC version: 3.1 (raw 0x301)
[  207.143666] net lauberhorn0: Our MAC address: 0c:53:31:03:00:28
[  207.143682] lauberhorn: Enabling prefix 0x0 on core slot 0
[  207.143748] net lauberhorn0: Allocated interrupt number = 327
[  207.143831] lauberhorn: chrdev major = 236, minor = 0
[  207.144086] lauberhorn: Device created at /dev/lauberhorn
[  207.144095] lauberhorn: Lauberhorn initialized
```

Verify that the bypass network interface has been created.  Set the correct MAC address and enable the interface:

```console
# ip addr
[...]
4: lauberhorn0: <BROADCAST,MULTICAST> mtu 9618 qdisc noop state DOWN group default qlen 1000
    link/ether 0c:53:31:03:00:28 brd ff:ff:ff:ff:ff:ff
# ip link set lauberhorn0 addr 0c:53:31:03:01:80 # first port on zuestoll12
# ip link set lauberhorn0 up                     # might take a couple attempts
```

To unload the kernel module:

```console
# rmmod lauberhorn
```

## License

This module is dual-licensed under the 3-clause BSD license and and GPL 2.0.

`SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only`
