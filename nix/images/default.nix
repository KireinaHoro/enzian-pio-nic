{
  pkgs,
  target,
  kmod,
  runtime,
  genVerilog,
  devHdrs,
  identity,
  kernelRelease,
}:
let
  mkDeploymentEnvironment =
    {
      applications,
      extraContents ? [ ],
    }:
    let
      valid = name: builtins.match "[a-zA-Z0-9][a-zA-Z0-9._-]*" name != null;
      appInfo = pkgs.lib.mapAttrs (name: app: {
        path = "${app}";
        entryPoint = if app.meta ? mainProgram then "${app}/bin/${app.meta.mainProgram}" else null;
        source =
          app.passthru.sourceIdentity or {
            revision = null;
            local = null;
            path = if app ? src then toString app.src else null;
          };
      }) applications;
      manifest = pkgs.writeText "lauberhorn-manifest.json" (
        builtins.toJSON {
          schemaVersion = 1;
          platform = identity;
          inherit kernelRelease;
          collateral = {
            rtl = "${genVerilog}";
            headers = "${genVerilog.headers}";
            devices = "${genVerilog.devices}";
            mackerel = "${devHdrs}";
          };
          packages = {
            runtime = "${runtime}";
            module = "${kmod}/lauberhorn.ko";
          };
          applications = appInfo;
        }
      );
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
      tools = pkgs.buildEnv {
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
      };
    in
    assert pkgs.lib.all valid (builtins.attrNames applications);
    pkgs.runCommand "lauberhorn-deployment-environment" { } ''
      mkdir -p $out/modules $out/apps
      ln -s ${tools}/bin $out/bin
      ln -s ${tools}/sbin $out/sbin
      ln -s ${kmod}/lauberhorn.ko $out/modules/lauberhorn.ko
      ln -s ${manifest} $out/manifest.json
      ${pkgs.lib.concatStringsSep "\n" (
        pkgs.lib.mapAttrsToList (name: app: "ln -s ${app} $out/apps/${name}") applications
      )}
    '';
  mkTestImage =
    {
      name,
      applications,
      extraContents ? [ ],
    }:
    let
      environment = mkDeploymentEnvironment { inherit applications extraContents; };
    in
    pkgs.callPackage "${pkgs.path}/nixos/lib/make-squashfs.nix" {
      storeContents = [
        environment
        runtime
        genVerilog
        genVerilog.headers
        genVerilog.devices
        devHdrs
      ];
      fileName = "lauberhorn-${name}";
      pseudoFiles = [ "lauberhorn s 0777 0 0 ${baseNameOf environment}" ];
    };
in
{
  inherit mkDeploymentEnvironment mkTestImage;
}
