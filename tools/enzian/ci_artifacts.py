#!/usr/bin/env python3
"""Read Lauberhorn CI status and download hardware artifacts (GET requests only)."""
import argparse
import hashlib
import json
import pathlib
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = 'https://gitlab.inf.ethz.ch/api/v4/projects/'
PROJECT = urllib.parse.quote('project-openenzian/applications/lauberhorn/platform', safe='')
ARTIFACTS = {
    'shell_lauberhorn-eci.bit': 'out/eci/vivadoProject.dest/shell_lauberhorn-eci.bit',
    'shell_lauberhorn-eci.ltx': 'out/eci/vivadoProject.dest/shell_lauberhorn-eci.ltx',
    'lauberhorn_trace_dma_map.json': 'out/eci/generateVerilog.dest/lauberhorn_trace_dma_map.json',
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('job', type=int)
    p.add_argument('--token-file', type=pathlib.Path, required=True)
    p.add_argument('--download', type=pathlib.Path)
    p.add_argument('--trace', type=pathlib.Path)
    p.add_argument('--checkpoint', action='store_true',
                   help='also download the routed DCP (requires --download)')
    p.add_argument('--wait-seconds', type=int, default=0,
                   help='maximum time to wait for job success; poll every 30 seconds')
    args = p.parse_args()
    if args.checkpoint and not args.download:
        p.error('--checkpoint requires --download')
    token = args.token_file.read_text().strip()
    # Refuse redirects: never forward the private token to an artifact CDN.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    def get(suffix=''):
        return opener.open(urllib.request.Request(
            f'{BASE}{PROJECT}/jobs/{args.job}{suffix}',
            headers={'PRIVATE-TOKEN': token}, method='GET'), timeout=120)
    if args.wait_seconds < 0:
        p.error('--wait-seconds must be nonnegative')
    deadline = time.monotonic() + args.wait_seconds
    while True:
        with get() as response:
            job = json.load(response)
        summary = {k: job.get(k) for k in ('id', 'status', 'web_url', 'artifacts_file')}
        summary['commit'] = job['commit']['id']
        print(json.dumps(summary), flush=True)
        if not args.wait_seconds or job['status'] == 'success':
            break
        if job['status'] in ('failed', 'canceled', 'skipped', 'manual'):
            raise SystemExit('CI cannot proceed automatically: ' + job['status'])
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise SystemExit('Timed out waiting for CI success')
        time.sleep(min(30, remaining))
    if args.trace:
        with get('/trace') as response, args.trace.open('wb') as output:
            shutil.copyfileobj(response, output)
    if args.download:
        if job['status'] != 'success':
            p.error('Refusing artifact download until the job succeeds')
        args.download.mkdir(parents=True, exist_ok=False)
        (args.download / 'job.json').write_text(json.dumps(job, indent=2) + '\n')
        hashes = []
        artifacts = dict(ARTIFACTS)
        if args.checkpoint:
            artifacts['shell_lauberhorn-eci_routed.dcp'] = (
                'out/eci/vivadoProject.dest/shell_lauberhorn-eci_routed.dcp')
        for name, artifact in artifacts.items():
            dest = args.download / name
            partial = dest.with_suffix(dest.suffix + '.part')
            with get('/artifacts/' + artifact) as response, partial.open('wb') as output:
                shutil.copyfileobj(response, output)
            partial.rename(dest)
            hashes.append(hashlib.sha256(dest.read_bytes()).hexdigest() + '  ' + name)
        (args.download / 'SHA256SUMS').write_text('\n'.join(hashes) + '\n')

if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as exc:
        raise SystemExit(f'GitLab GET failed: HTTP {exc.code} {exc.reason}')
