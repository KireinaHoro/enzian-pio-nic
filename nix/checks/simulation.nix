{
  stdenv,
  lib,
  mill,
  configure-mill-env-hook,
  verilator,
  cmake,
  zlib,
  libpcap,
  src,
  ivyCache,
  replayPcaps,
  name,
  commands,
}:
# Locked JVM/JNI/simulator inputs; tests execute without network access.
stdenv.mkDerivation {
  name = "lauberhorn-${name}";
  inherit src;
  nativeBuildInputs = [
    mill
    configure-mill-env-hook
    verilator
    cmake
  ];
  dontUseCmakeConfigure = true;
  buildInputs = [
    ivyCache
    zlib
  ];
  LD_LIBRARY_PATH = lib.makeLibraryPath [ libpcap ];
  LAUBERHORN_PCAP_DIR = "${replayPcaps}/data/eci/iladata";
  buildPhase = ''
    runHook preBuild
    ${commands}
    runHook postBuild
  '';
  installPhase = ''
    mkdir -p $out
    find out -name test-report.xml -exec cp --parents '{}' $out/ \;
    echo passed > $out/PASSED
  '';
}
