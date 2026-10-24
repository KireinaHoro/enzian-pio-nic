{ stdenv, pkg-config, runtime, tcp, source,
  tcpConnections ? 64, tcpBuffer ? 4096, tcpHoldMs ? 200,
  tcpGateMs ? 5000, tcpStateUs ? 1000,
  deadlineArmMs ? 100, deadlineTimeoutMs ? 5000 }:
stdenv.mkDerivation {
  pname = "lauberhorn-transport-validation";
  version = "1";
  src = source;
  nativeBuildInputs = [ pkg-config ];
  buildInputs = [ runtime tcp ];
  dontConfigure = true;
  makeFlags = [
    "-C" "sw/tests/transport" "PREFIX=$(out)"
    "TCP_CONNECTIONS=${toString tcpConnections}" "TCP_BUFFER=${toString tcpBuffer}"
    "TCP_HOLD_MS=${toString tcpHoldMs}" "TCP_GATE_MS=${toString tcpGateMs}"
    "TCP_STATE_US=${toString tcpStateUs}" "TCP_SOCKET_LIMIT=${toString tcp.sockets}"
    "DEADLINE_ARM_MS=${toString deadlineArmMs}" "DEADLINE_TIMEOUT_MS=${toString deadlineTimeoutMs}"
  ];
  enableParallelBuilding = true;
  dontStrip = true;
}
