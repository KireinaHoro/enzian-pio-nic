# Standalone packaging: no Lauberhorn runtime, driver, or FPGA dependency.
{ native ? false }:
let
  lock = builtins.fromJSON (builtins.readFile ../../../flake.lock);
  pkgs = import (builtins.fetchTree lock.nodes.nixpkgs.locked) { system = "x86_64-linux"; };
  target = if native then pkgs else pkgs.pkgsCross.aarch64-multiplatform;
  craneSource = builtins.fetchTree lock.nodes.crane.locked;
  crane = import craneSource { pkgs = target; };
  baseline = builtins.fetchTree {
    type = "github";
    owner = "eth-easl";
    repo = "dandelion";
    rev = "98c4968017c050391475a20da8e30bbabf6ea618";
    narHash = "sha256-i8x2bm3cLma4fCu/7eTOlFRJKs36muiLsQoJxQ/VoPQ=";
  };
  artifact = builtins.fetchTree {
    type = "github";
    owner = "eth-easl";
    repo = "dandelion_sosp_ae";
    rev = "2a8e23d0aa641f9de91cbc35723ca2c6cd08536e";
    narHash = "sha256-18ptsZPTmril6nAJ7o8sfKnZeD5YDvORSMZQUEY5K5Y=";
  };
  # The upstream workspace lacks a root lock. Pin resolution without changing
  # any implementation, fixture, or manifest from the requested revision.
  src = pkgs.runCommand "dandelion-baseline-source" { } ''
    cp -r ${baseline} $out
    chmod u+w $out
    cp ${./Cargo.lock} $out/Cargo.lock
  '';
  common = {
    inherit src;
    version = "98c4968";
    strictDeps = true;
    doCheck = false;
    nativeBuildInputs = [ pkgs.pkg-config ];
    buildInputs = [ target.openssl ];
    PKG_CONFIG_ALLOW_CROSS = "1";
    CARGO_PROFILE = "release";
  };
  workerArgs = common // {
    pname = "dandelion-baseline-worker";
    cargoExtraArgs = "-p machine_interface --bin mmu_worker --features mmu";
    nativeBuildInputs = common.nativeBuildInputs ++ [ pkgs.llvmPackages.lld ];
    # An explicit target keeps crt-static off build-host proc-macro crates,
    # including when building the optional native smoke-test executables.
    CARGO_BUILD_TARGET = target.stdenv.hostPlatform.rust.rustcTarget;
    # Same static-worker flags as the paper artifact's AArch64 recipe.
    RUSTFLAGS = "-C target-feature=+crt-static -L native=${target.glibc.static}/lib"
      + pkgs.lib.optionalString target.stdenv.hostPlatform.isAarch64
        " -C link-arg=-Wl,-fuse-ld=lld,--image-base=0xaaaaaaaa0000";
  };
  worker = crane.buildPackage (workerArgs // {
    cargoArtifacts = crane.buildDepsOnly workerArgs;
  });
  mkServer = timestamps: let
    args = common // {
      pname = "dandelion-baseline-server" + pkgs.lib.optionalString timestamps "-timestamps";
      # Upstream invokes workspace-wide features: dispatcher has its own
      # mmu/reqwest_io gates, not enabled by server package selection alone.
      cargoExtraArgs = "--bin dandelion_server --features mmu,reqwest_io"
        + pkgs.lib.optionalString timestamps ",timestamp,archive";
      nativeBuildInputs = common.nativeBuildInputs ++ [ target.makeWrapper ];
    };
  in crane.buildPackage (args // {
    cargoArtifacts = crane.buildDepsOnly args;
    postInstall = ''
      wrapProgram $out/bin/dandelion_server --set-default PROCESS_WORKER_PATH ${worker}/bin/mmu_worker
    '';
  });
  server = mkServer false;
  serverTimestamps = mkServer true;
  clientSrc = pkgs.lib.cleanSourceWith {
    src = "${artifact}/client";
    filter = path: type: builtins.elem (baseNameOf path) [ "Cargo.toml" "Cargo.lock" ]
      || pkgs.lib.hasInfix "/src" path;
  };
  clientArgs = {
    src = clientSrc;
    pname = "dandelion-paper-rload";
    version = "2a8e23d";
    strictDeps = true;
    doCheck = false;
    cargoExtraArgs = "--bin rload";
    CARGO_PROFILE = "release";
  };
  client = crane.buildPackage (clientArgs // {
    cargoArtifacts = crane.buildDepsOnly clientArgs;
  });
  fixtures = pkgs.runCommand "dandelion-baseline-fixtures" { } ''
    mkdir -p $out/share/dandelion-baseline
    cp ${baseline}/machine_interface/tests/data/test_elf_mmu_${target.stdenv.hostPlatform.parsed.cpu.name}_matmul $out/share/dandelion-baseline/matmul
    cp ${baseline}/README.md $out/share/dandelion-baseline/upstream-README.md
    cp ${artifact}/doe-suite-config/designs/load_latency_matmul.yml $out/share/dandelion-baseline/
  '';
  python = target.python3.withPackages (p: [ p.pymongo ]);
  runner = pkgs.writeTextFile {
    name = "dandelion-baseline-runner";
    destination = "/bin/dandelion-baseline";
    executable = true;
    text = pkgs.lib.replaceStrings
      [ "@bash@" "@server@" "@serverTimestamps@" "@client@" "@fixtures@" "@python@" "@diagnostic@" "@coreutils@" ]
      (map toString [ target.bash server serverTimestamps client fixtures python "${./diagnostic.py}" target.coreutils ])
      (builtins.readFile ./run.sh);
  };
  manifest = pkgs.writeTextDir "share/dandelion-baseline/manifest.json" (builtins.toJSON {
    server_revision = baseline.rev;
    artifact_revision = artifact.rev;
    server_source_hash = baseline.narHash;
    artifact_source_hash = artifact.narHash;
    server_lock_sha256 = builtins.hashFile "sha256" ./Cargo.lock;
    rust_version = pkgs.rustc.version;
    target = target.stdenv.hostPlatform.rust.rustcTarget;
    worker_rustflags = workerArgs.RUSTFLAGS;
    packages = builtins.mapAttrs (_: toString) { inherit server serverTimestamps worker client fixtures runner; };
    limitations = [
      "Pre-port March 2026 server, not the exact 2025 paper revision."
      "Newly resolved and pinned server dependencies; original artifact client lock retained."
      "Local preparation only: no Enzian deployment or performance result."
    ];
  });
  image = pkgs.callPackage "${pkgs.path}/nixos/lib/make-squashfs.nix" {
    storeContents = [ server serverTimestamps worker client fixtures runner manifest ];
    fileName = "dandelion-upstream-baseline";
  };
in {
  inherit server serverTimestamps worker client fixtures image src runner manifest baseline artifact clientSrc;
  shell = pkgs.mkShell {
    packages = [ pkgs.cargo pkgs.rustc pkgs.python3 pkgs.binutils pkgs.squashfsTools pkgs.bubblewrap pkgs.shellcheck ];
  };
}
