# Runtime-only Enzian client image: all executables are cross-built on the host.
{ pkgs, dpdk, modules, identity ? { revision = null; }, nativeVariants ? {}, probeDpdk ? dpdk, mlx5 ? false }:
let
  target = pkgs.pkgsCross.aarch64-multiplatform;
  generator = target.callPackage ../../sw/apps/microbenchmarks/dpdk/package.nix { inherit dpdk probeDpdk mlx5; };
  utilities = map pkgs.lib.getBin (with target; [ bash coreutils iproute2 kmod util-linuxMinimal python3 ]);
  scriptPaths = {
    lh-dpdk-run = "tools/enzian/dpdk_run.py";
    lh-cpu-isolation = "tools/enzian/cpu_isolation.py";
    lh-benchmark-summary = "tools/enzian/benchmark_summary.py";
    lh-trace-dump = "data/eci/sys_trace/dump_trace_udp.py";
    lh-dpdk-replay = "sw/apps/microbenchmarks/dpdk/replay_test.py";
  };
  sourceRoot = ../..;
  scriptSource = pkgs.lib.cleanSourceWith {
    src = pkgs.lib.cleanSource sourceRoot;
    filter = path: type:
      let relative = pkgs.lib.removePrefix (toString sourceRoot + "/") (toString path);
      in if type == "directory" then
        pkgs.lib.any (name: pkgs.lib.hasPrefix (relative + "/") name)
          (builtins.attrValues scriptPaths)
      else type == "regular" && builtins.elem relative (builtins.attrValues scriptPaths);
  };
  scripts = pkgs.lib.mapAttrs (_: relative: scriptSource + "/${relative}") scriptPaths;
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
    queueInstrumentation = if mlx5 then "mlx5-scalar-cpu-v1" else null;
    packages = {
      generator = "${generator}"; dpdk = "${dpdk}"; probeDpdk = "${probeDpdk}";
      thunderxPtpModules = "${modules}";
    };
    nativeVariants = pkgs.lib.mapAttrs (_: variant: {
      app = "${variant.app}"; runtime = "${variant.runtime}";
      tracing = variant.runtime.tracing;
    }) nativeVariants;
    responderTracing = { echo_dpdk = true; echo_dpdk_uninstrumented = false; };
    kernelRelease = modules.kernelRelease;
    scripts = pkgs.lib.mapAttrs (_: source: builtins.hashFile "sha256" source) scripts;
    sourceHashes = builtins.listToAttrs (map (name: {
      inherit name;
      value = builtins.hashFile "sha256" (generator.src + "/${name}");
    }) [ "bench.x" "echo_registry.py" "dpdk/bench_dpdk.c" "dpdk/echo_server.c"
         "dpdk/benchmarks.c" "dpdk/benchmark.h" "dpdk/wire.h" "dpdk/load.h"
         "dpdk/aggregate.h" "dpdk/open_loop.h" "dpdk/Makefile" ]);
    hostRequirements = [ "compatible Enzian kernel and nicvf/vfio-pci modules" "root access" ];
  });
  environment = pkgs.buildEnv {
    name = "lauberhorn-client-environment";
    paths = [ generator wrappers ] ++ utilities;
    pathsToLink = [ "/bin" "/sbin" ];
    postBuild = ''
      ln -s ${manifest} $out/manifest.json
      ln -s ${modules}/lib/modules/${modules.kernelRelease}/extra $out/modules
    '';
  };
  image = pkgs.callPackage "${pkgs.path}/nixos/lib/make-squashfs.nix" {
    storeContents = [ environment ];
    fileName = "lauberhorn-client";
    pseudoFiles = [ "lauberhorn-client s 0777 0 0 ${baseNameOf environment}" ];
  };
in { inherit generator environment image; }
