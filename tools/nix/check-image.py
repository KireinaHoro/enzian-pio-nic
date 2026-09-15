#!/usr/bin/env python3
"""Verify stable links, store closure links, and target ELF files in an extracted image."""
import argparse
import json
import os
from pathlib import Path
import posixpath
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, help='unsquashfs output directory')
    args = parser.parse_args()
    root = args.root.resolve()

    class HostLink(ValueError):
        pass

    def resolve(path):
        path = str(path)
        for _ in range(100):
            if path.startswith('/nix/store/'):
                path = path[len('/nix/store/'):]
            elif path.startswith('/'):
                raise HostLink(path)
            path = posixpath.normpath(path)
            parts = Path(path).parts
            if '..' in parts:
                raise HostLink(posixpath.normpath('/nix/store/' + path))
            for index in range(len(parts)):
                local = root.joinpath(*parts[:index + 1])
                if local.is_symlink():
                    target = os.readlink(local)
                    prefix = '' if target.startswith('/') else str(Path(*parts[:index]))
                    path = posixpath.join(prefix, target, *parts[index + 1:])
                    break
            else:
                assert (root / path).exists(), path
                return root / path
        raise ValueError('symlink loop: ' + path)

    links = 0
    host_links = {}
    for path in root.rglob('*'):
        if path.is_symlink():
            try:
                resolve(path.relative_to(root))
                links += 1
            except HostLink as exc:
                host_links[str(path.relative_to(root))] = str(exc)
    manifest = json.loads(resolve('lauberhorn/manifest.json').read_text())
    for tool in ('lh-test', 'jq', 'insmod', 'modinfo', 'ip', 'mount', 'rpcinfo'):
        resolve('lauberhorn/bin/' + tool)
    module = resolve('lauberhorn/modules/lauberhorn.ko')
    files = [module, resolve(manifest['packages']['runtime'] + '/lib/liblauberhorn.so')]
    for name, app in manifest['applications'].items():
        resolve('lauberhorn/apps/' + name)
        if app['entryPoint']:
            files.append(resolve(app['entryPoint']))
    elf = {}
    for file in files:
        header = subprocess.check_output(['readelf', '-h', str(file)], text=True)
        assert 'AArch64' in header, str(file)
        dynamic = subprocess.check_output(['readelf', '-d', str(file)], text=True)
        needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
        rpaths = re.findall(r'\((?:RUNPATH|RPATH)\).*?\[(.*?)\]', dynamic)
        for dependency in needed:
            candidates = [directory + '/' + dependency for path in rpaths for directory in path.split(':')]
            assert any(resolve(candidate).exists() for candidate in candidates
                       if (root / candidate.removeprefix('/nix/store/')).exists()), dependency
        program = subprocess.check_output(['readelf', '-l', str(file)], text=True)
        for interpreter in re.findall(r'Requesting program interpreter: (.*?)\]', program):
            resolve(interpreter)
        elf[file.name] = needed
    modinfo = subprocess.check_output(['readelf', '-p', '.modinfo', str(module)], text=True)
    assert 'vermagic=' + manifest['kernelRelease'] + ' ' in modinfo
    print(json.dumps({'linksChecked': links, 'hostConfigurationLinks': host_links, 'elfDependencies': elf, 'manifest': manifest}, indent=2))


if __name__ == '__main__':
    main()
