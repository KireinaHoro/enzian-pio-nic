# Build only a new application against an immutable hardware-pipeline platform.
# Unlike --override-input, retain the platform's original self.rev and collateral.
{ platformRef, applicationRevision, applicationBranch ? "feat/lauberhorn-rx-wait" }:
let
  platform = builtins.getFlake platformRef;
  application = builtins.fetchGit {
    url = "ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/benchmarking/memcached.git";
    ref = applicationBranch;
    rev = applicationRevision;
  };
  definition = import (platform.outPath + "/flake.nix");
  updated = (definition.outputs (platform.inputs // {
    self = platform;
    memcached = application;
  })).packages.x86_64-linux;
  baseline = platform.packages.x86_64-linux;
in
assert platform ? rev;
assert builtins.match "[0-9a-f]{40}" applicationRevision != null;
assert toString updated.genVerilog == toString baseline.genVerilog;
assert toString updated.devHdrs == toString baseline.devHdrs;
assert toString updated.runtime == toString baseline.runtime;
assert toString updated.kmod == toString baseline.kmod;
updated.memcachedDeployFs
