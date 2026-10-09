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
static int mode, polls, sleeps;
static uint32_t mackerel_read_addr_32(uint64_t b, int o) {
 (void)b;
 if(o==0x204) { polls++; return mode==2?0:mode==3?2:polls<3?0:3; }
 if(o==0x107c && mode==1) return 0;
 return regs[o/4];
}
static void mackerel_write_addr_32(uint64_t b,int o,uint32_t v) { (void)b; regs[o/4]=v; }
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
    source += '''
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
 puts("PASS startup, inaccessible FEC, alignment/status timeouts, invalid version");
}
'''
    (td/'test.c').write_text(source)
    subprocess.run(['cc','-std=gnu11','-Wall','-Werror','-I'+str(td),'-I'+str(headers),str(td/'test.c'),'-o',str(td/'test')],check=True)
    subprocess.run([str(td/'test')],check=True)
