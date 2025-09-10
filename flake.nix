{
  description = "devShell for Lauberhorn";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
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
    linux-noble-src = pkgs.stdenv.mkDerivation {
      name = "linux-noble-src";
      version = "6.8.0-64.67";
      src = pkgs.fetchgit {
        url = https://git.launchpad.net/~ubuntu-kernel/ubuntu/+source/linux/+git/noble;
        tag = "Ubuntu-6.8.0-64.67";
        hash = "sha256-F2bcvzxlE2wzSj2kr+Fj9Ui6ht6GRv4p/hRSuSyglCc=";
      };
      nativeBuildInputs = with pkgs; [ flex bison bc openssl libelf crossGcc ];
      buildPhase = ''
        make ARCH=arm64 CROSS_COMPILE=aarch64-unknown-linux-gnu- defconfig
        make ARCH=arm64 CROSS_COMPILE=aarch64-unknown-linux-gnu- modules_prepare
      '';
      installPhase = ''
        mkdir -p $out
        cp -a . $out/
      '';
      dontFixup = true;
    };
  in {
    packages = { inherit linux-noble-src; };
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
        crossGcc
        # quick script to repeat known failing test to find a good reproducer
        repeatTest
      ];
    };
  });
}
