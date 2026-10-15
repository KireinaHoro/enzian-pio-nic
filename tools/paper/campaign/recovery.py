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
    if any(Path(p).suffix not in ('.py','.sh') for p in changed):
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
