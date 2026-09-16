{
  pkgs,
  target,
  manifest,
  extraContents,
}:
let
  helper = target.writeShellScriptBin "lh-test" ''
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

in
pkgs.buildEnv {
  name = "lauberhorn-interactive-tools";
  paths = [
    helper
    target.bash
    target.coreutils
    target.jq
    target.kmod
    target.iproute2
    target.util-linux
    target.rpcbind
    target.libtirpc
  ]
  ++ extraContents;
  pathsToLink = [
    "/bin"
    "/sbin"
  ];
  ignoreCollisions = false;
}
