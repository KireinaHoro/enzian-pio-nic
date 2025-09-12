{
  description = "devShell for Lauberhorn";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
    mackerel = {
      url = "git+https://gitlab.inf.ethz.ch/project-opensockeye/mackerel2";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };

  outputs = inputs@{ nixpkgs, flake-utils, ... }:
  with builtins;
  with nixpkgs.lib;
  flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-darwin" ] (system: let
    pkgs = import nixpkgs { inherit system; };
    millw = pkgs.stdenv.mkDerivation {
      name = "millw";
      nativeBuildInputs = [ pkgs.makeWrapper ];
      src = pkgs.fetchurl {
        url = https://raw.githubusercontent.com/com-lihaoyi/mill/f5d0c9f87ac58795323904c2cce44c105e652b50/mill;
        hash = "sha256-yXCQ5YR0dOxjvC3beZDF0O3pVUXbZIyeKiEwU1nxWEw=";
      };
      phases = [ "installPhase" ];
      installPhase = ''
        mkdir -p $out/bin
        cp $src $out/bin/mill
        chmod +x $out/bin/mill
        wrapProgram $out/bin/mill \
          --add-flags "--no-server"
      '';
    };

    # aarch64 cross compiler
    crossGcc = pkgs.pkgsCross.aarch64-multiplatform.buildPackages.gcc;

    # mackerel compiler
    mackerel = inputs.mackerel.packages.${system}.mackerel2;

    # common build tools for building kernel (modules)
    linuxTools = with pkgs; [
      flex bison bc openssl elfutils.dev crossGcc
      pahole python3 zlib.dev
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

    # FIXME: we can't run the SpinalHDL generator inside stdenv, since mill
    #        wants to fetch all dependencies from the Internet.  Hence we check
    #        in all generated device files for now

    # generate C headers
    lauberhorn-dev-hdrs = pkgs.stdenvNoCC.mkDerivation {
      name = "lauberhorn-dev-hdrs";
      src = cleanSource ./sw/devices;
      nativeBuildInputs = [ mackerel ];
      buildPhase = ''
        mkdir -p $out
        for a in *.dev; do
          echo "Compiling $a..."
          mackerel2 -c $a -I$PWD -o $out/''${a%.dev}.h
        done
      '';
    };

    # cross-compile lauberhorn kernel module
    lauberhorn-kmod = let
      hwGenHdrs = cleanSource ./hw/gen;
    in pkgs.stdenv.mkDerivation {
      name = "lauberhorn-kmod";
      version = "0.0.1";
      src = cleanSource ./sw;
      nativeBuildInputs = linuxTools;
      buildPhase = ''
        export ARCH=arm64
        export CROSS_COMPILE=aarch64-unknown-linux-gnu-
        export KDIR=${linux-noble-src}
        pushd kmod
        make V=1 MACKEREL_DEV_HDRS=${lauberhorn-dev-hdrs} HW_CFG_HDRS=${hwGenHdrs}
        popd
      '';
      installPhase = ''
        mkdir -p $out
        cp kmod/lauberhorn.ko $out/
      '';
      dontFixup = true;
    };
  in {
    packages = {
      inherit
        linux-noble-src
        lauberhorn-dev-hdrs lauberhorn-kmod;
    };

    # for interactive development (mill needs to download Ivy deps for now)
    # TODO: upgrade SpinalHDL to newer mill version and use mill-ivy-fetch
    #       to allow running the generator inside the stdenv sandbox
    devShells.default = with pkgs; let
      repeatTest = writeShellApplication {
        name = "repeat-test";
        runtimeInputs = [ millw ];
        text = ''
          test_name="$1"
          while mill gen.test.testOnly lauberhorn.host.eci.NicSim -- -t "$test_name"; do
            echo "Test succeeded, retrying..."
          done
        '';
      };
    in mkShell {
      buildInputs = [
        zlib.dev verilator clang
        gtkwave sby yices
        jdk millw cmake
        crossGcc mackerel
        # quick script to repeat known failing test to find a good reproducer
        repeatTest
      ];
    };
  });
}
