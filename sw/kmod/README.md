# Lauberhorn kernel module

## Compiling

The easiest way to compile is to use the Nix flake.  On a `linux-amd64` Nix installation
(doesn't have to be NixOS!), execute:

```console
$ nix build ".#kmod" -L
```

The result kernel module is in `result/lauberhorn.ko`, correctly cross-compiled and ready
to be loaded onto an Enzian.

## Usage

Load only a module built with the image's generated headers. The driver rejects
an incompatible cooperative ABI or a reset-required session. It creates one
pinned idle-dispatch thread per worker; SGI 8 wakes idle dispatch, SGI 9 reports
fatal domain violations, and SGI 15 serves bypass traffic.

Application mappings contain stable physical-worker aliases. Explicit thread
registration and `lh_yield()` provide activation; mmap does neither. Unsupported
exit retains claims, identities and resources until a full coherence reset.
See [the scheduling contract](../../docs/software/scheduling-control-interface.md).


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
