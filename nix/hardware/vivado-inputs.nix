{
  lib,
  fetchurl,
  runCommand,
  source,
  genVerilog,
  gitRev,
}:
let
  # Same release as build.mill's interactive ECI flow, now content-addressed.
  staticShell = fetchurl {
    url = "https://gitlab.ethz.ch/api/v4/projects/48046/packages/generic/release/v0.1.5/build_static_shell_routed.dcp";
    hash = "sha256-su5Siz64ptILrELlby5iV+tD2pU0FJH2u8IfrM4y+rE=";
  };
in
runCommand "lauberhorn-eci-vivado-inputs" { } ''
  mkdir -p $out/vivado $out/deps/blocks/deps $out/generated $out/static-shell $out/tools/physical
  cp -r ${lib.cleanSource (source + "/vivado/eci")} $out/vivado/eci
  for dep in verilog-axis verilog-axi; do
    cp -r ${source}/deps/blocks/deps/$dep $out/deps/blocks/deps/$dep
  done
  cp -r ${genVerilog}/. ${genVerilog.headers}/. ${genVerilog.devices}/. $out/generated/
  cp ${staticShell} $out/static-shell/static_shell_routed.dcp
  cp ${source}/tools/physical/checkpoint.tcl $out/tools/physical/
  cp ${source}/flake.lock $out/flake.lock
  printf '%s\n' '${gitRev}' > $out/git-revision
  printf '%s\n' '${builtins.unsafeDiscardStringContext genVerilog.drvPath}' > $out/rtl-derivation
  # Materialize internal links for portable GitLab ZIP extraction. Omit dangling
  # links in unused DCS examples; Vivado validates the actual project sources.
  chmod -R u+w $out
  while IFS= read -r -d "" link; do
    target=$(realpath -m "$link")
    case "$target" in
      "$out"/*) ;;
      *) echo "Source link escapes the bundle: $link" >&2; exit 1 ;;
    esac
    if test -e "$target"; then
      cp --remove-destination "$target" "$link"
    else
      rm "$link"
    fi
  done < <(find $out -type l -print0)
''
