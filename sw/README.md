# PIO NIC Software

**Two parts:**
* usr: APIs facing user applications (RPC programs)
* rt: runtime (PCIe, ECI, Mock)

**Build configurations:**
* RT_PCIE: PCIe rt as a library; operates on `/sys/bus/pci/devices/<device>`.
* RT_ECI: ECI rt as a library; operates on `/dev/fpgamem` (requires the Enzian
  FPGA memory kernel module)

rt assumes the ownership of the whole device. It does not multiplex processes or
threads. These two configurations are good for unit testing in one process
one thread.

The app should uses headers in `rt-include`. The app may require `sudo` to
access the device.

* FULL_ECI: compiles a kernel module and a user library; the kernel module
  creates `/dev/pionic` and uses the ECI rt as the backend; the user library
  operates on the device.
* FULL_MOCK: the kernel module acts as a mock device for the hardware; the user
  library additionally provides APIs for mock tests.

The kernel module and the user library together multiplex processes and threads.

The app should use `usr-include/lauberhorn.h` and additionally
`usr-include/mock.h` if the kernel module uses the Mock backend (FULL_MOCK).

To differenciate rt and usr, rt APIs start with `pionic_` while usr APIs start
with `lauberhorn_`.

No FULL_PCIe. Hardware scheduling is implemented only on the ECI NIC.