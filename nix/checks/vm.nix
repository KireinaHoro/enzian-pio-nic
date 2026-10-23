{
  pkgs,
  vmModule,
  runtimeVm,
  tcpVm,
  nestedFixture,
  memcachedVm,
  source,
  policyTimeoutMs ? 10000,
  policySettleMs ? 30,
  policyArmMs ? 100,
  tcpConnections ? 64,
  tcpBuffer ? 4096,
  tcpHoldMs ? 200,
  tcpStateUs ? 1000,
}:
let
  fixture = pkgs.stdenv.mkDerivation {
    pname = "lauberhorn-vm-fixture";
    version = "0.1";
    src = source;
    nativeBuildInputs = [ pkgs.pkg-config ];
    buildInputs = [ runtimeVm tcpVm ];
    buildPhase = ''
      $CC -O2 -g -pthread -I${runtimeVm.src}/sw/core $(pkg-config --cflags lauberhorn-tcp-runtime) \
        -DTEST_TCP_HOLD_MS=${toString tcpHoldMs} -DTEST_TCP_STATE_US=${toString tcpStateUs} \
        -DTEST_TCP_CONNECTIONS=${toString tcpConnections} -DTEST_TCP_BUFFER=${toString tcpBuffer} \
        sw/tests/vm/tcp.c $(pkg-config --libs lauberhorn-tcp-runtime) -o vm-tcp
      $CC -O2 -g -pthread $(pkg-config --cflags lauberhorn) sw/tests/vm/tcp_transport.c \
        $(pkg-config --libs lauberhorn) -o vm-tcp-transport
      $CC -O2 -g -pthread -I${runtimeVm.src}/sw/core $(pkg-config --cflags lauberhorn) sw/tests/vm/udp.c \
        $(pkg-config --libs lauberhorn) -o vm-udp
      $CC -O2 -g -pthread -I${runtimeVm.src}/sw/core $(pkg-config --cflags lauberhorn) \
        -DTEST_TIMEOUT_MS=${toString policyTimeoutMs} -DTEST_SETTLE_MS=${toString policySettleMs} \
        -DTEST_ARM_MS=${toString policyArmMs} sw/tests/vm/scheduling.c \
        $(pkg-config --libs lauberhorn) -o vm-scheduling
    '';
    installPhase = ''
      install -Dm755 vm-tcp $out/bin/vm-tcp
      install -Dm644 sw/tests/vm/tcp_scheduling.py $out/share/tcp_scheduling.py
      install -Dm644 sw/tests/vm/tcp.py $out/share/tcp.py
      install -Dm755 vm-tcp-transport $out/bin/vm-tcp-transport
      install -Dm755 vm-udp $out/bin/vm-udp
      install -Dm755 vm-scheduling $out/bin/vm-scheduling
      install -Dm644 sw/tests/vm/client.py $out/share/client.py
      install -Dm644 sw/tests/vm/rpc.py $out/share/rpc.py
      install -Dm644 sw/tests/vm/memcached.py $out/share/memcached.py
    '';
  };
