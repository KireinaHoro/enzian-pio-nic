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
          url = "https://raw.githubusercontent.com/com-lihaoyi/mill/f5d0c9f87ac58795323904c2cce44c105e652b50/mill";
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
  in {
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
        # cross compiler
        pkgsCross.aarch64-multiplatform.buildPackages.gcc
        # quick script to repeat known failing test to find a good reproducer
        repeatTest
      ];
    };
  });
}
