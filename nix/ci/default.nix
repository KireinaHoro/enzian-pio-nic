{ pkgs }:
let
  ciBuild = pkgs.writeShellApplication {
    name = "ci-build";
    runtimeInputs = with pkgs; [
      nix
      bash
      coreutils
      findutils
    ];
    text = ''exec bash ${../../tools/ci/nix-build.sh} "$@"'';
  };

in
{
  inherit ciBuild;
  prepareEci = pkgs.writeShellApplication {
    name = "prepare-eci";
    runtimeInputs = [
      ciBuild
      pkgs.coreutils
    ];
    text = ''exec bash ${../../tools/ci/prepare-eci.sh} "$@"'';
  };
  summarizePhysical = pkgs.writeShellApplication {
    name = "summarize-physical";
    runtimeInputs = [ pkgs.python3 ];
    text = ''exec python3 ${../../tools/physical/summarize.py} "$@"'';
  };

}
