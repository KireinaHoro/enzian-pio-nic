"""One freeze/dump/validate/persist/restore lifecycle for manual and campaign use."""
import fcntl
import json
from pathlib import Path
import subprocess
import shlex

from .control import Control, atomic, digest
from .transport import Transport


def capture(config,directory):
    directory=Path(directory).resolve()
    directory.mkdir(parents=True,exist_ok=False)
    return _capture(config,directory)


def recover_export(config,directory):
    directory=Path(directory).resolve()
    state=json.loads((directory/'capture.json').read_text())
    if state.get('receiver_complete') is not True or state.get('passed') is True or state.get('failed_stage')!='exporting':
        raise ValueError('Export recovery needs a completed receiver and no local extraction; retain earlier attempts')
    return _capture(config,directory,recover=True)


def _capture(config,directory,recover=False):
    control=Control(config,directory)
    with control.exclusive(), (directory/'lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        state=json.loads((directory/'capture.json').read_text()) if recover else dict(stage='freezing',passed=False,receiver_complete=False)
        atomic(directory/'capture.json',state)
        state['remote_evidence']=dict(programmer=config['programmer'],control=control.remote,peer=config.get('peer'),receiver=Transport(control).remote)
        failure=None;restored=False
        try:
            if recover:
                current=control.call('status')
                if current.get('trace_stop')!=1 or current['_status']!=state['end']:raise ValueError('Frozen recorder changed')
            else:
                values=control.call('configure',directory/'freeze.log');end=values['_status']
                import re
                before={k:int(v,16) for k,v in re.findall(r'^BEFORE_FREEZE (\w+) ([0-9a-fA-F]+)$',(directory/'freeze.log').read_text(),re.M)}
                if end['trace_wrapped_1']:raise ValueError('Wrapped capture needs explicit ring-aware policy; no prefix capture')
                if end['trace_dma_error_1']:raise ValueError('Trace DMA error')
                size=end['trace_write_slot_1']*64
                if size<4096:raise ValueError('Capture too small for independent head/tail validation')
                state.update(stage='receiving',bytes=size,start=before,end=end,offset=0,bounded_window=True)
                atomic(directory/'capture.json',state)
                Transport(control).receive(size)
                state.update(stage='exporting',receiver_complete=True)
                atomic(directory/'capture.json',state)
            raw=Transport(control).export(state['bytes'])
            state.update(stage='validating',sha256=digest(raw));atomic(directory/'capture.json',state)
            state['integrity']=control.check(raw)
            current=control.call('status')
            if current.get('trace_stop')!=1 or current['_status']!=state['end']:raise ValueError('Recorder changed during capture')
            state.update(stage='validated',pipeline=config.get('pipeline'),raw=str(raw))
        except BaseException as error:
            failure=error;state.update(failed_stage=state['stage'],stage='failed',error=str(error))
        finally:
            # Snapshot belongs to this session, never a caller-generated label.
            # If freeze failed after saving remotely, still attempt restoration.
            try:
                exists=subprocess.run(['ssh',config['programmer'],shlex.join(['test','-f',control.remote+'/snapshot.txt'])],timeout=30,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                if exists.returncode==0:
                    state['restored_recorder']=control.call('preserve',directory/'restore.log');restored=True
                else:state['restore_error']='No saved snapshot; no restore attempted'
            except BaseException as error:
                state['restore_error']=str(error)
                if failure is None:failure=error
            state['restored']=restored
            state['passed']=failure is None and restored
            if state['passed']:state['stage']='complete'
            atomic(directory/'capture.json',state)
            atomic(directory/'manifest.json',dict(config=config,files={str(p.relative_to(directory)):digest(p) for p in directory.rglob('*') if p.is_file() and p.name not in ('manifest.json','lock')}))
        if failure:raise failure
        if not restored:raise RuntimeError('Capture saved but controls not restored')
        atomic(directory/'summary.json',state)
        manifest=json.loads((directory/'manifest.json').read_text())
        manifest['files']['summary.json']=digest(directory/'summary.json')
        atomic(directory/'manifest.json',manifest)
        return state
