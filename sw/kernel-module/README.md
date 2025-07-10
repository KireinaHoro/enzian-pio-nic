# PIO NIC kernel module

## Compiling
If you are building the module for the host you can just run `make` in this directory to compile the kernel module.
To cross-compile do the following
1. Tell the build system what kernel to build for:
   ```sh
   export KDIR=<path to kernel build directory>
   ```
2. Set the target architecture:
   ```sh
   export ARCH=<target architecture>
   ```
3. Tell the build system what cross-compiler to use
   ```sh
   export CROSS_COMPILE=<cross compiler prefix>
   ```
4. `make`

## Usage
Install the kernel module:
```sh
sudo insmod pionic.ko
```

See the dmesg (-w following + -H human-readable):
```sh
dmesg -wH
```

Uninstall the kernel module:
```sh
sudo rmmod pionic
```

## License

This module is dual-licensed under the 3-clause BSD license and and GPL 2.0.

`SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only`
