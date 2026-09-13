#!/usr/bin/env python3
"""One-shot read-only CI snapshot and hardware artifact collection; never polls."""
import argparse
import datetime
import json
import pathlib
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

BASE = 'https://gitlab.inf.ethz.ch/api/v4/projects/30605'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('manifest', type=pathlib.Path, help='JSON list of name, branch, sha')
    p.add_argument('--token-file', required=True, type=pathlib.Path)
    p.add_argument('--output', required=True, type=pathlib.Path)
    p.add_argument('--snapshot-only', action='store_true', help='record pipelines/jobs without downloading')
    args = p.parse_args()
    cases = json.loads(args.manifest.read_text())
    token = args.token_file.read_text().strip()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    opener = urllib.request.build_opener(NoRedirect)

    def get(suffix):
        return opener.open(urllib.request.Request(BASE + suffix,
                           headers={'PRIVATE-TOKEN': token}, method='GET'), timeout=180)

    def get_json(suffix):
        with get(suffix) as response:
            return json.load(response)

    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    for case in cases:
        record = dict(case)
        records.append(record)
        try:
            query = urllib.parse.urlencode(dict(sha=case['sha'], ref=case['branch'], per_page=1))
            pipelines = get_json('/pipelines?' + query)
            if not pipelines:
                record['status'] = 'pipeline_not_found'
                continue
            pipeline = pipelines[0]
            record['pipeline'] = pipeline
            record['pipeline_status'] = pipeline['status']
            jobs = get_json(f"/pipelines/{pipeline['id']}/jobs?per_page=100")
            record['jobs'] = [{k: job.get(k) for k in ('id', 'name', 'status', 'web_url', 'artifacts_file')}
                              for job in jobs]
            record['failed_jobs'] = [j['id'] for j in jobs if j['status'] == 'failed']
            if not args.snapshot_only:
                for job in jobs:
                    if job['status'] != 'failed':
                        continue
                    trace = args.output / f"{job['id']}-failed.log"
                    if not trace.exists():
                        with get(f"/jobs/{job['id']}/trace") as response, trace.open('wb') as out:
                            shutil.copyfileobj(response, out)
            hardware = next((j for j in jobs if j['name'] == 'build-hw-eci'), None)
            record['status'] = hardware['status'] if hardware else 'hardware_job_not_found'
            if args.snapshot_only or not hardware or hardware['status'] not in ('success', 'failed'):
                continue
            if not hardware.get('artifacts_file'):
                record['artifacts'] = 'not_available'
                continue
            dest = args.output / str(hardware['id'])
            dest.mkdir(exist_ok=True)
            archive = dest / 'artifacts.zip'
            if not archive.exists():
                partial = dest / 'artifacts.zip.part'
                with get(f"/jobs/{hardware['id']}/artifacts") as response, partial.open('wb') as out:
                    shutil.copyfileobj(response, out)
                partial.rename(archive)
            # Only extract report files beneath the exact known report directory.
            # Keep the full HW archive for later checkpoint/bitstream deployment.
            with zipfile.ZipFile(archive) as zipped:
                for entry in zipped.infolist():
                    path = pathlib.PurePosixPath(entry.filename)
                    if (len(path.parts) == 3 and path.parts[:2] == ('out', 'physical')
                            and path.name not in ('.', '..') and not entry.is_dir()):
                        report = dest / 'reports' / path.name
                        report.parent.mkdir(exist_ok=True)
                        with zipped.open(entry) as source, report.open('wb') as out:
                            shutil.copyfileobj(source, out)
            record['archive'] = str(archive.resolve())
            reports = dest / 'reports'
            if (reports / 'timing.rpt').exists() and (reports / 'metadata.txt').exists():
                # The split pipeline runs Python in its Nix reporting job.
                # Recreate the small digest locally from the HW archive too.
                digest = subprocess.run(
                    [sys.executable, str(pathlib.Path(__file__).with_name('summarize.py')),
                     str(reports), '--limit', '3'], capture_output=True, text=True)
                (dest / 'digest.log').write_text(digest.stdout + digest.stderr)
                record['reports_complete'] = (reports / 'COMPLETE').exists()
                if digest.returncode:
                    record['digest_error'] = str(dest / 'digest.log')
        except (urllib.error.URLError, OSError, zipfile.BadZipFile) as exc:
            record['error'] = str(exc)
    snapshot = dict(checked_at=datetime.datetime.now(datetime.timezone.utc).isoformat(), cases=records)
    filename = datetime.datetime.now(datetime.timezone.utc).strftime('snapshot-%Y%m%dT%H%M%S.json')
    text = json.dumps(snapshot, indent=2) + '\n'
    (args.output / filename).write_text(text)
    (args.output / 'latest.json').write_text(text)
    for record in records:
        print(json.dumps({k: record[k] for k in ('name', 'sha', 'pipeline_status', 'status', 'failed_jobs', 'archive', 'error') if k in record}))


if __name__ == '__main__':
    main()
