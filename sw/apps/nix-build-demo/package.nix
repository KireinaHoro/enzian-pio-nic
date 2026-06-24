{
  stdenv,
  pkgsCross,
  lauberhorn-rt,
  hello,
  ...
}:

let
  a64Pkgs = pkgsCross.aarch64-multiplatform;
  crossGcc = a64Pkgs.buildPackages.gcc;
  crossTirpc = a64Pkgs.libtirpc;
in

stdenv.mkDerivation {
  name = "lauberhorn-demo-app-nix";
  src = [ ./test.c ];
  version = "0.0.1";
  phases = [ "buildPhase" "installPhase" ];
  nativeBuildInputs = [ hello crossGcc ];
  buildInputs = [ crossTirpc ];
  buildPhase = ''
    hello --version
    hello
    aarch64-unknown-linux-gnu-gcc $src -L${lauberhorn-rt} -llauberhorn -o test
  '';
  installPhase = ''
    install -Dm755 test $out/bin/test
  '';
}
