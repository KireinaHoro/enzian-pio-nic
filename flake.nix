{
  description = "devShell for Lauberhorn";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-utils.url = "github:numtide/flake-utils";
    nixGL = {
      url = "github:nix-community/nixGL";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };

  outputs = inputs@{ nixpkgs, flake-utils, nixGL, ... }:
  with builtins;
  with nixpkgs.lib;
  flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-darwin" ] (system: let
    pkgs = import nixpkgs { inherit system; };
    millw = pkgs.stdenv.mkDerivation {
      name = "millw";
      src = pkgs.fetchurl {
          url = "https://raw.githubusercontent.com/com-lihaoyi/mill/f5d0c9f87ac58795323904c2cce44c105e652b50/mill";
          hash = "sha256-yXCQ5YR0dOxjvC3beZDF0O3pVUXbZIyeKiEwU1nxWEw=";
      };
      phases = [ "installPhase" ];
      installPhase = ''
        mkdir -p $out/bin
        cp $src $out/bin/mill
        chmod +x $out/bin/mill
      '';
    };
    wrapNixGL = name: let
      glWrapper = "${nixGL.packages.${system}.nixGLIntel}/bin/nixGLIntel";
      progPath = "${pkgs.${name}}/bin/${name}";
    in pkgs.writeShellApplication {
      inherit name;
      text = ''
        if [[ -f /etc/NIXOS ]]; then
          exec ${progPath} "$@"
        else
          exec ${glWrapper} ${progPath} "$@"
        fi
      '';
    };
  in {
    devShells.default  = with pkgs; mkShell {
      buildInputs = [
        zlib.dev verilator clang
        (wrapNixGL "surfer")
        sby yices
        jdk millw
        # cross compiler
        pkgsCross.aarch64-multiplatform.buildPackages.gcc
      ];
    };
  });
}
