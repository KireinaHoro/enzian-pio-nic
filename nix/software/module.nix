{
  stdenv,
  buildPackages,
  src,
  linuxTools,
  nukeReferences,
  kernelBuildTree,
  kernelRelease,
  devHdrs,
  genVerilog,
}:
stdenv.mkDerivation {
  name = "lauberhorn-kmod";
  version = "0.0.1";
  inherit src;
  nativeBuildInputs = linuxTools ++ [ nukeReferences ];
  buildPhase = ''
    ${buildPackages.stdenv.cc}/bin/cc -std=c11 -Wall -Wextra -Werror \
      -fsanitize=undefined -fno-sanitize-recover=all \
      -Isw/kmod -I${genVerilog.headers} sw/kmod/tests/worker_regs.c \
      -o worker-regs-check
    ./worker-regs-check
    export ARCH=arm64
    export CROSS_COMPILE=aarch64-unknown-linux-gnu-
    export KDIR=${kernelBuildTree}
    cd sw/kmod
    make V=1 LH_BACKEND_VM=0 KERNELRELEASE=${kernelRelease} \
      MACKEREL_DEV_HDRS=${devHdrs} \
      HW_CFG_HDRS=${genVerilog.headers}
    # Cross derivations can skip checkPhase; validate the target ELF here.
    aarch64-unknown-linux-gnu-readelf -p .modinfo lauberhorn.ko \
      | grep -F 'vermagic=${kernelRelease} '
    # The loader recognizes only this exact section. In particular,
    # DEFINE_PER_CPU_READ_MOSTLY can leave an unallocated orphan subsection.
    aarch64-unknown-linux-gnu-readelf -SW lauberhorn.ko > module-sections.txt
    grep -E '\] +\.data\.\.percpu +PROGBITS' module-sections.txt
    if grep -E '\] +\.data\.\.percpu\.' module-sections.txt; then
      echo "Unsupported module per-CPU subsection" >&2
      exit 1
    fi
  '';
  installPhase = ''
    mkdir -p $out
    nuke-refs lauberhorn.ko
    mv lauberhorn.ko $out/
  '';
  dontFixup = true;
}
