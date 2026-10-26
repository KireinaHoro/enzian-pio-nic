{
  pkgs,
  tlc,
  dcsRom,
  src,
  ivyCache,
  replayPcaps,
  runtimeInterface,
  runtimeTests,
  eciToolkit,
}:
let
  check =
    name: commands:
    pkgs.callPackage ./simulation.nix {
      inherit
        src
        ivyCache
        replayPcaps
        name
        commands
        ;
    };
in
{
  eci-address-map = pkgs.callPackage ./eci-address-map.nix { };
  scheduling-cooperative = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; cooperativeOnly = true; };
  axi-decerr =
    pkgs.runCommand "axi-decerr-regression"
      {
        nativeBuildInputs = [
          pkgs.verilator
          pkgs.gcc
          pkgs.gnumake
          pkgs.bash
          pkgs.python3
        ];
      }
      ''
        bash ${src}/deps/blocks/deps/verilog-axi/tb/axi_crossbar/run_decerr.sh
        touch $out
      '';
  coherence-layers = pkgs.callPackage ./coherence-layers.nix { inherit tlc dcsRom; };
  tla-modules = pkgs.callPackage ./tla-modules.nix { inherit tlc; };
  scheduling-kill-transport = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; killTransportOnly = true; };
  scheduling-full = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; fullOnly = true; };
  scheduling-axi = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; axiOnly = true; };
  scheduling-transport = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; transportOnly = true; };
  scheduling-composed = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; composedOnly = true; };
  scheduling-composed-invariants = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; composedInvariants = true; };
  dcs-rom-thx = pkgs.callPackage ./dcs-rom.nix { inherit dcsRom; };
  dcs-lauberhorn = pkgs.callPackage ./dcs-pressure.nix { inherit dcsRom; };
  dcs-read-pipeline = pkgs.callPackage ./dcs-read-pipeline.nix { };
  dcs-pressure = pkgs.callPackage ./dcs-pressure.nix { };
  scheduling-cache-contracts = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; cacheContractsOnly = true; };
  scheduling-kill-exit = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; killOnly = true; };
  scheduling-races = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; racesOnly = true; };
  scheduling-progress = pkgs.callPackage ./scheduling-progress.nix { inherit tlc; };
  steady-progress = pkgs.callPackage ./steady-progress.nix { inherit tlc; };
  workflow-tools = pkgs.callPackage ./workflow-tools.nix { };
  interactive = pkgs.callPackage ./interactive.nix { };
  eci-toolkit-rx = pkgs.callPackage ./eci-toolkit-rx.nix { inherit eciToolkit; };
  trace-fifo-tests = pkgs.callPackage ./trace-fifo.nix { inherit src; };
  fast-tests-eci = check "fast-tests-eci" ''
    mill --no-daemon --offline gen.test -l org.scalatest.tags.Slow -m lauberhorn.host.eci
    mill --no-daemon --offline gen.test.testOnly \
      lauberhorn.RecoveryStreamBufferSim lauberhorn.RecoveryInFlightSim \
      lauberhorn.ExecutionWatchdogSim lauberhorn.RpcReplyContextsSim
  '';
  transport-pipeline = check "transport-pipeline" ''
    mill --no-daemon --offline gen.test.testOnly \
      lauberhorn.RpcLocalRouteSim lauberhorn.host.eci.NativeTcpSim \
      lauberhorn.host.eci.NativeUdpSim lauberhorn.host.eci.DeadlineSim \
      lauberhorn.host.eci.CooperativeAllocationSim
    mill --no-daemon --offline gen.test.testOnly lauberhorn.host.eci.OncRpcSim \
      -- -t disabled-process-discards-free-reply-contexts
  '';
  scheduler-policy = check "scheduler-policy" ''
    mkdir -p out
    cp ${../../sw/kmod/scheduler_policy.h} out/scheduler_policy.h
    $CC -std=c11 -O2 -Wall -Wextra -Werror -Iout \
      -DLAUBERHORN_NUM_PROCS=16 -DLAUBERHORN_NUM_WORKER_CORES=4 \
      -DLAUBERHORN_RX_PKTS_PER_PROC=16 ${../../sw/tests/scheduler_policy.c} -o out/scheduler-policy
    out/scheduler-policy > out/scheduler-policy-vectors.txt
    export LH_SCHEDULER_VECTORS="$PWD/out/scheduler-policy-vectors.txt"
    mill --no-daemon --offline gen.test.testOnly \
      lauberhorn.SchedulerPolicyAgreementSim lauberhorn.SchedulerAllocationSim \
      lauberhorn.DomainDeadlineSim lauberhorn.DeadlineRegistersSim
  '';
  blocks-tests = check "blocks-tests" ''
    mill --no-daemon --offline 'blocks[2.13.12].test'
  '';
  app-header-pipeline = check "app-header-pipeline" ''
    mill --no-daemon --offline 'blocks[2.13.12].test.testOnly' \
      jsteward.blocks.axi.AxiStreamInjectHeaderEthernetTests \
      jsteward.blocks.axi.AxiStreamInjectHeaderRpcTests
  '';
  app-depth-fanout = check "app-depth-fanout" ''
    mill --no-daemon --offline gen.test.testOnly \
      lauberhorn.host.eci.RxPayloadIsolationSim lauberhorn.host.eci.TxPayloadIsolationSim \
      lauberhorn.host.eci.RxWritebackSim lauberhorn.host.eci.GsyncReleaseSim \
      lauberhorn.host.eci.CooperativeAllocationSim lauberhorn.host.eci.CooperativeYieldSim \
      lauberhorn.RecoveryStreamBufferSim lauberhorn.RecoveryInFlightSim \
      lauberhorn.StreamPipelineBoundarySim lauberhorn.RpcReplyContextsSim \
      lauberhorn.host.eci.EciAddressDecoderSim lauberhorn.host.eci.EciAxiIngressSim
  '';
  trace-autopipeline = check "trace-autopipeline" ''
    mill --no-daemon --offline gen.test.testOnly lauberhorn.TraceAutoPipelineSim
  '';
  trace-tests = check "trace-tests" ''
    mill --no-daemon --offline 'blocks[2.13.12].test.testOnly' jsteward.blocks.misc.TraceBufferDMATests
    mill --no-daemon --offline gen.test.testOnly lauberhorn.LauberhornTraceDumpTests
  '';
}
// pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
  runtime-interface = runtimeInterface;
  runtime-tests = runtimeTests;
}
