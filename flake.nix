{
  description = "devShell for Lauberhorn";
  inputs = {
    self.submodules = true;
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
    mill-ivy-fetcher = {
      url = "github:Avimitin/mill-ivy-fetcher";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mackerel = {
      url = "git+https://gitlab.inf.ethz.ch/project-opensockeye/mackerel2";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };

  outputs = inputs@{ self, nixpkgs, flake-utils, ... }:
  with builtins;
  with nixpkgs.lib;
  flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-darwin" ] (system: let
    pkgs = import nixpkgs {
      inherit system;
      overlays = [
        inputs.mill-ivy-fetcher.overlays.default
        inputs.mill-ivy-fetcher.overlays.mill-overlay
      ];
    };
    aarch64Pkgs = pkgs.pkgsCross.aarch64-multiplatform;

    # aarch64 cross compiler
    crossGcc = aarch64Pkgs.buildPackages.gcc;

    # mackerel compiler
    mackerel = inputs.mackerel.packages.${system}.mackerel2;

    # common build tools for building kernel (modules)
    linuxTools = with pkgs; [
      flex bison bc openssl elfutils.dev crossGcc
      pahole python3 zlib.dev
      rpcsvc-proto pkg-config
    ];

    # get kernel tree for building module
    # unpack Noble linux headers deb to get Modules.symvers and config
    linux-noble-src = let
      genericDeb = pkgs.fetchurl {
        url = http://launchpadlibrarian.net/799672062/linux-headers-6.8.0-64-generic_6.8.0-64.67_arm64.deb;
        hash = "sha256-x375IU9XFmuVJEoocimnR5qRUS8ya6sWqs3jYPeaTKM=";
      };
    in pkgs.stdenv.mkDerivation {
      name = "linux-noble-src";
      version = "6.8.0-64.67";
      src = pkgs.fetchgit {
        url = https://git.launchpad.net/~ubuntu-kernel/ubuntu/+source/linux/+git/noble;
        tag = "Ubuntu-6.8.0-64.67";
        hash = "sha256-F2bcvzxlE2wzSj2kr+Fj9Ui6ht6GRv4p/hRSuSyglCc=";
      };
      nativeBuildInputs = linuxTools ++ [ pkgs.dpkg ];
      buildPhase = ''
        patchShebangs scripts/bpf_doc.py

        export ARCH=arm64
        export CROSS_COMPILE=aarch64-unknown-linux-gnu-

        mkdir sysroot
        dpkg-deb -x ${genericDeb} sysroot/
        for a in .config Module.symvers; do
          cp sysroot/usr/src/linux-headers-6.8.0-64-generic/$a .
        done
        rm -rf sysroot

        cp .config .config.bak
        make olddefconfig
        make modules_prepare
      '';
      installPhase = ''
        mkdir -p $out
        cp -a . $out/
      '';
      dontFixup = true;
    };

    allSourcesIn = ty: paths: with fileset; toSource {
      root = ./.;
      fileset = unions (map (p: fileFilter ty p) paths);
    };

    isC = f: f.name == "Makefile" || lists.any f.hasExt [ "mk" "c" "h" "x" ];
    allCIn = allSourcesIn isC;

    isSpinal = f: lists.any f.hasExt [
      "scala" "java" "xml" "conf" # spinalhdl
      "mill"                      # mill build files
      "v" "sv"                    # RTL dependencies
    ];
    allSpinalIn = allSourcesIn isSpinal;

    # generate RTL, mackerel devices, and C headers
    genVerilog = with pkgs; let
      ivyCache = ivy-gather ./project-lock.nix;
      gitRev = if self ? rev then self.rev else "ffffffffffffffff";
    in stdenvNoCC.mkDerivation {
      name = "lauberhorn-hw-rtl-config";
      src = allSpinalIn [ ./build.mill ./hw ./deps ];
      outputs = [ "out" "devices" "headers" ];
      buildInputs = [ ivyCache ];
      nativeBuildInputs = [ mill configure-mill-env-hook ];
      buildPhase = ''
        mill --no-daemon --offline \
          -Dnix-git-hash=${gitRev} eci.generateVerilog
      '';
      installPhase = ''
        mkdir -p $out $devices $headers
        mv out/eci/generateVerilog.dest/*.{v,sv,xdc} $out/
        mv out/eci/generateVerilog.dest/*.h          $headers/
        mv out/eci/generateVerilog.dest/*.dev        $devices/
      '';
    };

    # generate mackerel device headers
    devHdrs = pkgs.stdenvNoCC.mkDerivation {
      name = "lauberhorn-dev-hdrs";
      src = cleanSource ./sw/devices;
      nativeBuildInputs = [ mackerel ];
      buildPhase = ''
        mkdir -p $out
        for a in *.dev ${genVerilog.devices}/*; do
          echo "Compiling $a..."
          fn=$(basename $a)
          mackerel2 -c $a -I$(dirname $a) -o $out/''${fn%.dev}_dev.h
        done
      '';
    };

    # cross-compile lauberhorn kernel module
    kmod = pkgs.stdenv.mkDerivation {
      name = "lauberhorn-kmod";
      version = "0.0.1";
      src = allCIn [ ./sw/kmod ./sw/core ];
      nativeBuildInputs = linuxTools ++ [ pkgs.nukeReferences ];
      buildPhase = ''
        export ARCH=arm64
        export CROSS_COMPILE=aarch64-unknown-linux-gnu-
        export KDIR=${linux-noble-src}
        cd sw/kmod
        make V=1 MACKEREL_DEV_HDRS=${devHdrs} HW_CFG_HDRS=${genVerilog.headers}
      '';
      installPhase = ''
        mkdir -p $out
        nuke-refs lauberhorn.ko
        mv lauberhorn.ko $out/
      '';
      dontFixup = true;
    };

    runtime = pkgs.stdenvNoCC.mkDerivation {
      name = "lauberhorn-rt";
      version = "0.0.1";
      src = allCIn [
        ./sw/rt ./sw/include ./sw/core
        ./sw/usr-common.mk ./sw/kmod/ioctl.h
      ];
      buildInputs = [ aarch64Pkgs.libtirpc ];
      nativeBuildInputs = linuxTools;
      buildPhase = ''
        cd sw/rt
        make MACKEREL_DEV_HDRS=${devHdrs} HW_CFG_HDRS=${genVerilog.headers}
      '';
      dontStrip = true;
      installPhase = ''
        mkdir -p $out
        mv liblauberhorn.so $out/
      '';
    };

    rpcsvc-proto = with pkgs; stdenv.mkDerivation {
      name = "rpcsvc-proto";
      version = "1.4.4";
      src = fetchFromGitHub {
        owner = "thkukuk";
        repo = "rpcsvc-proto";
        rev = "v1.4.4";
        hash = "sha256-DEXzSSmjMeMsr1PoU/ljaY+6b4COUU2Z8MJkGImsgzk=";
      };
      nativeBuildInputs = [ autoreconfHook ];
    };

    # can't use NoCC since rpcgen needs cpp
    buildLauberhornApp = name: with pkgs; stdenv.mkDerivation {
      name = "lauberhorn-app-${name}";
      version = "0.0.1";
      src = allCIn [ ./sw/apps/${name} ./sw/include ./sw/usr-common.mk ];
      buildInputs = [ aarch64Pkgs.libtirpc ];
      nativeBuildInputs = linuxTools;
      buildPhase = ''
        cd sw/apps/${name}
        make LAUBERHORN_RT=${runtime}/
      '';
      dontStrip = true;
      installPhase = ''
        mkdir -p $out
        mv ${name} $out/
      '';
    };

    deployFs = let
      allApps = [ "adder-demo" ];
    in pkgs.callPackage "${pkgs.path}/nixos/lib/make-squashfs.nix" {
      storeContents = map buildLauberhornApp allApps ++ [ kmod ];
    };
  in {
    packages = {
      inherit devHdrs kmod runtime deployFs genVerilog;
    };

    # for interactive development
    devShells.default = with pkgs; let
      # hammer a test that failed on CI but can't be easily reproduced locally
      repeatTest = writeShellApplication {
        name = "repeat-test";
        runtimeInputs = [ mill ];
        text = ''
          test_name="$1"
          if [[ $# == 2 ]]; then
            test_suite="$2"
          else
            test_suite="lauberhorn.host.eci.NicSim"
          fi
          while mill gen.test.testOnly "$test_suite" -- -t "$test_name"; do
            echo "Test succeeded, retrying..."
          done
        '';
      };
      updateMillLockFile = writeShellApplication {
        name = "update-mill-lock";
        runtimeInputs = [ mill-ivy-fetcher nixfmt ];
        text = ''
          mif codegen --cache "$XDG_CACHE_HOME" -o project-lock.nix
        '';
      };
    in mkShell {
      buildInputs = [
        zlib.dev verilator clang cmake
        gtkwave sby yices
        jdk mill updateMillLockFile
        crossGcc mackerel
        # quick script to repeat known failing test to find a good reproducer
        repeatTest
      ];
      env.LD_LIBRARY_PATH = makeLibraryPath [ libpcap ];
      shellHook = ''
        export XDG_CACHE_HOME=$PWD/out/xdg-cache-home/
      '';
    };
  });
}
