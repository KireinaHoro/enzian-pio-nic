# One ABI pin shared by module compilation and boot-image assembly.
{ pkgs }:
let
  version = "6.8.0-146.146";
  release = "6.8.0-146-generic";
  deb = directory: name: hash: pkgs.fetchurl {
    url = "https://ports.ubuntu.com/ubuntu-ports/pool/main/l/${directory}/${name}_${version}_arm64.deb";
    inherit hash;
  };
in {
  inherit version release;
  headers = deb "linux" "linux-headers-${release}" "sha256-X7cL/HtcOWivT0nwS6ozpiS4O+dzxHv5KzychBF+VlM=";
  image = deb "linux-signed" "linux-image-${release}" "sha256-7jXf/TLGctaxgUwNnIABwWVuBucY69X7FVZdC35R5Bg=";
  modules = deb "linux" "linux-modules-${release}" "sha256-N7PJbX/DdJ7KQtRvqRld4a0TP6QvXgRi2QfCEyuDjD0=";
  modulesExtra = deb "linux" "linux-modules-extra-${release}" "sha256-7ml/QNcU1E6wXXPj/D+avtgiTGQzVAqifJ6LaEOATD4=";
  source = pkgs.fetchgit {
    url = "https://git.launchpad.net/~ubuntu-kernel/ubuntu/+source/linux/+git/noble";
    tag = "Ubuntu-${version}";
    hash = "sha256-vLe50pZYLikSjmuDat7xAh++vqRb9kB1jo0PbprzIHs=";
  };
  # Stock initramfs userspace; its 6.8.0-142 modules are replaced in full.
  stockImage = pkgs.fetchurl {
    url = "https://cloud-images.ubuntu.com/releases/noble/release-20260926/ubuntu-24.04-server-cloudimg-arm64.tar.gz";
    hash = "sha256-GADrVra4OaCMAgVR4W+9jJWydyRS7M43N6mtJf5ZQSY=";
  };
}
