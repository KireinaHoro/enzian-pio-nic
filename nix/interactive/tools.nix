{
  pkgs,
  target,
  manifest,
  extraContents,
}:
let
  dependencies = import ./dependencies.nix { inherit target; };
  # Write the script on the build host; only its interpreter/tools run on Enzian.
  # target.writeShellScriptBin would require an AArch64 builder even though this
  # derivation only writes text, which fails in the x86_64 CI container.
  helper = pkgs.writeTextFile {
    name = "lh-test";
    destination = "/bin/lh-test";
    executable = true;
    text = ''
      #!${target.bash}/bin/bash
      export LH_MANIFEST=${manifest}
      export PATH=${
        pkgs.lib.makeBinPath [
          target.coreutils
          target.jq
          target.kmod
          target.iproute2
        ]
      }:$PATH
      ${builtins.readFile ../../tools/enzian/lh-test.sh}
    '';
  };

in
assert helper.system == pkgs.stdenv.buildPlatform.system;
pkgs.buildEnv {
  name = "lauberhorn-interactive-tools";
  # Only executables are linked below; avoid implicitly fetching target manuals.
  paths = [ helper ] ++ map pkgs.lib.getBin (builtins.attrValues dependencies) ++ extraContents;
  pathsToLink = [
    "/bin"
    "/sbin"
  ];
  ignoreCollisions = false;
}
