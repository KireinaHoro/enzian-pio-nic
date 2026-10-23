{
  pkgs,
  vmModule,
  runtimeVm,
  nestedFixture,
  memcachedVm,
  source,
  policyTimeoutMs ? 10000,
  policySettleMs ? 30,
  policyArmMs ? 100,
}:
let
  fixture = pkgs.stdenv.mkDerivation {
    pname = "lauberhorn-vm-fixture";
    version = "0.1";
    src = source;
    nativeBuildInputs = [ pkgs.pkg-config ];
    buildInputs = [ runtimeVm ];
    buildPhase = ''
      $CC -O2 -g -pthread -I${runtimeVm.src}/sw/core $(pkg-config --cflags lauberhorn) sw/tests/vm/udp.c \
        $(pkg-config --libs lauberhorn) -o vm-udp
      $CC -O2 -g -pthread -I${runtimeVm.src}/sw/core $(pkg-config --cflags lauberhorn) \
        -DTEST_TIMEOUT_MS=${toString policyTimeoutMs} -DTEST_SETTLE_MS=${toString policySettleMs} \
        -DTEST_ARM_MS=${toString policyArmMs} sw/tests/vm/scheduling.c \
        $(pkg-config --libs lauberhorn) -o vm-scheduling
    '';
    installPhase = ''
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
    machine.fail("dmesg | grep -E 'BUG:|Oops:|general protection fault'")
  '';
}
