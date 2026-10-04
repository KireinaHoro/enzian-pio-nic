# Runtime-only Enzian client image: all executables are cross-built on the host.
{ pkgs, identity ? { revision = null; } }:
let
  target = pkgs.pkgsCross.aarch64-multiplatform;
  dpdk = target.callPackage ../software/dpdk-thunderx.nix { };
  generator = target.callPackage ../../sw/apps/microbenchmarks/dpdk/package.nix { inherit dpdk; };
  utilities = map pkgs.lib.getBin (with target; [ bash coreutils iproute2 kmod util-linuxMinimal python3 ]);
  scripts = {
    lh-dpdk-run = ../../tools/enzian/dpdk_run.py;
    lh-benchmark-summary = ../../tools/enzian/benchmark_summary.py;
    lh-trace-dump = ../../data/eci/sys_trace/dump_trace_udp.py;
    lh-dpdk-replay = ../../sw/apps/microbenchmarks/dpdk/replay_test.py;
  };
  wrappers = pkgs.runCommand "lauberhorn-client-commands" { } ''
    mkdir -p $out/bin
    ${pkgs.lib.concatStringsSep "\n" (pkgs.lib.mapAttrsToList (name: source: ''
      cat > $out/bin/${name} <<'SCRIPT'
      #!${target.bash}/bin/bash
      export PATH=${pkgs.lib.makeBinPath utilities}
      unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME
      exec ${target.python3}/bin/python3 ${source} "$@"
      SCRIPT
      chmod +x $out/bin/${name}
    '') scripts)}
  '';
  manifest = pkgs.writeText "lauberhorn-client-manifest.json" (builtins.toJSON {
    schemaVersion = 1;
    inherit identity;
    system = target.stdenv.hostPlatform.config;
    dpdkVersion = dpdk.version;
    packages = { generator = "${generator}"; dpdk = "${dpdk}"; };
    scripts = pkgs.lib.mapAttrs (_: source: builtins.hashFile "sha256" source) scripts;
    sourceHashes = builtins.listToAttrs (map (name: {
      inherit name;
      value = builtins.hashFile "sha256" (../../sw/apps/microbenchmarks/dpdk + "/${name}");
    }) [ "bench_dpdk.c" "benchmarks.c" "benchmark.h" "wire.h" "Makefile" ]);
    hostRequirements = [ "compatible Enzian kernel and nicvf/vfio-pci modules" "root access" ];
  });
  environment = pkgs.buildEnv {
    name = "lauberhorn-client-environment";
    paths = [ generator wrappers ] ++ utilities;
    pathsToLink = [ "/bin" "/sbin" ];
    postBuild = "ln -s ${manifest} $out/manifest.json";
  };
  image = pkgs.callPackage "${pkgs.path}/nixos/lib/make-squashfs.nix" {
    storeContents = [ environment ];
    fileName = "lauberhorn-client";
    pseudoFiles = [ "lauberhorn-client s 0777 0 0 ${baseNameOf environment}" ];
  };
in { inherit generator environment image; }
