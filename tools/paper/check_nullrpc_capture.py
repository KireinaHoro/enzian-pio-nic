#!/usr/bin/env python3
"""Count RPC admissions/replies and stored loss markers in a complete capture.

This validates acquisition counts, not sequence correlation or cross-clock timing.
Use the matched generated map. Raw input is the complete unwrapped DDR buffer.
"""
import argparse,collections,hashlib,json
from pathlib import Path
import numpy as np

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('trace',type=Path);p.add_argument('--map',type=Path,required=True)
p.add_argument('--expected-rpcs',type=int,required=True);p.add_argument('--output',type=Path,required=True)
a=p.parse_args();m=json.loads(a.map.read_text());fmt=m['sample']
if fmt['sample_width']!=128 or fmt['byte_order']!='little' or fmt['payload_width']<64:
 raise ValueError('Unsupported sample format')
values=np.memmap(a.trace,dtype='<u8',mode='r').reshape(-1,2)
shift=fmt['payload_width']-64;source_mask=(1<<fmt['source_width'])-1
sources=[s['source'] for s in m['sources'] if s['type']=='lauberhorn_event']
event_format=m['payload_formats']['lauberhorn_event'];id_field=event_format['fields']['event_id']
if id_field['offset']+id_field['width']>64:raise ValueError('Event ID crosses word boundary')
counts=np.zeros(1<<id_field['width'],dtype=np.int64);lost=0;reported_lost=0
for start in range(0,len(values),1<<20):
 v=values[start:start+(1<<20)];src=(v[:,1]>>shift)&source_mask
 lost_mask=(src==fmt['lost_source'])&((v[:,0]&0xffffffff)!=0)
 lost+=int(np.count_nonzero(lost_mask));reported_lost+=int((v[lost_mask,0]&0xffffffff).sum())
 keep=np.isin(src,sources)
 ids=((v[keep,0]>>id_field['offset'])&((1<<id_field['width'])-1)).astype(np.int64)
 counts+=np.bincount(ids,minlength=len(counts))
names={name:int(counts[int(i)]) for i,name in event_format['events'].items() if counts[int(i)]}
errors=[]
if lost:errors.append('Stored loss markers present')
for name in ('OncRpcCallDecoder','OncRpcReplyEncode'):
 if names.get(name,0)!=a.expected_rpcs:errors.append(f'{name}: expected {a.expected_rpcs}, got {names.get(name)}')
h=hashlib.sha256()
with a.trace.open('rb') as f:
 for block in iter(lambda:f.read(1<<20),b''):h.update(block)
result=dict(passed=not errors,errors=errors,bytes=a.trace.stat().st_size,sha256=h.hexdigest(),
 map_sha256=hashlib.sha256(a.map.read_bytes()).hexdigest(),expected_rpcs=a.expected_rpcs,
 lost_markers=lost,reported_lost_samples=reported_lost,event_counts=names,
 scope='Whole-capture event counts and loss-marker scan; not sequence or timing proof.')
a.output.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:result[k] for k in ('passed','errors','expected_rpcs','lost_markers')}))
raise SystemExit(bool(errors))
