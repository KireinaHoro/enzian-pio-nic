#!/usr/bin/env python3
"""Canonical Lauberhorn trace session: CMAC dump, persistence and JTAG integrity."""
import argparse
import json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from tools.enzian.trace.control import Control, campaign_config
from tools.enzian.trace.session import capture, recover_export


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=('capture','recover-export','status','freeze','preserve','campaign-config'))
    p.add_argument('--config',type=Path,required=True)
    p.add_argument('--output',type=Path)
    a=p.parse_args();config=json.loads(a.config.read_text())
    if a.command=='campaign-config':print(json.dumps(campaign_config(config),indent=2));return
    if a.output is None:p.error('--output required')
    if a.command=='capture':result=capture(config,a.output)
    elif a.command=='recover-export':result=recover_export(config,a.output)
    else:result=Control(config,a.output).call(a.command)
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
