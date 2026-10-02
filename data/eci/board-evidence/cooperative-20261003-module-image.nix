# Exact composition used after the initial candidate's kernel indexing failure.
# Only the module changes; generated collateral, runtime and applications remain
# from hardware pipeline 515944. Both source identities are in the image manifest.
let
  root = toString ../../..;
  hw = builtins.getFlake "git+file://${root}?rev=8cc9ad584a5bf6b32284ef04746d9f3497fc1a74&submodules=1";
  fixed = builtins.getFlake "git+file://${root}?rev=336c65d69ba471a58d09ed7aa5a075b9de879d6c&submodules=1";
  pkgs = import hw.inputs.nixpkgs { system = "x86_64-linux"; };
  hp = hw.lib.mkPlatform { inherit pkgs; };
  sp = fixed.lib.mkPlatform { inherit pkgs; };
  module = sp.kmod.override { genVerilog = hp.genVerilog; devHdrs = hp.devHdrs; };
  deployment = pkgs.callPackage (hw.outPath + "/nix/images/default.nix") {
    target = import hw.inputs.nixpkgs { system = "aarch64-linux"; };
    kmod = module;
    inherit (hp) runtime genVerilog devHdrs kernelRelease;
    identity = {
      revision = "8cc9ad584a5bf6b32284ef04746d9f3497fc1a74";
      local = false;
      narHash = hw.narHash;
      moduleSourceRevision = "336c65d69ba471a58d09ed7aa5a075b9de879d6c";
      moduleSourceNarHash = fixed.narHash;
      composition = "Original hardware/runtime/applications; corrected module built with original generated headers";
    };
  };
in {
  inherit module;
  image = deployment.mkTestImage {
    name = "cooperative-515944-worker-index-fix";
    applications = { inherit (hp.applications) microbenchmarks recovery-test nested-validation; };
  };
}
