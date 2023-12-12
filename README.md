# Programmed I/O (PIO) NIC on Enzian

This repo contains a very simple programmed I/O-based NIC for Enzians.

## Setup

Install [Mill](https://github.com/com-lihaoyi/mill), the better build tool for Scala:

```console
$ sudo apt install default-jdk
$ mkdir -p $HOME/.local/bin
$ echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc # or your shell of choice
$ curl -L https://raw.githubusercontent.com/lefou/millw/0.4.10/millw > $HOME/.local/bin/mill && chmod +x $HOME/.local/bin/mill
```

Install the latest version of [Verilator](https://github.com/verilator/verilator) (the version with Ubuntu 22.04 is too old):

```console
$ wget https://github.com/verilator/verilator/archive/refs/tags/v5.018.tar.gz -O verilator.tgz
$ tar xvf verilator.tgz && cd verilator-5.018
$ sudo apt install build-essential autoconf flex bison help2man
$ autoconf
$ ./configure --prefix=$PWD/install
$ make -j$(nproc)
$ make install
$ echo "export PATH=\"$PWD/install/bin:\$PATH\"" >> ~/.bashrc # or your shell of choice
$ echo "export VERILATOR_ROOT=\"$PWD\"" >> ~/.bashrc
$ source ~/.bashrc && verilator --version
Verilator 5.018 2023-10-30 rev UNKNOWN.REV
```

Clone with submodules and check if mill is working:

```console
$ git clone --recursive git@gitlab.inf.ethz.ch:pengxu/enzian-pio-nic.git
$ cd enzian-pio-nic
$ mill version
[1/1] version
0.11.6
```

## Generate Output Products

Generate Verilog into `hw/gen/`:

```console
$ mill pioNicEngineModule.runMain pionic.PioNicEngineVerilog
[49/49] pioNicEngineModule.run
[Runtime] SpinalHDL v1.9.0    git head : 7d30dbacbd3aa1be42fb2a3d4da5675703aae2ae
[Runtime] JVM max memory : 16012.0MiB
[Runtime] Current date : 2023.12.11 16:53:30
[Progress] at 0.000 : Elaborate components
[Progress] at 0.405 : Checks and transforms
[Progress] at 0.581 : Generate Verilog
[Warning] 206 signals were pruned. You can call printPruned on the backend report to get more informations.
[Done] at 0.714
$ file hw/gen/Merged.v
hw/gen/Merged.v: ASCII text
```

Run test benches:

```console
# TODO!
```

Create Vivado project and generate the bitstream:

```console
# TODO!
```

## Devs Area

Create project for IntelliJ IDEA:

```console
$ mill mill.idea.GenIdea/idea
```
