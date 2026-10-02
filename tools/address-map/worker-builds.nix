# Cross-build software against headers emitted by GenWorkerConfigurations.
# Use a committed platform revision and its matching generated configurations.
{ revision, generatedRoot }:
let
  root = ../..;
  platform = builtins.getFlake "git+file://${toString root}?rev=${revision}&submodules=1";
  packages = platform.packages.x86_64-linux;
  configurations = builtins.genList (i:
    let
      n = toString (i + 1);
      base = generatedRoot + "/${n}";
      generated = {
        headers = builtins.path { path = base + "/headers"; name = "eci-${n}-config-headers"; };
        devices = builtins.path { path = base + "/devices"; name = "eci-${n}-device-sources"; };
      };
      devHdrs = packages.devHdrs.override { genVerilog = generated; };
    in [
      { name = "kmod${n}"; value = packages.kmod.override { inherit devHdrs; genVerilog = generated; }; }
      { name = "runtime${n}"; value = packages.runtime.override { inherit devHdrs; genVerilog = generated; }; }
    ]) 7;
in builtins.listToAttrs (builtins.concatLists configurations)
