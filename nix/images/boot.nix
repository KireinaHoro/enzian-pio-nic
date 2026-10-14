# Enzian boot files: stock Ubuntu kernel/userspace, matching patched NIC modules.
# No target execution, host root filesystem, or manually prepared initrd input.
{ pkgs, ubuntu, modules }:
assert modules.kernelRelease == ubuntu.release;
pkgs.runCommand "lauberhorn-boot-${ubuntu.release}" {
  nativeBuildInputs = with pkgs; [
    dpkg e2fsprogs cpio kmod gzip zstd xz lz4 bzip2 lzop util-linux jq
  ];
  passthru = { kernelRelease = ubuntu.release; inherit modules; };
} ''
  mkdir stock extracted root debs "$out"
  tar -xzf ${ubuntu.stockImage} -C stock noble-server-cloudimg-arm64.img
  debugfs -R 'dump /boot/initrd.img-6.8.0-142-generic stock/initrd.img' stock/noble-server-cloudimg-arm64.img
  debugfs -R 'dump /usr/bin/unmkinitramfs stock/unmkinitramfs' stock/noble-server-cloudimg-arm64.img
  test -s stock/initrd.img
  test -s stock/unmkinitramfs
  bash stock/unmkinitramfs stock/initrd.img extracted
  for part in early early2 main; do
    if test -d "extracted/$part"; then cp -a "extracted/$part/." root/; fi
  done
  test -x root/init
  test "$(readlink root/lib)" = usr/lib
  # Keep the stock network/iSCSI boot userspace; never retain its old kernel ABI.
  test -f root/scripts/init-bottom/copymods
  test -f root/scripts/local-top/iscsi
  rm -rf root/usr/lib/modules
  for package in ${ubuntu.image} ${ubuntu.modules} ${ubuntu.modulesExtra}; do
    dpkg-deb -x "$package" debs
  done
  cp -a debs/lib/modules root/usr/lib/
  if test -d debs/lib/firmware; then cp -a debs/lib/firmware/. root/usr/lib/firmware/; fi
  mkdir -p root/usr/lib/modules/${ubuntu.release}/updates/thunderx-ptp root/etc/modprobe.d
  cp ${modules}/lib/modules/${ubuntu.release}/extra/*.ko root/usr/lib/modules/${ubuntu.release}/updates/thunderx-ptp/
  cat > root/etc/modprobe.d/lauberhorn-thunderx-ptp.conf <<'CONF'
  options cavium_ptp fallback_base=0x807000000000
  options nicvf tstmp_bgx_intf=0,4
  CONF
  printf '\ncavium_ptp\nthunder_bgx\nnicvf\n' >> root/conf/modules
  depmod -b "$PWD/root" ${ubuntu.release}
  for module in cavium_ptp nicvf thunder_bgx; do
    selected=$(modinfo -b "$PWD/root" -k ${ubuntu.release} -F filename "$module")
    test "$selected" = "$PWD/root/lib/modules/${ubuntu.release}/updates/thunderx-ptp/$module.ko"
    cmp "$selected" ${modules}/lib/modules/${ubuntu.release}/extra/"$module.ko"
    modinfo -F vermagic "$selected" | grep -F '${ubuntu.release} '
  done
  for module in nicpf iscsi_tcp vfio_pci; do
    modinfo -b "$PWD/root" -k ${ubuntu.release} -F vermagic "$module" | grep -F '${ubuntu.release} '
  done
  test "$(find root/usr/lib/modules -mindepth 1 -maxdepth 1 -type d | wc -l)" = 1
  cp debs/boot/vmlinuz-${ubuntu.release} "$out/vmlinuz"
  # Normalize archive metadata and ordering; retain target executable modes.
  find root -exec touch -h -d @1 {} +
  (cd root; find . -print0 | LC_ALL=C sort -z | cpio --null -o -H newc --reproducible --owner=0:0) | gzip -n -9 > "$out/initrd.img"
  (cd ${modules}/lib/modules/${ubuntu.release}/extra; sha256sum *.ko) > "$out/modules.sha256"
  jq -n --arg kernel '${ubuntu.release}' --arg stock '${ubuntu.stockImage}' \
    --arg image '${ubuntu.image}' --arg modules '${modules}' \
    '{schemaVersion:1,requiredKernelArgs:["copymods=force"],kernelRelease:$kernel,stockImage:$stock,kernelPackage:$image,patchedModules:$modules,ptpFallbackBase:"0x807000000000",txPortSelectors:[0,4]}' > "$out/manifest.json"
  (cd "$out"; sha256sum vmlinuz initrd.img manifest.json modules.sha256 > SHA256SUMS)
''
