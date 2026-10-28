{ pkgs, source, appSource, upstreamSource }:
let
  testSource = pkgs.lib.fileset.toSource {
    root = source;
    fileset = pkgs.lib.fileset.unions [
      (source + "/sw/include")
      (source + "/tools/progress/capacity.py")
      (source + "/tools/memcached")
      (pkgs.lib.fileset.fileFilter (f: f.hasExt "py") (source + "/tools/enzian"))
      (pkgs.lib.fileset.fileFilter (f: f.hasExt "py") (source + "/tools/trace"))
    ];
  };
  mockRuntime = pkgs.stdenv.mkDerivation {
    pname = "lauberhorn-memcached-test-transport";
    version = "1";
    src = testSource;
    nativeBuildInputs = [ pkgs.pkg-config ];
    propagatedBuildInputs = [ pkgs.libtirpc ];
    buildPhase = ''
      $CC -shared -fPIC -Wall -Wextra -I sw/include $(pkg-config --cflags libtirpc) \
        tools/memcached/mock-runtime.c -o liblauberhorn.so
    '';
    installPhase = ''
      mkdir -p $out/lib/pkgconfig $out/include
      cp liblauberhorn.so $out/lib/
      cp -r sw/include/. $out/include/
      cat > $out/lib/pkgconfig/lauberhorn.pc <<PC
      prefix=$out
      libdir=$out/lib
      includedir=$out/include
      Name: lauberhorn
      Description: Test only socket transport
      Version: 1
      Requires: libtirpc
      Libs: -L$out/lib -llauberhorn
      Cflags: -I$out/include
      PC
    '';
  };
  adapter = pkgs.callPackage (appSource + "/package.nix") {
    source = appSource;
    lauberhornRuntime = mockRuntime;
  };
  upstream = pkgs.callPackage (appSource + "/package.nix") {
    source = upstreamSource;
    enableLauberhorn = false;
  };
in
pkgs.runCommand "memcached-adapter-compatibility" { nativeBuildInputs = [ (pkgs.python3.withPackages (ps: [ ps.numpy ps.scipy ps.matplotlib ])) ]; } ''
  mkdir -p $out
  python3 ${testSource}/tools/memcached/test_measurements.py
  python3 ${testSource}/tools/memcached/test_breakdown.py
  python3 ${testSource}/tools/memcached/test_switching_trace.py
  python3 ${testSource}/tools/memcached/test_cohort.py --upstream ${upstream}/bin/memcached
  python3 ${testSource}/tools/memcached/check_adapter.py \
    --adapter ${adapter}/bin/memcached --upstream ${upstream}/bin/memcached --output $out
''
