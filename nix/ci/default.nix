{ pkgs }:
let
  ciBuild = pkgs.writeShellApplication {
    name = "ci-build";
    meta.mainProgram = "ci-build";
    runtimeInputs = with pkgs; [
      nix
      bash
      coreutils
      findutils
      gnused
    ];
    text = ''exec bash ${../../tools/ci/nix-build.sh} "$@"'';
  };

in
{
  inherit ciBuild;
  prepareEci = pkgs.writeShellApplication {
    name = "prepare-eci";
    meta.mainProgram = "prepare-eci";
    runtimeInputs = [
      ciBuild
      pkgs.coreutils
    ];
    text = ''exec bash ${../../tools/ci/prepare-eci.sh} "$@"'';
  };
  summarizePhysical = pkgs.writeShellApplication {
    name = "summarize-physical";
    meta.mainProgram = "summarize-physical";
    runtimeInputs = [ pkgs.python3 ];
    text = ''exec python3 ${../../tools/physical/summarize.py} "$@"'';
  };

}