in
pkgs.testers.runNixOSTest {
  name = "lauberhorn-vm";
  nodes.machine = { ... }: {
    networking.firewall.enable = false;
    services.rpcbind.enable = true;
    boot.kernelPackages = pkgs.linuxPackages;
    boot.extraModulePackages = [ vmModule ];
    boot.kernelParams = [
      "isolcpus=nohz,domain,managed_irq,2-5"
      "nohz_full=2-5"
      "rcu_nocbs=2-5"
      "irqaffinity=0-1"
      "kthread_cpus=0-1"
    ];
    virtualisation.cores = 6;
    virtualisation.memorySize = 1536;
    environment.systemPackages = [
      fixture
      nestedFixture
      memcachedVm
      pkgs.python3
      pkgs.nftables
    ];
  };
  nodes.peer = { ... }: {
    virtualisation.memorySize = 512;
    environment.systemPackages = [ pkgs.python3 ];
  };
  testScript = ''
    start_all()
    peer.wait_for_unit("multi-user.target")
    machine.wait_for_unit("multi-user.target")
    machine.succeed("modprobe lauberhorn")
    machine.wait_until_succeeds("test -e /dev/lauberhorn")
    machine.succeed("taskset -c 0-1 vm-scheduling > /tmp/scheduling.log 2>&1 || { cat /tmp/scheduling.log; exit 1; }", timeout=60)
    machine.succeed("grep VM_SCHEDULER_POLICY_PASS /tmp/scheduling.log && grep VM_DEADLINE_PASS /tmp/scheduling.log")
    machine.shutdown()
    machine.start()
    machine.wait_for_unit("multi-user.target")
    machine.succeed("modprobe lauberhorn")
    machine.wait_until_succeeds("test -e /dev/lauberhorn")
    machine.succeed("vm-tcp-transport > /tmp/tcp-transport.log 2>&1 &")
    machine.wait_until_succeeds("grep READY /tmp/tcp-transport.log")
    machine.succeed("python3 -c 'import socket, errno; s=socket.socket(); s.settimeout(5); assert s.connect_ex((\"127.0.0.1\", 19500)) == errno.ECONNREFUSED'")
    try:
        machine.wait_until_succeeds("grep TCP_TRANSPORT_PASS /tmp/tcp-transport.log", timeout=10)
    except Exception:
        print(machine.execute("cat /tmp/tcp-transport.log")[1])
        raise
    machine.succeed("vm-udp 19000 4 > /tmp/udp-a.log 2>&1 &")
    machine.succeed("vm-udp 19001 4 > /tmp/udp-b.log 2>&1 &")
    machine.wait_until_succeeds("grep READY /tmp/udp-a.log && grep READY /tmp/udp-b.log")
    machine.succeed("python3 ${fixture}/share/client.py")
    machine.succeed("test $(awk '/^RX/ {print $2}' /tmp/udp-a.log | sort -u | wc -l) -eq 4")
    machine.succeed("LH_VM_STATE_PATH=/tmp/rpc-state.json nested-validation 127.0.0.1 127.0.0.1 > /tmp/rpc.log 2>&1 &")
    machine.wait_until_succeeds("grep VALIDATION_READY /tmp/rpc.log", timeout=30)
    machine.succeed("python3 ${fixture}/share/rpc.py")
    machine.succeed("taskset -c 0-1 memcached -p 0 -U 11211 -t 4 -m 32 -o hashpower=16 -u root > /tmp/memcached.log 2>&1 &")
    machine.wait_until_succeeds("python3 ${fixture}/share/memcached.py", timeout=30)
    peer.succeed("LH_VM_HOST=192.168.1.1 python3 ${fixture}/share/memcached.py")
    peer.succeed("LH_VM_HOST=192.168.1.1 python3 ${fixture}/share/client.py")
    machine.succeed("taskset -c 0-1 vm-tcp 192.168.1.1 19501 > /tmp/tcp.log 2>&1 &")
    machine.wait_until_succeeds("grep TCP_READY /tmp/tcp.log", timeout=30)
    try:
        machine.succeed("python3 ${fixture}/share/tcp.py", timeout=60)
        peer.succeed("python3 ${fixture}/share/tcp.py", timeout=60)
    except Exception:
        print(machine.execute("cat /tmp/tcp.log")[1])
        raise
    machine.succeed("LH_VM_STATE_PATH=/tmp/tcp-a.json taskset -c 0-1 vm-tcp 192.168.1.1 19502 > /tmp/tcp-a.log 2>&1 &")
    machine.succeed("LH_VM_STATE_PATH=/tmp/tcp-b.json taskset -c 0-1 vm-tcp 192.168.1.1 19503 > /tmp/tcp-b.log 2>&1 &")
    machine.wait_until_succeeds("test -s /tmp/tcp-a.json && test -s /tmp/tcp-b.json")
    try:
        machine.succeed("python3 ${fixture}/share/tcp_scheduling.py", timeout=60)
    except Exception:
        print(machine.execute("tail -40 /tmp/tcp-a.log; tail -40 /tmp/tcp-b.log; cat /tmp/tcp-a.json /tmp/tcp-b.json")[1])
        raise
    machine.fail("dmesg | grep -E 'BUG:|Oops:|general protection fault'")
  '';
}
