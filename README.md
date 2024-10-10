# Enzian Fast RPC

This repo contains the RPC-accelerating NIC for Enzian; it builds upon the old
PIO paper NIC artifact, now in the `pio-paper` branch.

## Setup

The recommended way to set up the environment is to use the [Docker
container](./Dockerfile), available at the ETH registry
`registry.ethz.ch/project-openenzian/ci-images/pionic-tools:latest`.  This
image is also used by the department GitLab CI for running unit tests and
building bitstreams.  For a local setup, refer to the Dockerfile for
instructions.

Set up `git` to use SSH for all HTTPS links on the D-INFK GitLab:

```console
$ git config --global url.ssh://git@gitlab.inf.ethz.ch/.insteadOf https://gitlab.inf.ethz.ch/
```

Clone with submodules:

```console
$ git clone --recursive git@gitlab.inf.ethz.ch:pengxu/enzian-pio-nic.git
$ cd enzian-pio-nic
```

## Generate Output Products

Run simulation test benches:

```console
$ mill gen.test -- -P8 # run all test suites in parallel with 8 threads
...
[Progress] Verilator compilation done in 4941.624 ms
NicSim:
[info] simulation transcript at /local/home/pengxu/work-local/enzian-pio-nic/simWorkspace/pcie/rx-regular/sim_transcript.log.gz
- rx-regular
...
$ mill gen.test.testOnly pionic.host.eci.NicSim # run only the test suite for ECI integration test
$ mill gen.test -l org.scalatest.tags.Slow # exclude slow-running integration tests
...
```

Simulation transcripts are stored in
`simWorkspace/<design>/<test>/sim_transcript_<setup seed>_<sim seed>.log.gz`;
they are compressed to save disk space.  To browse the transcript:

```console
$ vim <transcript>          # to view in an editor, after the simulation has finished (vim supports gz files)
$ gztool -T <transcript>    # to follow the transcript (in the same way as `tail -f`)
```

**Note**: if mill complains about not incorrect `VERILATOR_ROOT` setup, even
after correcting the problem, try killing the mill server:

```console
$ ps aux | grep '[M]illServerMain' | awk '{print $2}' | xargs kill
$ mill --no-server <...>
```

This is due to the old mill server still using the old environment
(specifically with the old `VERILATOR_ROOT` variable).  Running mill with
`--no-server` forces mill to run as a standalone process (thus forcing the new
environment every time).

You can reproduce a specific simulation (for debugging) by supplying the exact
setup and sim seeds to `NicSim` with the command at the start of the
transcript:

```console
$ zcat /local/home/pengxu/work-local/enzian-pio-nic/simWorkspace/PacketAlloc/simple-allocate-free/sim_transcript.log.gz
>>>>> Simulation transcript pionic.PacketAllocSim for test simple-allocate-free
>>>>> To reproduce: mill gen.test.testOnly pionic.PacketAllocSim -- -t simple-allocate-free -DsetupSeed=1024949829 -DsimSeed=-1610542669 -DprintSimLog=true

[0] [Progress] Start PacketAlloc simple-allocate-free simulation with seed 565014101
...
```

Create Vivado project and generate the bitstream for ECI and PCIe:

```console
$ mill --no-server pcie.generateBitstream
...

$ file out/pcie/vivadoProject.dest/pio-nic-pcie/pio-nic-pcie.runs/impl_1/pio-nic-pcie.{bit,ltx}
[...]/pio-nic-pcie.bit: Xilinx BIT data - from design_1_wrapper;COMPRESS=TRUE;UserID=0XFFFFFFFF;Version=2022.1 - for xcvu9p-flgb2104-3-e - built 2023/12/23(11:09:14) - data length 0x1b2cf00
[...]/pio-nic-pcie.ltx: ASCII text
$ mill --no-server eci.generateBitstream
```

