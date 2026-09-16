{
  pkgs,
  crossPkgs,
  kernel,
  staticShell,
  deploymentPackages,
}:
let
  dependencies = {
    inherit kernel staticShell;
    crossStdenv = crossPkgs.stdenv;
    crossPkgConfig = crossPkgs.buildPackages.pkg-config;
    crossRpcgen = crossPkgs.buildPackages.rpcsvc-proto;
    crossRpcgenHeaders = crossPkgs.buildPackages.rpcsvc-proto.dev;
    crossTirpc = crossPkgs.libtirpc;
    crossTirpcHeaders = crossPkgs.libtirpc.dev;
    nukeRefs = pkgs.nukeReferences;
    jq = pkgs.jq.dev;
    shellcheckMinimal = pkgs.shellcheck-minimal;
    # buildEnv is a local builder; warming it retains its generated Perl builder.
    buildEnvSupport = pkgs.buildEnv {
      name = "ci-build-env-support";
      paths = [ ];
    };
  }
  // pkgs.lib.mapAttrs' (name: value: pkgs.lib.nameValuePair "deployment-${name}" value) deploymentPackages;
in
# These are cached data, not inputs to a native development shell. String paths
# preserve exact cross/native package variants instead of dependency splicing
# selecting host variants (as mkShell.packages did for cross-built libtirpc).
pkgs.linkFarm "lauberhorn-ci-dependencies" (
  pkgs.lib.mapAttrsToList (name: package: {
    inherit name;
    path = "${package}";
  }) dependencies
)
