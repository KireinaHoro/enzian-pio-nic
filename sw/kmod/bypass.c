// SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only
// Copyright (c) 2025 Pengcheng Xu

// Implement the bypass core functions in kernel.  Main functionalities:
// - fetch TX packets from network device and send through bypass core
// - hook into ARP stack:
//   - sync host ARP table entries to HW
//   - expire ARP table entries and delete them
// - run poll loop for bypass core to fetch (as part of NAPI)
//   - bypass packets: feed into a network device
//   - ARP resolve request from TX pipeline: send out ARP request
//
// The bypass core polling loop does not delay responses.  The FPI number
// 15 to core 0 will be used to notify the CPU that the bypass queue is
// now non-empty, and is used to drive NAPI scheduling.
// 

