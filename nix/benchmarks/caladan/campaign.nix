# One immutable image for both probe modes and the shared generator/harness.
{ pkgs, target, port, clientEnvironment, microbenchmarkSource, rpcSource, executionModel ? "resident-workers" }:
let
  server = tracing: target.callPackage (microbenchmarkSource + "/server/caladan/package.nix") {
    source = microbenchmarkSource;
    lauberhornSource = rpcSource;
    qemu = pkgs.qemu;
    caladan = port.runtime.override { inherit tracing; };
    inherit (port) rdmaCore;
    inherit tracing executionModel;
  };
  activationRuntime = port.runtime.override { tracing = true; };
  off = server false;
  breakdown = server true;
  image = pkgs.callPackage "${pkgs.path}/nixos/lib/make-squashfs.nix" {
    storeContents = [ clientEnvironment port.environment activationRuntime off breakdown ];
    fileName = "caladan-campaign";
    pseudoFiles = [
      "lauberhorn-client s 0777 0 0 ${baseNameOf clientEnvironment}"
      "caladan-activation s 0777 0 0 ${baseNameOf activationRuntime}"
      "caladan s 0777 0 0 ${baseNameOf port.environment}"
      "caladan-echo-off s 0777 0 0 ${baseNameOf off}"
      "caladan-echo-breakdown s 0777 0 0 ${baseNameOf breakdown}"
    ];
  };
in { inherit image off breakdown; }
