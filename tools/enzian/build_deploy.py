#!/usr/bin/env python3
"""Build deployFs at a CI revision, optionally applying a recorded packaging fix."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('revision')
    parser.add_argument('--out-link', type=pathlib.Path, required=True)
    parser.add_argument('--packaging-patch', type=pathlib.Path,
                        help='reviewed packaging-only patch; must preserve HW/ABI generation')
    args = parser.parse_args()
    if not re.fullmatch('[0-9a-f]{40}', args.revision):
        parser.error('full lowercase Git revision required')
    root = pathlib.Path(__file__).resolve().parents[2]
    ref = f'git+{root.as_uri()}?rev={args.revision}&submodules=1'
    # JSON strings are also Nix strings here; reject Nix interpolation in paths.
    if '${' in ref:
        parser.error('unsupported repository path containing Nix interpolation')
    out = args.out_link.absolute()
    provenance = {'revision': args.revision, 'source': ref}
    if args.packaging_patch:
        patch = args.packaging_patch.resolve()
        stats = subprocess.check_output(['git', 'apply', '--numstat', str(patch)], text=True)
        paths = [line.split('\t')[-1] for line in stats.splitlines()]
        allowed = lambda p: p == 'flake.nix' or (p.startswith('nix/') and p.endswith('.nix')) or p in {
            'tools/enzian/lh-test.sh', 'sw/usr-common.mk', 'sw/rt/Makefile',
            'sw/apps/microbenchmarks/Makefile', 'sw/apps/nix-build-demo/package.nix'
        }
        if not paths or not all(allowed(p) for p in paths):
            parser.error('patch includes files outside the packaging allowlist; review hardware/ABI separately')
        provenance['packaging_patch_sha256'] = hashlib.sha256(patch.read_bytes()).hexdigest()
        with tempfile.TemporaryDirectory(prefix='lauberhorn-deploy-') as directory:
            work = pathlib.Path(directory)
            source = pathlib.Path(subprocess.check_output([
                'nix', 'eval', '--impure', '--raw', '--expr',
                f'(builtins.getFlake {json.dumps(ref)}).outPath'], text=True).strip())
            # Copy only the code inputs used by deployFs, excluding trace captures.
            for name in ('hw', 'deps', 'sw', 'nix', 'tools', 'vivado'):
                if not (source / name).exists():
                    continue
                shutil.copytree(source / name, work / name, symlinks=True)
            for name in ('build.mill', 'project-lock.nix', 'flake.nix', 'flake.lock'):
                shutil.copyfile(source / name, work / name)
            # Nix source directories are read-only; allow temporary-tree cleanup.
            for path in work.rglob('*'):
                if path.is_dir() and not path.is_symlink():
                    path.chmod(path.stat().st_mode | 0o700)
            subprocess.run(['git', 'apply', '--check', str(patch)], cwd=work, check=True)
            subprocess.run(['git', 'apply', str(patch)], cwd=work, check=True)
            (work / 'build.nix').write_text(
                f'let ci = builtins.getFlake {json.dumps(ref)};\n'
                'fixed = import ./flake.nix;\n'
                'outputs = fixed.outputs (ci.inputs // { self = ci; });\n'
                'in outputs.packages.x86_64-linux.deployFs\n')
            subprocess.run(['nix', 'build', '--impure', '--file', str(work / 'build.nix'),
                            '--out-link', str(out), '-L'], check=True)
        shutil.copyfile(patch, str(out) + '.patch')
    else:
        subprocess.run(['nix', 'build', ref + '#deployFs', '--out-link', str(out), '-L'], check=True)
    provenance['image'] = str(out.resolve())
    provenance['image_sha256'] = hashlib.sha256(out.read_bytes()).hexdigest()
    pathlib.Path(str(out) + '.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps(provenance, indent=2))

if __name__ == '__main__':
    main()
