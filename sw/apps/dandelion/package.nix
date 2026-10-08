# Integration adapter for the pinned external snapshot; no target selection here.
{
  lib,
  pkgs,
  crane,
  lauberhornRuntime,
  source,
  makeWrapper,
  pkg-config,
  openssl,
}:
let
  craneLib = crane.mkLib pkgs;
  src = pkgs.applyPatches {
    name = "dandelion-lauberhorn-source";
    src = source;
    patches = [
      ./0001-registered-endpoint-and-worker-placement.patch
      ./0002-configurable-state-directory.patch
      ./0003-explicit-request-outcomes.patch
      ./0004-cooperative-worker-pool.patch
      ./0005-portable-bounded-test-client.patch
    ];
  };
  target = pkgs.stdenv.hostPlatform.rust.rustcTarget;
  sourceIdentity = {
    revision = source.rev or null;
    local = !(source ? rev);
    narHash = source.narHash or null;
  };
  commonArgs = {
    inherit src;
    pname = "dandelion";
    version = "0.0.1";
    strictDeps = true;
    nativeBuildInputs = [
      pkg-config
      makeWrapper
    ];
    buildInputs = [
      lauberhornRuntime
      openssl
    ];
    cargoExtraArgs = "--features mmu --package dandelion_server --package dandelion_lauberhorn --package machine_interface --bin latency --bin dandelion_lauberhorn --bin mmu_worker --bin dandelion_server";
    RUSTFLAGS = "-C link-arg=-L${lib.getLib lauberhornRuntime}/lib -C link-arg=-Wl,-rpath,${lib.getLib lauberhornRuntime}/lib";
    CARGO_PROFILE = "release";
    PKG_CONFIG_ALLOW_CROSS = "1";
  };
  cargoArtifacts = craneLib.buildDepsOnly commonArgs;
  codecTests = craneLib.mkCargoDerivation (
    commonArgs
    // {
      inherit cargoArtifacts;
      pname = "dandelion-codec-tests";
      # Cross stdenv disables checkPhase even with a runner. Execute this
      # bounded codec-only test explicitly, never the hardware integration suite.
      buildPhaseCargoCommand = "cargoWithProfile test --features mmu --package dandelion_lauberhorn --lib capacity_tests";
      doCheck = false;
      installPhase = "mkdir -p $out; echo passed > $out/result";
    }
    // lib.optionalAttrs (!(pkgs.stdenv.buildPlatform.canExecute pkgs.stdenv.hostPlatform)) {
      "CARGO_TARGET_${lib.toUpper (builtins.replaceStrings [ "-" ] [ "_" ] target)}_RUNNER" =
        pkgs.stdenv.hostPlatform.emulator pkgs.buildPackages;
    }
  );
  tests = craneLib.cargoBuild (
    commonArgs
    // {
      inherit cargoArtifacts;
      pname = "dandelion-lauberhorn-tests";
      cargoExtraArgs = "--features mmu --package dandelion_lauberhorn --test server_tests";
      installPhase = ''
        mkdir -p $out/bin $out/share/dandelion-tests/binaries
        found=0
        for test in target/${target}/release/deps/server_tests-*; do
          if test -f "$test" && test -x "$test"; then
            install -m755 "$test" $out/bin/server-tests
            found=$((found + 1))
          fi
        done
        test "$found" -eq 1
        cp -r machine_interface/tests/data/. $out/share/dandelion-tests/binaries/
        cp dandelion_lauberhorn/tests/config.sh $out/share/dandelion-tests/
        substituteInPlace $out/share/dandelion-tests/config.sh \
          --replace-fail './binaries' "$out/share/dandelion-tests/binaries"
        wrapProgram $out/bin/server-tests \
          --set-default DANDELION_TEST_BINARY_PATH $out/share/dandelion-tests/binaries \
          --set-default DANDELION_TEST_BINARY_ARCH aarch64 \
          --set-default DANDELION_ISOL_TYPE mmu
      '';
      meta.mainProgram = "server-tests";
      passthru = { inherit sourceIdentity; };
    }
  );
in
craneLib.buildPackage (
  commonArgs
  // {
    inherit cargoArtifacts;
    installPhase = ''
      mkdir -p $out/bin $out/share/dandelion
      for program in dandelion_lauberhorn dandelion_server mmu_worker latency; do
        install -m755 target/${target}/release/$program $out/bin/
      done
      cp dandelion_lauberhorn/config.toml $out/share/dandelion/config.toml
      wrapProgram $out/bin/dandelion_server \
        --set-default PROCESS_WORKER_PATH $out/bin/mmu_worker
      wrapProgram $out/bin/dandelion_lauberhorn \
        --set-default PROCESS_WORKER_PATH $out/bin/mmu_worker \
        --set-default DANDELION_LAUBERHORN_CONFIG $out/share/dandelion/config.toml
    '';
    meta.mainProgram = "dandelion_lauberhorn";
    passthru = {
      inherit sourceIdentity codecTests;
      interactiveTests.smoke = tests;
    };
  }
)
