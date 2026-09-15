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
    text = ''
      ci-build eci-inputs .#eciVivadoInputs
      ci-build deploy .#deployFs
      mkdir -p out/eci/generateVerilog.dest
      mv out/ci/deploy/output out/deploy.img
      mv out/ci/eci-inputs/output out/eci/vivado-inputs
      cp -r out/eci/vivado-inputs/generated/. out/eci/generateVerilog.dest/
      test "$(cat out/eci/vivado-inputs/git-revision)" = "$CI_COMMIT_SHA"
    '';
  };
  summarizePhysical = pkgs.writeShellApplication {
    name = "summarize-physical";
    runtimeInputs = [ pkgs.python3 ];
    text = ''exec python3 ${../../tools/physical/summarize.py} "$@"'';
  };

}
