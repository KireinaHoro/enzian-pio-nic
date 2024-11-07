## Bypass loopback benchmark (PCIe)

Tests the combined send/receive latency for the NIC in loopback mode.  A
randomly generated Ethernet/IP/UDP packet is sent with the bypass transaction
type, looped back at the CMAC with PCS/PMA loopback, and then received through
the bypass transaction again.

The actual MAC addresses / IP addresses sent does not matter, since we enable
promiscuous mode so that no packets will be dropped on the RX side.  However,
we need to set the appropriate _next protocol_ bits in the headers, to measure
different bits of the decoder pipeline.

All timestamps offered by the profile driver is stored in the CSV file.  As a
result, this application is implementation-agnostic.
