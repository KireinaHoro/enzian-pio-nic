{
  lib,
  runCommand,
  python3,
  bash,
  shellcheck,
  tcl,
}:
let
  source = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      (lib.fileset.fileFilter (f: f.hasExt "sh" || f.hasExt "py") ../../tools/ci)
      ../../tools/physical
      ../../tools/hardware
      ../../tools/enzian/boot.py
      ../../tools/enzian/console.py
      ../../tools/enzian/run.py
      ../../tools/enzian/test.py
      ../../tools/enzian/cpu.sh
      ../../tools/enzian/tests/test_campaign.py
      ../../tools/enzian/tests/test_boot.py
      ../../tools/enzian/tests/test_run.py
      ../../tools/enzian/cooperative_client.py
      ../../tools/enzian/tests/test_cooperative_client.py
      ../../tools/enzian/analyze_rx_holds.py
      ../../tools/enzian/assess_tad.py
      ../../tools/enzian/tests/test_tad.py
      ../../tools/enzian/caladan_activation.py
      ../../tools/enzian/tests/test_caladan_activation.py
      ../../tools/enzian/activation_trace.py
      ../../tools/enzian/activation_breakdown.py
      ../../tools/enzian/tests/test_activation_breakdown.py
      ../../tools/enzian/tests/test_rx_holds.py
      ../../data/eci/sys_trace/dma_decode.py
      ../../data/eci/sys_trace/common.py
      ../../data/eci/sys_trace/sample_window.py
      ../../vivado/eci/container.yml
    ];
  };
in
runCommand "lauberhorn-workflow-tools-tests"
  {
    nativeBuildInputs = [
      (python3.withPackages (ps: [ ps.pexpect ]))
      bash
      shellcheck
      tcl
    ];
  }
  ''
    cp -r ${source}/. .
    shellcheck -x tools/ci/*.sh tools/ci/image/*.sh tools/hardware/*.sh
    tclsh tools/physical/tests/test_trace_latency.tcl
    python3 -m unittest discover -s tools/physical/tests -v
    python3 -m unittest discover -s tools/ci/tests -v
    python3 -m unittest discover -s tools/enzian/tests -p test_boot.py -v
    python3 -m unittest discover -s tools/enzian/tests -p test_run.py -v
    python3 -m unittest discover -s tools/enzian/tests -p test_rx_holds.py -v
    python3 -m unittest discover -s tools/enzian/tests -p test_cooperative_client.py -v
    python3 -m unittest discover -s tools/enzian/tests -p test_campaign.py -v
    python3 -m unittest discover -s tools/enzian/tests -p test_tad.py -v
    python3 -m unittest discover -s tools/enzian/tests -p test_activation_breakdown.py -v
    python3 -m unittest discover -s tools/enzian/tests -p test_caladan_activation.py -v
    touch $out
  ''
