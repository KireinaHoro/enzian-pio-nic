# Package applications and test interactively

Human and agent sessions use the same SquashFS and target commands. Linux builds
produce Enzian AArch64 software for Ubuntu `6.8.0-64-generic`; Darwin supports
hardware development/generation, without exporting Linux deployment packages.
Reservation, FPGA programming, file transfer, mounting and remote log collection
remain host operations. Start with the [hardware quickstart](hardware-test.md).

## Package interface

An application repository supplies a root `package.nix` accepting named arguments:

```nix
{ stdenv, pkg-config, lauberhornRuntime }:
stdenv.mkDerivation {
  pname = "my-service";
  version = "0.1";
  src = ./.;
  nativeBuildInputs = [ pkg-config ];
  buildInputs = [ lauberhornRuntime ];
  buildPhase = ''
    $CC $CFLAGS $( $PKG_CONFIG --cflags lauberhorn) main.c \
      $LDFLAGS $( $PKG_CONFIG --libs lauberhorn) -o my-service
  '';
  installPhase = ''
    install -Dm755 my-service $out/bin/my-service
    mkdir -p $out/share/my-service
    cp -r assets/. $out/share/my-service/
  '';
  meta.mainProgram = "my-service";
}
```

The platform supplies the target compiler, libraries and matching runtime through
`platform.callPackage`. Do not select `pkgsCross` inside the application. Runtime
`out/lib/liblauberhorn.so` and `dev/include`/`dev/lib/pkgconfig/lauberhorn.pc` form
the public interface; libtirpc is propagated and declared in pkg-config. Generated
headers are private runtime/module inputs from the same RTL generation. The
[in-tree demo](../../sw/apps/nix-build-demo/package.nix) compiles and links against
the installed public interface, without handwritten ABI declarations.

Return one derivation. Install executables in `$out/bin`, immutable assets in
`$out/share/<application>`, and set `meta.mainProgram` for the default executable.
Resolve assets from their installed paths: execution must work without a checkout
or a particular working directory. Accept writable state, configuration overrides
and log destinations outside `$out` (arguments or documented environment variables).
Optional `passthru.interactiveTests = { smoke = testPackage; };` contains runnable
packages; images include them only when explicitly selected.

## Compose an external repository

In a consumer flake, pin the platform and applications as inputs. Commit the lock
file. Keep substantial applications in their own repositories; hardware libraries
and simple in-tree demos stay here. Example input and output fragments:

```nix
inputs.platform.url = "git+ssh://git@gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform?submodules=1";
inputs.application = { url = "git+https://YOUR-SERVER/YOUR-APPLICATION"; flake = false; };

# Inside outputs, with a build-machine pkgs imported from the pinned nixpkgs:
platform = inputs.platform.lib.mkPlatform { inherit pkgs; };
application = (platform.callPackage (inputs.application + "/package.nix") {}).overrideAttrs (old: {
  passthru = (old.passthru or {}) // { sourceIdentity = {
    revision = inputs.application.rev or null;
    local = !(inputs.application ? rev);
    narHash = inputs.application.narHash or null;
  }; };
});
# Expose these in packages.${system}:
service = application;
image = platform.mkTestImage {
  name = "my-service";
  applications = { my-service = application; };
  extraContents = [];
};
# Expose in devShells.${system}; target compilation, native build tools:
shell = platform.target.mkShell { inputsFrom = [ application ]; };
```

Use a complete flake's usual `let ... in`/output structure around these fragments.
An optional standalone application flake reuses its root recipe. Input source
identity is explicit because a derivation's `src` alone cannot establish its Git
revision or whether a local checkout was used. Absent metadata is recorded as
unknown, never assumed clean. The manifest records source paths and package paths,
platform revision/local status, collateral store identities and kernel release.

Local overrides affect application packages, their shells and images together:

```sh
nix build .#service --override-input application path:/absolute/application --no-write-lock-file
nix develop .#shell --override-input application path:/absolute/application --no-write-lock-file
nix build .#image --override-input application path:/absolute/application --no-write-lock-file
```

Do not change platform or hardware inputs to iterate on an application. Select
`application.interactiveTests.smoke` under a separate image application name when
wanted. `platform.mkDeploymentEnvironment { applications; extraContents = []; }`
constructs just the stable environment; `mkTestImage` includes it and its closure.
Extra packages contribute bin/sbin tools with collision checking; applications
retain all their executables below their own named link.

## One image, helper and manual commands

`nix build .#deployFs -L` builds the microbenchmark image. Existing CI artifact
locations remain `out/deploy.img` and `out/eci/vivado-inputs`.
After checking reservation and matching FPGA/ABI provenance, copy the image:

```sh
rsync -L result enzian-gateway:/scratch/pengxu/deploy.img
# On the CPU, as root; refuses to replace an existing mount:
bash cpu.sh mount /scratch/pengxu/deploy.img
export LH=/nix/store/lauberhorn
export PATH="$LH/bin:$LH/sbin:$PATH"
```

`lauberhorn` links to a hashed environment in the mounted closure. Applications
never install into that stable location themselves.

| Operation | Helper | Manual equivalent |
| --- | --- | --- |
| Inspect | `lh-test info --json` | `jq . "$LH/manifest.json"` |
| Load | `lh-test load` | Check `modinfo -F vermagic "$LH/modules/lauberhorn.ko"` against `uname -r`, ensure module is absent, then `insmod "$LH/modules/lauberhorn.ko"` |
| Configure | `lh-test configure MAC CIDR` | `ip link set lauberhorn0 address MAC`; `ip link set lauberhorn0 mtu 1500`; `ip addr add CIDR dev lauberhorn0`; `ip link set lauberhorn0 up` (helper retries link-up three times) |
| Run | `lh-test run microbenchmarks -- add 4 /scratch/pengxu/run/server.csv` | `"$LH/apps/microbenchmarks/bin/microbenchmarks" add 4 /scratch/pengxu/run/server.csv` |
| Select executable | `lh-test run NAME --executable PROGRAM -- ARGS` | `"$LH/apps/NAME/bin/PROGRAM" ARGS` |

Create a fresh writable result directory before launching. Start Ubuntu's
`rpcbind` service with `systemctl start rpcbind`; inspect with `rpcinfo -p localhost`.
The tools environment includes rpcinfo, IP tools, kmod, mount tools, Bash, jq and
coreutils. Ubuntu owns system services. No helper unloads modules, kills workers,
or replaces a mount. `run` preserves argument boundaries and the program's exit
status. `load` validates kernel vermagic; it does not prove FPGA ABI compatibility.
Check hardware identity with `cpu.sh verify`, then require an externally routed
correctness client. Use `LH_DIRECT=1 cpu serve ...` for the manual application path
in the same test harness. Collect server/client logs from the writable directory
with host-side rsync, along with image hashes and the manifest.

## Dandelion boundary and validation

Dandelion needs Rust/C linkage, installed assets/function registries, configuration
and external writable state. This package contract supports those packaging needs;
its recipe can accept `rustPlatform`, native bindgen tools and target libraries as
named dependencies. Runtime nested-call/fiber APIs in the student port remain
unmerged. No Dandelion build or FPGA functionality is implied: see the
[Dandelion integration notes](../research/dandelion.md).

Build checks and historical board results are separate evidence. The required
board campaign programs compatible downloaded CI artifacts and tests two repeats,
including helper and direct paths. A successful Nix build alone is insufficient.
