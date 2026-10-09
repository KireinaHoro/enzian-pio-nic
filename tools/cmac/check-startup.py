#!/usr/bin/env python3
"""Run production startup with generated Mackerel accessors and fake MMIO."""
import pathlib
import subprocess
import sys
import tempfile
root = pathlib.Path(__file__).resolve().parents[2]
headers = pathlib.Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory() as td:
    td = pathlib.Path(td)
    (td/'mackerel_support').mkdir()
    (td/'mackerel_support/mackerel.h').write_text('''#pragma once
#include <stdint.h>
typedef uint64_t mackerel_addr_t;
static uint32_t regs[0x1100/4];
static int mode, polls, sleeps, resets;
static uint32_t mackerel_read_addr_32(uint64_t b, int o) {
 (void)b;
 if(o==0x204) { polls++; return mode==2?0:mode==3?2:polls<3?0:3; }
 if(o==0x107c && mode==1) return 0;
 return regs[o/4];
}
static void mackerel_write_addr_32(uint64_t b,int o,uint32_t v) { (void)b; if(o==0 && (v&1)) resets++; regs[o/4]=v; }
static uint64_t mackerel_read_addr_64(uint64_t b,int o) { return mackerel_read_addr_32(b,o); }
static void mackerel_write_addr_64(uint64_t b,int o,uint64_t v) { mackerel_write_addr_32(b,o,v); }
static uint16_t mackerel_read_addr_16(uint64_t b,int o) { return mackerel_read_addr_32(b,o); }
static void mackerel_write_addr_16(uint64_t b,int o,uint16_t v) { mackerel_write_addr_32(b,o,v); }
''')
    source = (root/'sw/kmod/cmac.c').read_text().replace('#include "common.h"', '''#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <assert.h>
#define pr_info(...) ((void)printf(__VA_ARGS__))
#define pr_err(...) ((void)printf(__VA_ARGS__))
#include "cmac_dev.h"
static void msleep(unsigned n) { (void)n; sleeps++; }
static void udelay(unsigned n) { (void)n; }
void stop_cmac(cmac_t *cmac);
''')
    # Compile the actual netdev callbacks against state-checking kernel shims.
    bypass = (root/'sw/kmod/bypass.c').read_text()
    callbacks = []
    for name in ('netdev_open', 'netdev_stop'):
        start = bypass.index('static int ' + name + '(')
        end = bypass.index('\n}\n', start) + 3
        callbacks.append(bypass[start:end])
    source += '''
struct netdev_priv { cmac_t cmac_dev; int napi, macIf_dev, reg_dev, poll_timer; bool poll_rearm; };
struct net_device { struct netdev_priv priv; int carrier, queue; };
static struct netdev_priv *netdev_priv(struct net_device *d) { return &d->priv; }
static bool force_poll, do_loopback;
static unsigned force_poll_us=100, jiffies;
#define WRITE_ONCE(x,v) ((x)=(v))
#define dev_dbg(...) ((void)0)
#define netdev_err(...) ((void)0)
static void smp_wmb(void) {}
static unsigned usecs_to_jiffies(unsigned n) { return n; }
static void forced_napi_poll_cb(void) {}
static void timer_setup(int *t, void (*f)(void), int flags) { (void)f; (void)flags; assert(!*t); }
static void mod_timer(int *t, unsigned n) { (void)n; *t=1; }
static void timer_delete_sync(int *t) { *t=0; }
static void napi_enable(int *n) { assert(!*n); *n=1; }
static void napi_disable(int *n) { assert(*n); *n=0; }
static void netif_carrier_off(struct net_device *d) { d->carrier=0; }
static void netif_carrier_on(struct net_device *d) { d->carrier=1; }
static void netif_start_queue(struct net_device *d) { assert(d->carrier && d->priv.napi); d->queue=1; }
static void netif_stop_queue(struct net_device *d) { d->queue=0; }
static void lauberhorn_eci_macIf_ctrl_rx_drop_all_wr(int *r, int v) { *r=v; }
static void lauberhorn_eci_bypassIrq_irq_en_wr(int *r, int v) { *r=v; }
'''
    source += '\n'.join(callbacks)
    source += '''
static void check_down(struct net_device *d) {
 assert(!d->carrier && !d->queue && !d->priv.napi);
 assert(!d->priv.reg_dev && !d->priv.poll_timer && !d->priv.poll_rearm);
 assert(d->priv.macIf_dev==1);
 assert(!(regs[0xc/4]&1) && !(regs[0x14/4]&1));
}
int main(void) {
 cmac_t cmac; cmac_initialize(&cmac,0);
 for(mode=0; mode<5; mode++) {
  memset(regs,0,sizeof(regs)); polls=0; sleeps=0;
  regs[0x24/4] = mode==4?0:0x301;
  int rc=start_cmac(&cmac,false);
  if(mode==0) {
   assert(rc==0); assert((regs[0x107c/4]&3)==3);
   assert(regs[0xc/4]==1); assert((regs[0x14/4]&1)==1);
   assert(regs[4/4]==0);
  } else {
   assert(rc==(mode==1?-EIO:mode==4?-ENODEV:-ETIMEDOUT));
   assert(!(regs[0xc/4]&1)); assert(!(regs[0x14/4]&1));
  }
  assert(sleeps<=202);
 }
 for(int polling=0; polling<2; polling++) {
  force_poll=polling;
  for(int failure=1; failure<=4; failure++) {
   struct net_device dev={0};
   cmac_initialize(&dev.priv.cmac_dev,0);
   dev.priv.macIf_dev=1;
   memset(regs,0,sizeof(regs)); polls=0; sleeps=0;
   regs[0x24/4]=failure==4?0:0x301; mode=failure;
   assert(netdev_open(&dev)==(failure==1?-EIO:failure==4?-ENODEV:-ETIMEDOUT));
   check_down(&dev);
   /* Retry UP on the same object/register state; no module reload or clear. */
   mode=0; regs[0x24/4]=0x301; int before=resets;
   assert(netdev_open(&dev)==0); assert(resets==before+1);
   assert(dev.carrier && dev.queue && dev.priv.napi);
   assert(!dev.priv.macIf_dev);
   assert(polling ? dev.priv.poll_timer && dev.priv.poll_rearm : dev.priv.reg_dev);
   assert(netdev_stop(&dev)==0); check_down(&dev);
   before=resets;
   assert(netdev_open(&dev)==0); assert(resets==before+1);
   assert(netdev_stop(&dev)==0); check_down(&dev);
  }
 }
 puts("PASS startup and netdev UP failure/retry/DOWN/UP in IRQ and polling modes");
}
'''
    (td/'test.c').write_text(source)
    subprocess.run(['cc','-std=gnu11','-Wall','-Werror','-I'+str(td),'-I'+str(headers),str(td/'test.c'),'-o',str(td/'test')],check=True)
    subprocess.run([str(td/'test')],check=True)
