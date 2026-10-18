let
  current = builtins.getFlake "git+file:///local/home/pengxu/work-local/lauberhorn/platform";
  baseline = builtins.getFlake "git+file:///local/home/pengxu/work-local/lauberhorn/platform?rev=0e58f0582b6f0039ceeff2c4714ceb4451cca490&submodules=1";
  runtime = baseline.packages.x86_64-linux.runtime;
in assert toString runtime == "/nix/store/inr7lnnk4yh0bflln45hvk786p3c69sv-lauberhorn-rt-aarch64-unknown-linux-gnu-0.0.1";
current.dev.x86_64-linux.software.microbenchmarks.override { lauberhornRuntime = runtime; }
