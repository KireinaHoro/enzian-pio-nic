{ lib, stdenvNoCC, python3, bash, source, identity }:
stdenvNoCC.mkDerivation {
  pname = "memcached-measurement-tools";
  version = "1";
  src = lib.cleanSource (source + "/tools/memcached");
  dontBuild = true;
  installPhase = ''
    mkdir -p $out/share/memcached $out/bin
    cp *.py $out/share/memcached/
    for command in launch matrix benchmark compatibility ready switching cpu_sample; do
      cat > $out/bin/memcached-$command <<EOF
    #!${bash}/bin/bash
    exec ${python3}/bin/python3 $out/share/memcached/$command.py "\$@"
    EOF
      chmod +x $out/bin/memcached-$command
    done
  '';
  passthru.sourceIdentity = identity;
}
