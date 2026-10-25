/* SPDX-License-Identifier: BSD-3-Clause OR GPL-2.0-only */
#ifndef LH_KMOD_VM_H
#define LH_KMOD_VM_H
#include "vm/abi.h"
struct socket;
int lh_vm_init(void);
void lh_vm_exit(void);
void lh_vm_notify(void);
int lh_vm_service(struct srv_def *srv, bool enable);
void lh_vm_endpoint(struct proc_def *proc, u32 handle, u16 port,
                    struct socket *socket, bool native, bool enable);
bool lh_vm_endpoint_busy(u16 port);
long lh_vm_retire(struct proc_def *proc, u32 cookie);
long lh_vm_control(struct proc_def *proc, unsigned int cmd, unsigned long arg);
long lh_vm_ioctl(struct proc_def *proc, unsigned int cmd, unsigned long arg);
#endif
