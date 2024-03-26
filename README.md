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
$ mill pcie.generateVerilog
$ file hw/gen/pcie/NicEngine{{,_ips}.v,{,_ooc}.xdc}
hw/gen/pcie/NicEngine.v:       ASCII text
hw/gen/pcie/NicEngine_ips.v:   ASCII text
hw/gen/pcie/NicEngine.xdc:     ASCII text
hw/gen/pcie/NicEngine_ooc.xdc: ASCII text
TODO ECI
```

Run test benches:

```console
$ mill pcie.runMain pionic.pcie.sim.NicSim
...
[Progress] Verilator compilation done in 5542.813 ms
[Progress] Start PioNicEngine rx-regular simulation with seed 1445906924
...
[Done] Simulation done in 46.215 ms
[Progress] Start PioNicEngine tx-regular simulation with seed 71106236
...
[Done] Simulation done in 16.665 ms
$ # TODO ECI
```

Simulation transcripts are stored in `simWorkspace/<design>/<test>/sim_transcript_<seed>.log.gz`; they are compressed to save disk space.  To browse the transcript:

```console
$ zcat <transcript> | vim - # to view in an editor, after the simulation has finished
$ gztool -T <transcript>    # to follow the transcript (in the same way as `tail -f`)
```

Create Vivado project and generate the bitstream:

```console
$ mill --no-server pcie.generateBitstream
...

$ file out/pcie/vivadoProject.dest/pio-nic-pcie/pio-nic-pcie.runs/impl_1/pio-nic-pcie.{bit,ltx}
[...]/pio-nic-pcie.bit: Xilinx BIT data - from design_1_wrapper;COMPRESS=TRUE;UserID=0XFFFFFFFF;Version=2022.1 - for xcvu9p-flgb2104-3-e - built 2023/12/23(11:09:14) - data length 0x1b2cf00
[...]/pio-nic-pcie.ltx: ASCII text
```

**Note**: if mill complains about not being able to find Vivado, try killing the mill server:

```console
$ ps aux | grep '[M]illServerMain' | awk '{print $2}' | xargs kill
$ mill --no-server <...>
```

This is due to the old mill server still using the old `PATH` environment.  Running mill with `--no-server` forces mill to run as a standalone process (thus forcing the new environment every time); this also helps if Vivado's output is not printed to the standard output (but instead to the server process).

Build userspace software (drivers):

```console
$ cd sw/pcie && make
...
$ file pionic-test
pionic-test: ELF 64-bit LSB shared object, ARM aarch64, version 1 (SYSV), dynamically linked, interpreter /lib/ld-linux-aarch64.so.1, BuildID[sha1]=f5f6636f5ffa34cc6577c7be2778856dfaeb3f80, for GNU/Linux 3.7.0, with debug_info, not stripped
$ # TODO ECI
```

The PCIe test should be ran on an Enzian with a PCIe cable between the CPU and FPGA (`zuestoll11-12` at the moment).

## Devs Area

Create project for IntelliJ IDEA:

```console
$ mill mill.idea.GenIdea/idea
```

Interact with the Vivado project (e.g. change the block design, read timing reports, etc.):

```console
$ mill --no-server pcie.vivadoProject
...
$ vivado out/pcie/vivadoProject.dest/pio-nic-pcie/pio-nic-pcie.xpr
```

Remember to export the project Tcl again to keep the build process reproducible.  In Vivado's Tcl console:

```tcl
write_project_tcl -no_ip_version -paths_relative_to ./vivado/pcie -force vivado/pcie/create_project.tcl
```
