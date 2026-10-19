"""Explicit, hash-bound compatibility review for stopped campaign resumes."""
import hashlib
import json
from pathlib import Path

from .import_loaded import digest


def identity(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def invalidated(root, receipt):
    if not receipt.exists():
        return False
    fingerprint=digest(receipt)
    return any(fingerprint in json.loads(p.read_text())['invalidated_receipts']
               for p in (root/'recoveries').glob('*/review.json'))


def prepare(root, code, review, atomic):
    """Caller holds campaign and global locks. No acquisition/config changes."""
    old=json.loads((root/'code.json').read_text())
    if old==code:
        if review is not None: raise ValueError('Recovery review requires a code change')
        return
    if review is None: raise ValueError('Controller changed; supply --recovery-review')
    if review.get('old_code')!=old or review.get('new_code')!=code:
        raise ValueError('Recovery review does not match exact old/new code hashes')
    if not review.get('reason') or not review.get('evidence'):
        raise ValueError('Recovery requires a reason and hashed validation evidence')
    for path,sha in review['evidence'].items():
        if not Path(path).is_absolute() or digest(path)!=sha:
            raise ValueError('Recovery validation evidence changed')
    # JSON hardware manifests and other non-code artifacts are immutable. A
    # source fix is reviewable; changed builds/placement/workload need a new root.
    changed={p for p in old.keys()|code.keys() if old.get(p)!=code.get(p)}
    if any(Path(p).suffix not in ('.py','.sh','.tcl') for p in changed):
        raise ValueError('Recovery cannot change acquisition manifests/artifacts')
    receipts={str(p.relative_to(root)):p for p in (root/'stages').glob('*/receipt.json')}
    decisions=review.get('receipts',{})
    if set(decisions)!=set(receipts):
        raise ValueError('Review must classify every completed stage receipt')
    invalid=[]
    for name,path in receipts.items():
        decision=decisions[name];saved=json.loads(path.read_text())
        if decision.get('sha256')!=digest(path) or not decision.get('reason'):
            raise ValueError('Receipt review stale or missing reason: '+name)
        if decision.get('action') not in ('reuse','rerun'):
            raise ValueError('Receipt action must be reuse or rerun')
        if any(digest(p)!=sha for p,sha in saved['hashes'].items()):
            raise ValueError('Completed stage evidence changed: '+name)
        requests=[Path(p) for p in saved['hashes'] if Path(p).name=='request.json' and Path(p).parent.name.startswith('attempt-')]
        if len(requests)!=1: raise ValueError('Ambiguous stage request: '+name)
        stage=json.loads(requests[0].read_text())['stage']
        # Derived outputs must be regenerated after any revision transition;
        # acquisition reuse is an explicit per-cohort compatibility judgment.
        if decision['action']=='rerun' or stage not in ('loaded','unloaded'):
            invalid.append(digest(path))
    record=dict(review,invalidated_receipts=invalid)
    directory=root/'recoveries'/identity(record)
    directory.mkdir(parents=True,exist_ok=True)
    atomic(directory/'review.json',record)
    # Keep exact prior receipt contents, even when a subsequent attempt replaces
    # the active pointer. The attempt directories and raw evidence never move.
    atomic(directory/'receipts.json',{k:json.loads(p.read_text()) for k,p in receipts.items()})
    atomic(root/'code.json',code)


def draft(root, code):
    """Fail-closed review template; an operator must justify each reuse."""
    return dict(old_code=json.loads((root/'code.json').read_text()),new_code=code,
                reason='',evidence={},receipts={
                    str(p.relative_to(root)):dict(sha256=digest(p),action='rerun',reason='')
                    for p in sorted((root/'stages').glob('*/receipt.json'))})


def inherit_unloaded(source, destination, reason, atomic):
    """Carry reviewed rehearsal unloaded receipts into a new placement cohort."""
    import fcntl
    with (Path(source)/'lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        status=json.loads((Path(source)/'status.json').read_text())['stage']
        if status not in ('failed','rehearsal-complete'):
            raise ValueError('Source rehearsal must be stopped')
        return _inherit_unloaded(source,destination,reason,atomic)


def _inherit_unloaded(source, destination, reason, atomic):
    source=Path(source).resolve();destination=Path(destination).resolve()
    if not reason or source==destination:raise ValueError('Distinct source and review reason required')
    if json.loads((source/'config.json').read_text()).get('scope')!='rehearsal':
        raise ValueError('Only rehearsal inheritance is supported')
    selected=[]
    for receipt in sorted((source/'stages').glob('*/receipt.json')):
        if invalidated(source,receipt):continue
        saved=json.loads(receipt.read_text())
        cohort=saved['result'].get('cohort')
        if not cohort:continue
        if cohort['count']!=100 or cohort['warmup']!=16:raise ValueError('Wrong rehearsal sample count')
        for path,sha in saved['hashes'].items():
            if digest(path)!=sha:raise ValueError('Inherited evidence changed')
        selected.append((receipt,saved))
    from .import_unloaded import validate_matched
    validate_matched([saved['result']['cohort'] for _,saved in selected])
    if len(selected)!=12:raise ValueError('Expected all 12 validated unloaded cohorts')
    if any((destination/'stages'/p.parent.name/'receipt.json').exists() for p,_ in selected):
        raise ValueError('Cannot overwrite inherited receipts')
    record=dict(source=str(source),reason=reason,
        source_config=json.loads((source/'config.json').read_text()),
        source_code=json.loads((source/'code.json').read_text()),
        receipts={str(p):digest(p) for p,_ in selected})
    atomic(destination/'inherited-unloaded.json',record)
    for receipt,saved in selected:
        target=destination/'stages'/receipt.parent.name;target.mkdir(parents=True,exist_ok=True)
        atomic(target/'receipt.json',saved)


def inheritance_draft(source, config, code):
    source=Path(source).resolve()
    return dict(source=str(source),old_config=json.loads((source/'config.json').read_text()),
        old_code=json.loads((source/'code.json').read_text()),new_config=config,new_code=code,
        reason='',evidence={},receipts={
            str(p.relative_to(source)):dict(sha256=digest(p),action='rerun',reason='')
            for p in sorted((source/'stages').glob('*/receipt.json'))})


def inherit_reviewed(source, destination, config, code, review, atomic):
    """Explicit model override for intact acquisitions across artifact changes."""
    import fcntl
    source=Path(source).resolve();destination=Path(destination).resolve()
    if source==destination:raise ValueError('Inheritance requires distinct roots')
    with (source/'lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        status=json.loads((source/'status.json').read_text())['stage']
        if status not in ('failed','rehearsal-complete','complete'):
            raise ValueError('Source campaign must be stopped')
        expected=inheritance_draft(source,config,code)
        for key in ('source','old_config','old_code','new_config','new_code'):
            if review.get(key)!=expected[key]:raise ValueError('Stale inheritance review: '+key)
        if expected['old_config']['scope']!=config['scope']:
            if not (config['scope']=='full' and (
                    (expected['old_config']['scope'] in ('rehearsal','unloaded-rehearsal') and status=='rehearsal-complete')
                    or (expected['old_config']['scope']=='unloaded-full' and status=='complete'))):
                raise ValueError('Scope promotion requires a completed rehearsal or full unloaded collection')
            # Acquisition requests retain their rehearsal phase and sample counts.
            # The full controller reruns validation before fresh full acquisitions.
        if not review.get('reason') or not review.get('evidence'):
            raise ValueError('Inheritance requires justification and validation evidence')
        for path,sha in review['evidence'].items():
            if not Path(path).is_absolute() or digest(path)!=sha:
                raise ValueError('Inheritance validation evidence changed')
        if set(review.get('receipts',{}))!=set(expected['receipts']):
            raise ValueError('Review must classify every source receipt')
        selected=[]
        for name,decision in review['receipts'].items():
            path=source/name;saved=json.loads(path.read_text())
            if decision.get('sha256')!=digest(path) or not decision.get('reason'):
                raise ValueError('Stale receipt or missing reason: '+name)
            if decision.get('action') not in ('reuse','rerun'):
                raise ValueError('Invalid inheritance action')
            if any(digest(p)!=sha for p,sha in saved['hashes'].items()):
                raise ValueError('Source evidence changed: '+name)
            requests=[Path(p) for p in saved['hashes'] if Path(p).name=='request.json' and Path(p).parent.name.startswith('attempt-')]
            if len(requests)!=1:raise ValueError('Ambiguous source request')
            stage=json.loads(requests[0].read_text())['stage']
            if decision['action']=='reuse':
                if invalidated(source,path):raise ValueError('Cannot reuse invalidated receipt')
                if stage not in ('loaded','unloaded') or saved['result'].get('passed') is not True:
                    raise ValueError('Only passing acquisition receipts may be inherited')
                target=destination/name
                if target.exists():raise ValueError('Cannot overwrite inherited receipt')
                selected.append((target,saved))
        state_path=source/'hardware-state.json'
        state=None
        if review.get('hardware_state_sha256'):
            if digest(state_path)!=review['hardware_state_sha256']:
                raise ValueError('Reviewed hardware state changed')
            state=json.loads(state_path.read_text())
            if state.get('safe') is not True:raise ValueError('Inherited hardware needs recovery')
        # Validate everything before writing. Original observations/code stay intact.
        atomic(destination/'inheritance-review.json',review)
        if state is not None:atomic(destination/'hardware-state.json',state)
        for target,saved in selected:
            target.parent.mkdir(parents=True,exist_ok=True);atomic(target,saved)
