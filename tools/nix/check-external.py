#!/usr/bin/env python3
"""Check flake=false application overrides against the installed runtime contract."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', required=True, help='locked platform flake URL')
    parser.add_argument('--logs', type=Path, required=True)
    args = parser.parse_args()
    args.logs.mkdir(parents=True, exist_ok=False)
    demo = Path(__file__).resolve().parents[2] / 'sw/apps/nix-build-demo'
    with tempfile.TemporaryDirectory(prefix='lh-external-') as temporary:
        root = Path(temporary)
        for name in ('application', 'override'):
            shutil.copytree(demo, root / name)
        (root / 'override/local-asset').write_text('local application override\n')
        recipe = root / 'override/package.nix'
        recipe.write_text(recipe.read_text().replace('pkg-config,', 'pkg-config, hello,').replace('[ pkg-config ]', '[ pkg-config hello ]'))
        # JSON string quoting is valid Nix here only without interpolation.
        if '${' in args.platform:
            parser.error('unsupported interpolation in platform URL')
        (root / 'flake.nix').write_text('''{
  inputs.platform.url = PLATFORM;
  inputs.nixpkgs.follows = "platform/nixpkgs";
  inputs.application = { url = "path:./application"; flake = false; };
  outputs = inputs@{ self, nixpkgs, ... }: let
    pkgs = import nixpkgs { system = "x86_64-linux"; };
    platform = inputs.platform.lib.mkPlatform { inherit pkgs; };
    app = (platform.callPackage (inputs.application + "/package.nix") {}).overrideAttrs (old: {
      passthru = (old.passthru or {}) // { sourceIdentity = {
        revision = inputs.application.rev or null;
        local = !(inputs.application ? rev);
        narHash = inputs.application.narHash or null;
      }; };
    });
  in {
    packages.x86_64-linux = {
      application = app;
      inherit (platform) runtime kmod genVerilog;
      image = platform.mkTestImage { name = "external"; applications.demo = app; };
    };
    devShells.x86_64-linux.default = platform.target.mkShell { inputsFrom = [ app ]; };
  };
}
'''.replace('PLATFORM', json.dumps(args.platform)))
        def nix(*command):
            command = [f'path:{root}{arg[1:]}' if arg.startswith('.#') else arg for arg in command]
            if command[:2] == ['flake', 'lock']:
                command.append(f'path:{root}')
            return subprocess.check_output(['nix', *command], cwd=root, text=True).strip()
        nix('flake', 'lock')
        lock = (root / 'flake.lock').read_bytes()
        override = ['--override-input', 'application', f'path:{root}/override', '--no-write-lock-file']
        paths = {}
        for attr in ('application', 'runtime', 'kmod', 'genVerilog', 'image'):
            paths[attr] = [nix('eval', '--raw', f'.#{attr}.drvPath', *options)
                           for options in ([], override)]
            assert (paths[attr][0] != paths[attr][1]) == (attr in ('application', 'image')), attr
        shell = [nix('eval', '--raw', '.#devShells.x86_64-linux.default.drvPath', *options)
                 for options in ([], override)]
        assert shell[0] != shell[1], 'application shell did not follow override'
        nix('build', '.#application', '--no-link', '-L', *override)
        assert (root / 'flake.lock').read_bytes() == lock, 'override changed committed lock'
        (args.logs / 'flake.nix').write_bytes((root / 'flake.nix').read_bytes())
        (args.logs / 'flake.lock').write_bytes(lock)
        (args.logs / 'summary.json').write_text(json.dumps({'paths': paths, 'shells': shell,
                                                          'lockUnchanged': True}, indent=2) + '\n')
        print('External build and local override isolation passed')


if __name__ == '__main__':
    main()
