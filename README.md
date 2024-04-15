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
$ git clone https://github.com/verilator/verilator -b v5.024 --depth=1 && cd verilator
$ sudo apt install build-essential autoconf flex bison help2man
$ autoconf
$ ./configure --prefix=$PWD/install
$ make -j$(nproc)
$ make install
$ echo "export PATH=\"$PWD/install/bin:\$PATH\"" >> ~/.bashrc # or your shell of choice
$ source ~/.bashrc && verilator --version
Verilator 5.024 2024-04-05 rev v5.024
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
$ mill eci.generateVerilog
$ file hw/gen/eci/NicEngine{{,_ips}.v,{,_ooc}.xdc}
hw/gen/eci/NicEngine.v:       ASCII text
hw/gen/eci/NicEngine_ips.v:   ASCII text
hw/gen/eci/NicEngine.xdc:     ASCII text
hw/gen/eci/NicEngine_ooc.xdc: ASCII text
```

Run test benches:

```console
$ mill gen.test -- -P8 # run all test suites in parallel with 8 threads
...
[Progress] Verilator compilation done in 4941.624 ms
NicSim:
[info] simulation transcript at /local/home/pengxu/work-local/enzian-pio-nic/simWorkspace/pcie/rx-regular/sim_transcript.log.gz
- rx-regular
...
$ mill gen.test.testOnly pionic.eci.NicSim # run only the test suite for ECI integration test
...
```

Simulation transcripts are stored in `simWorkspace/<design>/<test>/sim_transcript_<setup seed>_<sim seed>.log.gz`; they are compressed to save disk space.  To browse the transcript:

```console
$ vim <transcript>          # to view in an editor, after the simulation has finished (vim supports gz files)
$ gztool -T <transcript>    # to follow the transcript (in the same way as `tail -f`)
```

You can reproduce a specific simulation (for debugging) by supplying the exact setup and sim seeds to `NicSim` with the command at the start of the transcript:

```console
$ zcat /local/home/pengxu/work-local/enzian-pio-nic/simWorkspace/PacketAlloc/simple-allocate-free/sim_transcript.log.gz
>>>>> Simulation transcript pionic.PacketAllocSim for test simple-allocate-free
>>>>> To reproduce: mill gen.test.testOnly pionic.PacketAllocSim -- -t simple-allocate-free -DsetupSeed=1024949829 -DsimSeed=-1610542669 -DprintSimLog=true

[0] [Progress] Start PacketAlloc simple-allocate-free simulation with seed 565014101
...
```

Create Vivado project and generate the bitstream:

```console
$ mill --no-server pcie.generateBitstream
...

$ file out/pcie/vivadoProject.dest/pio-nic-pcie/pio-nic-pcie.runs/impl_1/pio-nic-pcie.{bit,ltx}
[...]/pio-nic-pcie.bit: Xilinx BIT data - from design_1_wrapper;COMPRESS=TRUE;UserID=0XFFFFFFFF;Version=2022.1 - for xcvu9p-flgb2104-3-e - built 2023/12/23(11:09:14) - data length 0x1b2cf00
[...]/pio-nic-pcie.ltx: ASCII text
```

To create a bitstream for the ECI NIC, we need a checkpoint of the [static shell](https://gitlab.inf.ethz.ch/project-openenzian/fpga-stack/static-shell).  Assuming you have `static_shell_routed.dcp` in `~/Downloads`:

```console
$ ENZIAN_SHELL_DIR=~/Downloads mill --no-server eci.generateBitstream
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
pionic-test: ELF 64-bit LSB executable, ARM aarch64, version 1 (GNU/Linux), statically linked, BuildID[sha1]=898cb76f926551cdf354110ac0a269dbe271c93e, for GNU/Linux 3.7.0, not stripped
$ cd sw/eci && make
```

The PCIe test should be ran on an Enzian with a PCIe cable between the CPU and FPGA (`zuestoll11-12` at the moment).  The ECI test should work on any Enzian.

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
$ vivado out/eci/vivadoProject.dest/pio-nic-eci/pio-nic-eci.xpr # synth-only project!
```

For the PCIe project, remember to export the project Tcl again to keep the build process reproducible.  In Vivado's Tcl console:

```tcl
write_project_tcl -no_ip_version -paths_relative_to ./vivado/pcie -force vivado/pcie/create_project.tcl
```

For the ECI project, remember to export the block design Tcl again.  In Vivado's Tcl console:

```tcl
write_bd_tcl -force vivado/eci/bd/design_1.tcl
```

Update `vivado/eci/create_project.tcl` accordingly (the script is hand-written).