Same as with `VERILATOR_ROOT`, if mill complains about not able to find
`vivado`, despite already sourcing the environment file, try killing the mill
server and running with `--no-server`.  This also helps if Vivado's output is
not printed to the standard output (but instead to the server process).

Build userspace software (drivers):

```console
$ mill pcie.generateVerilog # generate header files
$ cd sw/pcie && make
...
$ file pionic-test
pionic-test: ELF 64-bit LSB executable, ARM aarch64, version 1 (GNU/Linux), statically linked, BuildID[sha1]=898cb76f926551cdf354110ac0a269dbe271c93e, for GNU/Linux 3.7.0, not stripped
$ mill eci.generateVerilog # generate header files
$ cd sw/eci && make
```

The PCIe test should be ran on an Enzian with a PCIe cable between the CPU and
FPGA (`zuestoll11-12` at the moment).  The ECI test should work on any Enzian.

## (Re)-running the experiments

Configure the correct iSCSI boot image and grub config with `emg(2)`.  Remember
to `sudo poweroff` (i.e. shut down the OS) before releasing the iSCSI target.

```bash
# release the image, cleaning out the old configuration
emg release zuestoll11
# run vanilla kernel with no isolation
emg acquire zuestoll11 -n pio-nic
# run vanilla kernel with isolcpus
emg acquire zuestoll11 -n pio-nic -a 'isolcpus=nohz,domain,managed_irq,47 nohz_full=47 rcu_nocbs=47 irqaffinity=0-46 kthread_cpus=0-46 rcu_nocb_poll'
# run custom, nohz_full kernel with isolcpus
emg acquire zuestoll11 -n pio-nic -a 'isolcpus=nohz,domain,managed_irq,47 nohz_full=47 rcu_nocbs=47 irqaffinity=0-46 kthread_cpus=0-46 rcu_nocb_poll' -k 'pengxu/vmlinuz-5.4.0-196-generic' -i 'pengxu/initrd.img-5.4.0-196-generic'
```

Boot up an Enzian with the desired bitstream:
- Power up both the CPU and FPGA
- Interrupt the CPU boot process during BDK
- Program FPGA bitstream
- Continue CPU boot process and verify that the link between CPU and FPGA is correctly configured
  - PCIe: `N0.PCIe2: Link active, 8 lanes, speed gen3`
  - ECI: `N0.CCPI Lanes([] is good):[0][1][2][3][4][5][6][7][8][9][10][11][12][13][14][15][16][17][18][19][20][21][22][23]`

Run the experiment, pinning the application to a specific core (should match
the core specified in `isolcpus`/`nohz_full` options above):

```bash
# for ECI
sudo taskset -c 47 ./pionic-test
# for PCIe
sudo taskset -c 47 ./pionic-test 0004:90:00.0
```

Retrieve the `pcie_lat.csv`/`eci_lat.csv` and `loopback.csv` and run `data/*/plot.py` to generate plots.

## Devs Area

Create project for IntelliJ IDEA:

```console
$ mill mill.idea.GenIdea/idea
```

Interact with the Vivado project (e.g. change the block design, read timing
reports, etc.):

```console
$ mill --no-server pcie.vivadoProject
...
$ vivado out/pcie/vivadoProject.dest/pio-nic-pcie/pio-nic-pcie.xpr
$ vivado out/eci/vivadoProject.dest/pio-nic-eci/pio-nic-eci.xpr # synth-only project!
```

For the PCIe project, remember to export the project Tcl again to keep the
build process reproducible.  In Vivado's Tcl console:

```tcl
write_project_tcl -no_ip_version -paths_relative_to ./vivado/pcie -force vivado/pcie/create_project.tcl
```

For the ECI project, remember to export the block design Tcl again.  In
Vivado's Tcl console:

```tcl
write_bd_tcl -force vivado/eci/bd/design_1.tcl
```

Update `vivado/eci/create_project.tcl` accordingly (the script is
hand-written).
