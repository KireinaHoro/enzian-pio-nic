final: prev: {
  verilator = prev.verilator.overrideAttrs {
    version = "5.048";
    # 5.048's gdb probe uses echo; newer packaging patches a sh probe.
    postPatch = ''
      patchShebangs .
      substituteInPlace bin/verilator --replace-fail "/bin/echo" "${final.coreutils}/bin/echo"
    '';
    src = final.fetchFromGitHub {
      owner = "verilator";
      repo = "verilator";
      tag = "v5.048";
      hash = "sha256-xvqqgbW7L07+NBYzGN2KLhwir58ByShxo4VVPI3pgZk=";
    };
  };
}
