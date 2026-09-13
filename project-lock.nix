{ fetchurl }:
let
  fetchMaven =
    {
      name,
      urls,
      hash,
      installPath,
    }:
    with builtins;
    let
      firstUrl = head urls;
      otherUrls = filter (elem: elem != firstUrl) urls;
    in
    fetchurl {
      inherit name hash;
      passthru = { inherit installPath; };
      url = firstUrl;
      recursiveHash = true;
      downloadToTemp = true;
      postFetch = ''
        mkdir -p "$out"
        cp -v "$downloadedFile" "$out/${baseNameOf firstUrl}"
      ''
      + concatStringsSep "\n" (
        map (
          elem:
          let
            filename = baseNameOf elem;
          in
          ''
            downloadedFile=$TMPDIR/${filename}
            tryDownload ${elem} "$downloadedFile"
            cp -v "$TMPDIR/${filename}" "$out/"
          ''
        ) otherUrls
      );
    };
in
{

  "commons-codec_commons-codec-1.19.0" = fetchMaven {
    name = "commons-codec_commons-codec-1.19.0";
    urls = [
      "https://repo1.maven.org/maven2/commons-codec/commons-codec/1.19.0/commons-codec-1.19.0.jar"
      "https://repo1.maven.org/maven2/commons-codec/commons-codec/1.19.0/commons-codec-1.19.0.pom"
    ];
    hash = "sha256-Tctia3qzKymGgRNoH+mVSjTp3wPVbX8HIevxQz6Qz5g=";
    installPath = "https/repo1.maven.org/maven2/commons-codec/commons-codec/1.19.0";
  };

  "commons-io_commons-io-2.11.0" = fetchMaven {
    name = "commons-io_commons-io-2.11.0";
    urls = [
      "https://repo1.maven.org/maven2/commons-io/commons-io/2.11.0/commons-io-2.11.0.jar"
      "https://repo1.maven.org/maven2/commons-io/commons-io/2.11.0/commons-io-2.11.0.pom"
    ];
    hash = "sha256-ZHSzUoNO6aoG6rwFT3Ok5gpjh44wXvFQvcd3d41TrPA=";
    installPath = "https/repo1.maven.org/maven2/commons-io/commons-io/2.11.0";
  };

  "commons-io_commons-io-2.15.1" = fetchMaven {
    name = "commons-io_commons-io-2.15.1";
    urls = [
      "https://repo1.maven.org/maven2/commons-io/commons-io/2.15.1/commons-io-2.15.1.jar"
      "https://repo1.maven.org/maven2/commons-io/commons-io/2.15.1/commons-io-2.15.1.pom"
    ];
    hash = "sha256-MsTN0cAvO/K4XWJ5p0WNyGZWOSL8OmBNuaKQ1JouvGM=";
    installPath = "https/repo1.maven.org/maven2/commons-io/commons-io/2.15.1";
  };

  "commons-io_commons-io-2.22.0" = fetchMaven {
    name = "commons-io_commons-io-2.22.0";
    urls = [
      "https://repo1.maven.org/maven2/commons-io/commons-io/2.22.0/commons-io-2.22.0.jar"
      "https://repo1.maven.org/maven2/commons-io/commons-io/2.22.0/commons-io-2.22.0.pom"
    ];
    hash = "sha256-llK0u+GUZ1PfJMMBAgpiPbQTxPXj8USIwisBXUR4VVg=";
    installPath = "https/repo1.maven.org/maven2/commons-io/commons-io/2.22.0";
  };

  "com.azure_azure-sdk-bom-1.3.7" = fetchMaven {
    name = "com.azure_azure-sdk-bom-1.3.7";
    urls = [ "https://repo1.maven.org/maven2/com/azure/azure-sdk-bom/1.3.7/azure-sdk-bom-1.3.7.pom" ];
    hash = "sha256-ML0aa3sCg/K+7/EpmwAFYQllmocKEw3UFlQnbLSzQkA=";
    installPath = "https/repo1.maven.org/maven2/com/azure/azure-sdk-bom/1.3.7";
  };

  "com.eed3si9n_shaded-jawn-parser_3-1.3.2" = fetchMaven {
    name = "com.eed3si9n_shaded-jawn-parser_3-1.3.2";
    urls = [
      "https://repo1.maven.org/maven2/com/eed3si9n/shaded-jawn-parser_3/1.3.2/shaded-jawn-parser_3-1.3.2.jar"
      "https://repo1.maven.org/maven2/com/eed3si9n/shaded-jawn-parser_3/1.3.2/shaded-jawn-parser_3-1.3.2.pom"
    ];
    hash = "sha256-pGLVZWs9cfxuwKYy+XhLZcEQV9Ong5YKuV6zaMX0TWM=";
    installPath = "https/repo1.maven.org/maven2/com/eed3si9n/shaded-jawn-parser_3/1.3.2";
  };

  "com.eed3si9n_shaded-scalajson_3-1.0.0-M4" = fetchMaven {
    name = "com.eed3si9n_shaded-scalajson_3-1.0.0-M4";
    urls = [
      "https://repo1.maven.org/maven2/com/eed3si9n/shaded-scalajson_3/1.0.0-M4/shaded-scalajson_3-1.0.0-M4.jar"
      "https://repo1.maven.org/maven2/com/eed3si9n/shaded-scalajson_3/1.0.0-M4/shaded-scalajson_3-1.0.0-M4.pom"
    ];
    hash = "sha256-9w1IZvK5lwswQfQiwfwZJDiQaT7a0XvKKWK+pfkh/co=";
    installPath = "https/repo1.maven.org/maven2/com/eed3si9n/shaded-scalajson_3/1.0.0-M4";
  };

  "com.eed3si9n_sjson-new-core_3-0.10.1" = fetchMaven {
    name = "com.eed3si9n_sjson-new-core_3-0.10.1";
    urls = [
      "https://repo1.maven.org/maven2/com/eed3si9n/sjson-new-core_3/0.10.1/sjson-new-core_3-0.10.1.pom"
    ];
    hash = "sha256-g0aETJA9YwthKanzoO2q/g+6SH31bJsaqRZu+x/0zqg=";
    installPath = "https/repo1.maven.org/maven2/com/eed3si9n/sjson-new-core_3/0.10.1";
  };

  "com.eed3si9n_sjson-new-core_3-0.14.0-M5" = fetchMaven {
    name = "com.eed3si9n_sjson-new-core_3-0.14.0-M5";
    urls = [
      "https://repo1.maven.org/maven2/com/eed3si9n/sjson-new-core_3/0.14.0-M5/sjson-new-core_3-0.14.0-M5.jar"
      "https://repo1.maven.org/maven2/com/eed3si9n/sjson-new-core_3/0.14.0-M5/sjson-new-core_3-0.14.0-M5.pom"
    ];
    hash = "sha256-BpUaTTdi6wOjsXGvKl5e6tMTf/YfGpA4wIVmRITaSsU=";
    installPath = "https/repo1.maven.org/maven2/com/eed3si9n/sjson-new-core_3/0.14.0-M5";
  };

  "com.eed3si9n_sjson-new-scalajson_3-0.14.0-M5" = fetchMaven {
    name = "com.eed3si9n_sjson-new-scalajson_3-0.14.0-M5";
    urls = [
      "https://repo1.maven.org/maven2/com/eed3si9n/sjson-new-scalajson_3/0.14.0-M5/sjson-new-scalajson_3-0.14.0-M5.jar"
      "https://repo1.maven.org/maven2/com/eed3si9n/sjson-new-scalajson_3/0.14.0-M5/sjson-new-scalajson_3-0.14.0-M5.pom"
    ];
    hash = "sha256-T8lJPOwU3RCsy3e85D/yfAmZzIUhB+n+dK/S5m0i4JY=";
    installPath = "https/repo1.maven.org/maven2/com/eed3si9n/sjson-new-scalajson_3/0.14.0-M5";
  };

  "com.fasterxml_oss-parent-69" = fetchMaven {
    name = "com.fasterxml_oss-parent-69";
    urls = [ "https://repo1.maven.org/maven2/com/fasterxml/oss-parent/69/oss-parent-69.pom" ];
    hash = "sha256-/LRxt7QkFlgBi48aGOZGIrHfNUC9cwqkbisbV7NGgqc=";
    installPath = "https/repo1.maven.org/maven2/com/fasterxml/oss-parent/69";
  };

  "com.fasterxml_oss-parent-75" = fetchMaven {
    name = "com.fasterxml_oss-parent-75";
    urls = [ "https://repo1.maven.org/maven2/com/fasterxml/oss-parent/75/oss-parent-75.pom" ];
    hash = "sha256-ByQEet4c8ay63MBnJiPijJAsMG82rLp0B7f7XfNSzVA=";
    installPath = "https/repo1.maven.org/maven2/com/fasterxml/oss-parent/75";
  };

  "com.lihaoyi_fansi_3-0.5.1" = fetchMaven {
    name = "com.lihaoyi_fansi_3-0.5.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/fansi_3/0.5.1/fansi_3-0.5.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/fansi_3/0.5.1/fansi_3-0.5.1.pom"
    ];
    hash = "sha256-1OUhN7HxHQeI8uOzAZr1tMi2z7+LvOEJ+j9BikcSQJo=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/fansi_3/0.5.1";
  };

  "com.lihaoyi_fastparse_3-3.1.1" = fetchMaven {
    name = "com.lihaoyi_fastparse_3-3.1.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/fastparse_3/3.1.1/fastparse_3-3.1.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/fastparse_3/3.1.1/fastparse_3-3.1.1.pom"
    ];
    hash = "sha256-iz6Wj92asaujz93RjmBAaKHHV64HS26cduPsQzaD6wM=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/fastparse_3/3.1.1";
  };

  "com.lihaoyi_geny_2.13-1.0.0" = fetchMaven {
    name = "com.lihaoyi_geny_2.13-1.0.0";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.0.0/geny_2.13-1.0.0.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.0.0/geny_2.13-1.0.0.pom"
    ];
    hash = "sha256-NV0rvNt5hyo27OvkyQVogEYEpZsP8Vmxt+RIHFejVYI=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.0.0";
  };

  "com.lihaoyi_geny_2.13-1.1.0" = fetchMaven {
    name = "com.lihaoyi_geny_2.13-1.1.0";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.0/geny_2.13-1.1.0.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.0/geny_2.13-1.1.0.pom"
    ];
    hash = "sha256-z9oB4D+MOO9BqE/1pf/E4NGbxHTHsoS9N9TPW/7ofA4=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.0";
  };

  "com.lihaoyi_geny_2.13-1.1.1" = fetchMaven {
    name = "com.lihaoyi_geny_2.13-1.1.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1/geny_2.13-1.1.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1/geny_2.13-1.1.1.pom"
    ];
    hash = "sha256-+gQ8X4oSRU30RdF5kE2Gn8nxmo3RJEShiEyyzUJd088=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1";
  };

  "com.lihaoyi_geny_3-1.0.0" = fetchMaven {
    name = "com.lihaoyi_geny_3-1.0.0";
    urls = [ "https://repo1.maven.org/maven2/com/lihaoyi/geny_3/1.0.0/geny_3-1.0.0.pom" ];
    hash = "sha256-gyZV3FMH1nlWfPJ0nAN3y08zzWtW4AYPo1A3oaMraUY=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/geny_3/1.0.0";
  };

  "com.lihaoyi_geny_3-1.1.1" = fetchMaven {
    name = "com.lihaoyi_geny_3-1.1.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_3/1.1.1/geny_3-1.1.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/geny_3/1.1.1/geny_3-1.1.1.pom"
    ];
    hash = "sha256-DtsM1VVr7WxRM+YRjjVDOkfCqXzp2q9FwlSMgoD/+ow=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/geny_3/1.1.1";
  };

  "com.lihaoyi_mainargs_2.13-0.5.4" = fetchMaven {
    name = "com.lihaoyi_mainargs_2.13-0.5.4";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mainargs_2.13/0.5.4/mainargs_2.13-0.5.4.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mainargs_2.13/0.5.4/mainargs_2.13-0.5.4.pom"
    ];
    hash = "sha256-HSbwcll8iJWf4r5HKk7KO8OE/Tn2qQp+5HKFJ821hfY=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mainargs_2.13/0.5.4";
  };

  "com.lihaoyi_mainargs_3-0.7.8" = fetchMaven {
    name = "com.lihaoyi_mainargs_3-0.7.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mainargs_3/0.7.8/mainargs_3-0.7.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mainargs_3/0.7.8/mainargs_3-0.7.8.pom"
    ];
    hash = "sha256-7s+2mHx7lZqNBQSHcR7CLAclPXnDlE/TrnWIofLa7bk=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mainargs_3/0.7.8";
  };

  "com.lihaoyi_mill-core-api-daemon_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-api-daemon_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-api-daemon_3/1.1.8/mill-core-api-daemon_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-api-daemon_3/1.1.8/mill-core-api-daemon_3-1.1.8.pom"
    ];
    hash = "sha256-HY047oPFK95UxdDEY5lr1vJq7ChW+XqbWnujpmaSXjQ=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-api-daemon_3/1.1.8";
  };

  "com.lihaoyi_mill-core-api-java11_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-api-java11_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-api-java11_3/1.1.8/mill-core-api-java11_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-api-java11_3/1.1.8/mill-core-api-java11_3-1.1.8.pom"
    ];
    hash = "sha256-Ckt86yYoStMSBKTeFU2MOJb6CbArMveh8b1WtwO00gc=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-api-java11_3/1.1.8";
  };

  "com.lihaoyi_mill-core-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-api_3/1.1.8/mill-core-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-api_3/1.1.8/mill-core-api_3-1.1.8.pom"
    ];
    hash = "sha256-jbcxkuv2Dt0ESPz7LyA04LBrIeeJ9zbqqaUhWdDb6o0=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-api_3/1.1.8";
  };

  "com.lihaoyi_mill-core-constants-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-constants-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-constants/1.1.8/mill-core-constants-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-constants/1.1.8/mill-core-constants-1.1.8.pom"
    ];
    hash = "sha256-yfR4N1/iD0E/rqArAaX5pvkt2vbnYaKwXjw9l/NMyw8=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-constants/1.1.8";
  };

  "com.lihaoyi_mill-core-eval_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-eval_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-eval_3/1.1.8/mill-core-eval_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-eval_3/1.1.8/mill-core-eval_3-1.1.8.pom"
    ];
    hash = "sha256-Ff8abMKErFbCKuK+K3G0UtI3Uzr/jkcfTw6+4es1Zk8=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-eval_3/1.1.8";
  };

  "com.lihaoyi_mill-core-exec_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-exec_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-exec_3/1.1.8/mill-core-exec_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-exec_3/1.1.8/mill-core-exec_3-1.1.8.pom"
    ];
    hash = "sha256-hpHnjohMfWEVwOkq4+lk7pCBsX2uvviCsiA6lMORPuc=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-exec_3/1.1.8";
  };

  "com.lihaoyi_mill-core-internal-cli_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-internal-cli_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-internal-cli_3/1.1.8/mill-core-internal-cli_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-internal-cli_3/1.1.8/mill-core-internal-cli_3-1.1.8.pom"
    ];
    hash = "sha256-v+PQSrY/yG/RlAIBf3ksrhZ4SpMfcjKC7xnHGSq7RmU=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-internal-cli_3/1.1.8";
  };

  "com.lihaoyi_mill-core-internal_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-internal_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-internal_3/1.1.8/mill-core-internal_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-internal_3/1.1.8/mill-core-internal_3-1.1.8.pom"
    ];
    hash = "sha256-UqgHN8kjJabDixRxEVm9ccdXmbr7wgrQR7jYLqZ47xA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-internal_3/1.1.8";
  };

  "com.lihaoyi_mill-core-resolve_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-core-resolve_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-resolve_3/1.1.8/mill-core-resolve_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-core-resolve_3/1.1.8/mill-core-resolve_3-1.1.8.pom"
    ];
    hash = "sha256-QZNuByRPKvXvyY57/Rdjaj6qdRMlrevcUwqHmwOZaZg=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-core-resolve_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-androidlib-databinding_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-androidlib-databinding_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-androidlib-databinding_3/1.1.8/mill-libs-androidlib-databinding_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-androidlib-databinding_3/1.1.8/mill-libs-androidlib-databinding_3-1.1.8.pom"
    ];
    hash = "sha256-jtkM2f4TumDzC9uLI95hIvw10trZ9lOHRfbMOD/uKMY=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-androidlib-databinding_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-androidlib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-androidlib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-androidlib_3/1.1.8/mill-libs-androidlib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-androidlib_3/1.1.8/mill-libs-androidlib_3-1.1.8.pom"
    ];
    hash = "sha256-QH3v++EyokPm4aYplaMExwHxCTauNxWrcWhUdfoXjos=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-androidlib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-daemon-client_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-daemon-client_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-daemon-client_3/1.1.8/mill-libs-daemon-client_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-daemon-client_3/1.1.8/mill-libs-daemon-client_3-1.1.8.pom"
    ];
    hash = "sha256-QgMabb6rpfMUqpAv3t31hOShI2j+F12crN9XAWzRGbw=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-daemon-client_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-daemon-server_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-daemon-server_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-daemon-server_3/1.1.8/mill-libs-daemon-server_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-daemon-server_3/1.1.8/mill-libs-daemon-server_3-1.1.8.pom"
    ];
    hash = "sha256-u8xSCWO6nLB56blPj65DUEqx1cK03eWML3psxoSrN0w=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-daemon-server_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-groovylib-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-groovylib-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-groovylib-api_3/1.1.8/mill-libs-groovylib-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-groovylib-api_3/1.1.8/mill-libs-groovylib-api_3-1.1.8.pom"
    ];
    hash = "sha256-K3UmLRd0M1s+zNAawRClWyOezYMZsE4xrxsT/qP2878=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-groovylib-api_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-groovylib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-groovylib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-groovylib_3/1.1.8/mill-libs-groovylib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-groovylib_3/1.1.8/mill-libs-groovylib_3-1.1.8.pom"
    ];
    hash = "sha256-KAMFCpYNWhH23JE514ii3/7SVdO2sRd3gmikcoby4tE=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-groovylib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-init_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-init_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-init_3/1.1.8/mill-libs-init_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-init_3/1.1.8/mill-libs-init_3-1.1.8.pom"
    ];
    hash = "sha256-TW3XelpTih4nTBkcoC0I5Dx6Y8hz0flpgvphDGosRGY=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-init_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-javalib-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javalib-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-api_3/1.1.8/mill-libs-javalib-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-api_3/1.1.8/mill-libs-javalib-api_3-1.1.8.pom"
    ];
    hash = "sha256-HRqH8MeCLpwtZ7KfuhZzDY5bE6fCtJQfalpuzh5aEuk=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-api_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-javalib-classgraph-worker_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javalib-classgraph-worker_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-classgraph-worker_3/1.1.8/mill-libs-javalib-classgraph-worker_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-classgraph-worker_3/1.1.8/mill-libs-javalib-classgraph-worker_3-1.1.8.pom"
    ];
    hash = "sha256-OvkFMrxzcF0W2qItYCsc6l7OY/YkjWpm/UpmvIUuMow=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-classgraph-worker_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-javalib-jarjarabrams-worker_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javalib-jarjarabrams-worker_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-jarjarabrams-worker_3/1.1.8/mill-libs-javalib-jarjarabrams-worker_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-jarjarabrams-worker_3/1.1.8/mill-libs-javalib-jarjarabrams-worker_3-1.1.8.pom"
    ];
    hash = "sha256-DzlphaufTgmwaUnIch/gKuY6/4+pQnG5qjUMOdILppA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-jarjarabrams-worker_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-javalib-testrunner-entrypoint-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javalib-testrunner-entrypoint-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-testrunner-entrypoint/1.1.8/mill-libs-javalib-testrunner-entrypoint-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-testrunner-entrypoint/1.1.8/mill-libs-javalib-testrunner-entrypoint-1.1.8.pom"
    ];
    hash = "sha256-0jM4422flnZbArejf8dUrbyplqAvePpohiKS4Fnk+mQ=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-testrunner-entrypoint/1.1.8";
  };

  "com.lihaoyi_mill-libs-javalib-testrunner_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javalib-testrunner_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-testrunner_3/1.1.8/mill-libs-javalib-testrunner_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-testrunner_3/1.1.8/mill-libs-javalib-testrunner_3-1.1.8.pom"
    ];
    hash = "sha256-n03+l2hNVvrJbOdbaoaCMgu5qN5clqG7iaGgBf3ZtoY=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-testrunner_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-javalib-worker_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javalib-worker_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-worker_3/1.1.8/mill-libs-javalib-worker_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-worker_3/1.1.8/mill-libs-javalib-worker_3-1.1.8.pom"
    ];
    hash = "sha256-9YXoF5nFHbauJkbg0ND0Njpbgp722YYjT++l5CQYzsE=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib-worker_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-javalib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javalib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib_3/1.1.8/mill-libs-javalib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib_3/1.1.8/mill-libs-javalib_3-1.1.8.pom"
    ];
    hash = "sha256-5umUi4pY/HZ2BQOKlTlIoMk4aqDzzDktQRqPjmdSgZE=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javalib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-javascriptlib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-javascriptlib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javascriptlib_3/1.1.8/mill-libs-javascriptlib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-javascriptlib_3/1.1.8/mill-libs-javascriptlib_3-1.1.8.pom"
    ];
    hash = "sha256-cDpgEHTr4gc3ZZcIoZXg2CrepDpcU7MxnQXdyzXYAts=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-javascriptlib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-kotlinlib-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-kotlinlib-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib-api_3/1.1.8/mill-libs-kotlinlib-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib-api_3/1.1.8/mill-libs-kotlinlib-api_3-1.1.8.pom"
    ];
    hash = "sha256-WKJyb904ZixouuLEpmWrYERwJpSUUP6jt3PuPrTEHyE=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib-api_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-kotlinlib-ksp2-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-kotlinlib-ksp2-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib-ksp2-api_3/1.1.8/mill-libs-kotlinlib-ksp2-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib-ksp2-api_3/1.1.8/mill-libs-kotlinlib-ksp2-api_3-1.1.8.pom"
    ];
    hash = "sha256-2h4t3dVySpVnCJ8Kh4k6GWinPhYIxn5LJLzPRO/jTCA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib-ksp2-api_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-kotlinlib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-kotlinlib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib_3/1.1.8/mill-libs-kotlinlib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib_3/1.1.8/mill-libs-kotlinlib_3-1.1.8.pom"
    ];
    hash = "sha256-jeYtQrPvhUHF7JqPoh/b5K7IlY9GsilqkICzFg+H/qc=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-kotlinlib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-pythonlib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-pythonlib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-pythonlib_3/1.1.8/mill-libs-pythonlib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-pythonlib_3/1.1.8/mill-libs-pythonlib_3-1.1.8.pom"
    ];
    hash = "sha256-tFOurwIG3zPgXG/Rdl+4MoepXja0ONQLfXwaGbTwo5k=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-pythonlib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-rpc_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-rpc_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-rpc_3/1.1.8/mill-libs-rpc_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-rpc_3/1.1.8/mill-libs-rpc_3-1.1.8.pom"
    ];
    hash = "sha256-BhqenlwCHS8HMEOgYdoUVFRoNBiOmz3owqSjiMAht1g=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-rpc_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-scalajslib-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-scalajslib-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalajslib-api_3/1.1.8/mill-libs-scalajslib-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalajslib-api_3/1.1.8/mill-libs-scalajslib-api_3-1.1.8.pom"
    ];
    hash = "sha256-Nt8UNe9qgCIHS4JV2IG2SPErh6yWAGRvKy7oskWfh/w=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalajslib-api_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-scalajslib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-scalajslib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalajslib_3/1.1.8/mill-libs-scalajslib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalajslib_3/1.1.8/mill-libs-scalajslib_3-1.1.8.pom"
    ];
    hash = "sha256-Av7SR3V8dDtNrAK4L+SCbXCQaJAJkl+qPyzlWxFKASs=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalajslib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-scalalib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-scalalib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalalib_3/1.1.8/mill-libs-scalalib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalalib_3/1.1.8/mill-libs-scalalib_3-1.1.8.pom"
    ];
    hash = "sha256-MpdUbnNxvNzzRpG3NwCSbGqyrxO5XZgUA2g5J4TDrR0=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalalib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-scalanativelib-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-scalanativelib-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalanativelib-api_3/1.1.8/mill-libs-scalanativelib-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalanativelib-api_3/1.1.8/mill-libs-scalanativelib-api_3-1.1.8.pom"
    ];
    hash = "sha256-nAItZFmcQZkBzQa2CkyecbKbFSSvbAv4yF+ZjlfQjlI=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalanativelib-api_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-scalanativelib_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-scalanativelib_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalanativelib_3/1.1.8/mill-libs-scalanativelib_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalanativelib_3/1.1.8/mill-libs-scalanativelib_3-1.1.8.pom"
    ];
    hash = "sha256-cShts4R4DW4lZf/n/pYiGuqKn7eXEp6xqsyYJjsmnMQ=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-scalanativelib_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-script_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-script_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-script_3/1.1.8/mill-libs-script_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-script_3/1.1.8/mill-libs-script_3-1.1.8.pom"
    ];
    hash = "sha256-Lh8rI2Z/EHqKmMG053TnKrx2sanaLhstkZ62neJS6ro=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-script_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-tabcomplete_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-tabcomplete_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-tabcomplete_3/1.1.8/mill-libs-tabcomplete_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-tabcomplete_3/1.1.8/mill-libs-tabcomplete_3-1.1.8.pom"
    ];
    hash = "sha256-TOQkyZkzj7MuSG4XhYSj+LjftMrJYXuf84TB+xFrXWQ=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-tabcomplete_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-util-java11_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-util-java11_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-util-java11_3/1.1.8/mill-libs-util-java11_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-util-java11_3/1.1.8/mill-libs-util-java11_3-1.1.8.pom"
    ];
    hash = "sha256-G3ehiCDKPatubqzlNLv6kwIv4NqQshg6ck4adEYX7TA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-util-java11_3/1.1.8";
  };

  "com.lihaoyi_mill-libs-util_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs-util_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-util_3/1.1.8/mill-libs-util_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs-util_3/1.1.8/mill-libs-util_3-1.1.8.pom"
    ];
    hash = "sha256-LIP4oyq3+O3dlOO8N17OLEiZNkSNA1l8UOt77nLlSjE=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs-util_3/1.1.8";
  };

  "com.lihaoyi_mill-libs_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-libs_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs_3/1.1.8/mill-libs_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-libs_3/1.1.8/mill-libs_3-1.1.8.pom"
    ];
    hash = "sha256-+Xndt7D4MGWIOHmJo1HS8gBqNXiDIIhK64SocTfBMJU=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-libs_3/1.1.8";
  };

  "com.lihaoyi_mill-moduledefs_3-0.13.1" = fetchMaven {
    name = "com.lihaoyi_mill-moduledefs_3-0.13.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-moduledefs_3/0.13.1/mill-moduledefs_3-0.13.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-moduledefs_3/0.13.1/mill-moduledefs_3-0.13.1.pom"
    ];
    hash = "sha256-FtoxYwGaNOAbIgk6dJvCB3q3LMbWRaWp1Oihk/pK1TU=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-moduledefs_3/0.13.1";
  };

  "com.lihaoyi_mill-runner-autooverride-api_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-autooverride-api_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-autooverride-api_3/1.1.8/mill-runner-autooverride-api_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-autooverride-api_3/1.1.8/mill-runner-autooverride-api_3-1.1.8.pom"
    ];
    hash = "sha256-0iZN7rFuUVYdtaaFnITcNk6uXY2oVHhh1+Z8+EcLqOA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-autooverride-api_3/1.1.8";
  };

  "com.lihaoyi_mill-runner-autooverride-plugin_3.8.2-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-autooverride-plugin_3.8.2-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-autooverride-plugin_3.8.2/1.1.8/mill-runner-autooverride-plugin_3.8.2-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-autooverride-plugin_3.8.2/1.1.8/mill-runner-autooverride-plugin_3.8.2-1.1.8.pom"
    ];
    hash = "sha256-GwRGSk35Fy/1JNyGKe/RgREOVsmuFJ6FyffNMLBZcjk=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-autooverride-plugin_3.8.2/1.1.8";
  };

  "com.lihaoyi_mill-runner-bsp_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-bsp_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-bsp_3/1.1.8/mill-runner-bsp_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-bsp_3/1.1.8/mill-runner-bsp_3-1.1.8.pom"
    ];
    hash = "sha256-alpJemJWyRmx+h0yLmtBjx8W/f3+m6a2E+MqXVwAdfA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-bsp_3/1.1.8";
  };

  "com.lihaoyi_mill-runner-codesig_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-codesig_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-codesig_3/1.1.8/mill-runner-codesig_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-codesig_3/1.1.8/mill-runner-codesig_3-1.1.8.pom"
    ];
    hash = "sha256-ia5+D4jLWKhPzKhM9HHDTU4mEfWUihT4wKj0ihU1K7U=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-codesig_3/1.1.8";
  };

  "com.lihaoyi_mill-runner-daemon_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-daemon_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-daemon_3/1.1.8/mill-runner-daemon_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-daemon_3/1.1.8/mill-runner-daemon_3-1.1.8.pom"
    ];
    hash = "sha256-BTGAcOa+Kb/Okqbu1B3VYeX85IkVHgVC0dcj1J1mLsE=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-daemon_3/1.1.8";
  };

  "com.lihaoyi_mill-runner-eclipse_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-eclipse_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-eclipse_3/1.1.8/mill-runner-eclipse_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-eclipse_3/1.1.8/mill-runner-eclipse_3-1.1.8.pom"
    ];
    hash = "sha256-KFdxSIIGS0SNarJt4nCIdQrkIyEjXV0KwQcSF41TA6w=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-eclipse_3/1.1.8";
  };

  "com.lihaoyi_mill-runner-launcher_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-launcher_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-launcher_3/1.1.8/mill-runner-launcher_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-launcher_3/1.1.8/mill-runner-launcher_3-1.1.8.pom"
    ];
    hash = "sha256-Dfx/8SNvpbwfolmscOxdr1LsXjmrkzkmvGK7RpoJJk0=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-launcher_3/1.1.8";
  };

  "com.lihaoyi_mill-runner-meta_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-meta_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-meta_3/1.1.8/mill-runner-meta_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-meta_3/1.1.8/mill-runner-meta_3-1.1.8.pom"
    ];
    hash = "sha256-c5/K3G97Rw3UOBZP+JyCf11dt/hTUb/IkND0apRHobo=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-meta_3/1.1.8";
  };

  "com.lihaoyi_mill-runner-server_3-1.1.8" = fetchMaven {
    name = "com.lihaoyi_mill-runner-server_3-1.1.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-server_3/1.1.8/mill-runner-server_3-1.1.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-runner-server_3/1.1.8/mill-runner-server_3-1.1.8.pom"
    ];
    hash = "sha256-p1F+o+bm8MDA0Bx/KYEpbPVVAP+lirnTAIM2NlgNgRM=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-runner-server_3/1.1.8";
  };

  "com.lihaoyi_mill-scala-compiler-bridge_2.13.12-0.0.1" = fetchMaven {
    name = "com.lihaoyi_mill-scala-compiler-bridge_2.13.12-0.0.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-scala-compiler-bridge_2.13.12/0.0.1/mill-scala-compiler-bridge_2.13.12-0.0.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/mill-scala-compiler-bridge_2.13.12/0.0.1/mill-scala-compiler-bridge_2.13.12-0.0.1.pom"
    ];
    hash = "sha256-GaXk8CIA96v8LO4rVEQiDCtSYt+W/jflKFVX5MSJ7Bo=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/mill-scala-compiler-bridge_2.13.12/0.0.1";
  };

  "com.lihaoyi_os-lib-watch_3-0.11.8" = fetchMaven {
    name = "com.lihaoyi_os-lib-watch_3-0.11.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib-watch_3/0.11.8/os-lib-watch_3-0.11.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib-watch_3/0.11.8/os-lib-watch_3-0.11.8.pom"
    ];
    hash = "sha256-RkqtzxzH4tiaOFaDpQdvXhqdiR4Mrzk/VVbXm/0O8K8=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/os-lib-watch_3/0.11.8";
  };

  "com.lihaoyi_os-lib_2.13-0.11.7" = fetchMaven {
    name = "com.lihaoyi_os-lib_2.13-0.11.7";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.11.7/os-lib_2.13-0.11.7.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.11.7/os-lib_2.13-0.11.7.pom"
    ];
    hash = "sha256-ApP9S8VJ6jUTek/Ceh3ZaCsSkeXXyzbFEWZj1npYC50=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.11.7";
  };

  "com.lihaoyi_os-lib_2.13-0.9.3" = fetchMaven {
    name = "com.lihaoyi_os-lib_2.13-0.9.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.9.3/os-lib_2.13-0.9.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.9.3/os-lib_2.13-0.9.3.pom"
    ];
    hash = "sha256-XuX4Y3+RlgsJ/YyvEx8J61DlGSUyzBQOo5+9M+nmrTs=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.9.3";
  };

  "com.lihaoyi_os-lib_3-0.11.8" = fetchMaven {
    name = "com.lihaoyi_os-lib_3-0.11.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib_3/0.11.8/os-lib_3-0.11.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/os-lib_3/0.11.8/os-lib_3-0.11.8.pom"
    ];
    hash = "sha256-x+kzII+L0a54bp4fSurrXiunx7R7DtHKvLVaZvPZ0yM=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/os-lib_3/0.11.8";
  };

  "com.lihaoyi_os-zip-0.11.7" = fetchMaven {
    name = "com.lihaoyi_os-zip-0.11.7";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.7/os-zip-0.11.7.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.7/os-zip-0.11.7.pom"
    ];
    hash = "sha256-q39gUxO88BYHwS3y2xJBXjen+LR889h5mgNlX3dUqHk=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.7";
  };

  "com.lihaoyi_os-zip-0.11.8" = fetchMaven {
    name = "com.lihaoyi_os-zip-0.11.8";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.8/os-zip-0.11.8.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.8/os-zip-0.11.8.pom"
    ];
    hash = "sha256-jBJhfpAe97pLxzyc4Qe17M3KbZea93pwz3lEr8Z0v18=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/os-zip/0.11.8";
  };

  "com.lihaoyi_pprint_3-0.9.3" = fetchMaven {
    name = "com.lihaoyi_pprint_3-0.9.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.3/pprint_3-0.9.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.3/pprint_3-0.9.3.pom"
    ];
    hash = "sha256-1Ifl6qABoIAAD/1ahPwZ+qTVhEYfqecg9OtCi+kzEh8=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.3";
  };

  "com.lihaoyi_pprint_3-0.9.6" = fetchMaven {
    name = "com.lihaoyi_pprint_3-0.9.6";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.6/pprint_3-0.9.6.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.6/pprint_3-0.9.6.pom"
    ];
    hash = "sha256-rgJ1Xt7SYj05HgAYb3r+lBvbRcM++i3EMqV4a/3ibp0=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/pprint_3/0.9.6";
  };

  "com.lihaoyi_requests_3-0.8.2" = fetchMaven {
    name = "com.lihaoyi_requests_3-0.8.2";
    urls = [ "https://repo1.maven.org/maven2/com/lihaoyi/requests_3/0.8.2/requests_3-0.8.2.pom" ];
    hash = "sha256-mgdzh8Gw8N9bGVUCWM2DOyn8QVqngi79aiRvzMzSthc=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/requests_3/0.8.2";
  };

  "com.lihaoyi_requests_3-0.9.3" = fetchMaven {
    name = "com.lihaoyi_requests_3-0.9.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/requests_3/0.9.3/requests_3-0.9.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/requests_3/0.9.3/requests_3-0.9.3.pom"
    ];
    hash = "sha256-y9tnEMrHt/e+hxfK3PksjjHIEwJ2Ej6hypRJlJhzj2c=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/requests_3/0.9.3";
  };

  "com.lihaoyi_scalac-mill-moduledefs-plugin_3.8.2-0.13.1" = fetchMaven {
    name = "com.lihaoyi_scalac-mill-moduledefs-plugin_3.8.2-0.13.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/scalac-mill-moduledefs-plugin_3.8.2/0.13.1/scalac-mill-moduledefs-plugin_3.8.2-0.13.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/scalac-mill-moduledefs-plugin_3.8.2/0.13.1/scalac-mill-moduledefs-plugin_3.8.2-0.13.1.pom"
    ];
    hash = "sha256-W55P/b2uRU2nzXo4iYjJ49uycrWwi02XlkTbfLhIht4=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/scalac-mill-moduledefs-plugin_3.8.2/0.13.1";
  };

  "com.lihaoyi_sourcecode_2.13-0.3.0" = fetchMaven {
    name = "com.lihaoyi_sourcecode_2.13-0.3.0";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/sourcecode_2.13/0.3.0/sourcecode_2.13-0.3.0.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/sourcecode_2.13/0.3.0/sourcecode_2.13-0.3.0.pom"
    ];
    hash = "sha256-Y+QhWVO6t2oYpWS/s2aG1fHO+QZ726LyJYaGh3SL4ko=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_2.13/0.3.0";
  };

  "com.lihaoyi_sourcecode_3-0.3.0" = fetchMaven {
    name = "com.lihaoyi_sourcecode_3-0.3.0";
    urls = [ "https://repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.3.0/sourcecode_3-0.3.0.pom" ];
    hash = "sha256-lr4/nfVXauGgxI2rR6IJs2lPSQkS39mb8QS//1agWtA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.3.0";
  };

  "com.lihaoyi_sourcecode_3-0.4.0" = fetchMaven {
    name = "com.lihaoyi_sourcecode_3-0.4.0";
    urls = [ "https://repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.4.0/sourcecode_3-0.4.0.pom" ];
    hash = "sha256-CunaGKCz6cVD4Kx3ZdSg3L5DGSNtI4jCuhsBj+hMKGA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.4.0";
  };

  "com.lihaoyi_sourcecode_3-0.4.4" = fetchMaven {
    name = "com.lihaoyi_sourcecode_3-0.4.4";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.4.4/sourcecode_3-0.4.4.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.4.4/sourcecode_3-0.4.4.pom"
    ];
    hash = "sha256-Mb4BGjFreHJodpuyXYAN4MNqYNlNeSQoTWHUnPYdF10=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/sourcecode_3/0.4.4";
  };

  "com.lihaoyi_ujson_2.13-3.3.1" = fetchMaven {
    name = "com.lihaoyi_ujson_2.13-3.3.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1/ujson_2.13-3.3.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1/ujson_2.13-3.3.1.pom"
    ];
    hash = "sha256-tS5BVFeMdRfzGHUlrAywtQb4mG6oel56ooMEtlsWGjI=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1";
  };

  "com.lihaoyi_ujson_3-4.4.3" = fetchMaven {
    name = "com.lihaoyi_ujson_3-4.4.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/ujson_3/4.4.3/ujson_3-4.4.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/ujson_3/4.4.3/ujson_3-4.4.3.pom"
    ];
    hash = "sha256-iyd34AfH34Tok6c+MLKecRU4BWWcs2vFjBI+dWjHcbE=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/ujson_3/4.4.3";
  };

  "com.lihaoyi_unroll-annotation_3-0.2.0" = fetchMaven {
    name = "com.lihaoyi_unroll-annotation_3-0.2.0";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/unroll-annotation_3/0.2.0/unroll-annotation_3-0.2.0.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/unroll-annotation_3/0.2.0/unroll-annotation_3-0.2.0.pom"
    ];
    hash = "sha256-ExxiEO3FCd5f41vmo6LitJC46Lq5EBcjLSsFDmrTWLA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/unroll-annotation_3/0.2.0";
  };

  "com.lihaoyi_upack_3-4.4.3" = fetchMaven {
    name = "com.lihaoyi_upack_3-4.4.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/upack_3/4.4.3/upack_3-4.4.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/upack_3/4.4.3/upack_3-4.4.3.pom"
    ];
    hash = "sha256-yNJ3+ctidSURwFBpIwHM7SwVPUGdUqL2O2s7iB9XsEA=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/upack_3/4.4.3";
  };

  "com.lihaoyi_upickle-core_2.13-3.3.1" = fetchMaven {
    name = "com.lihaoyi_upickle-core_2.13-3.3.1";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1/upickle-core_2.13-3.3.1.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1/upickle-core_2.13-3.3.1.pom"
    ];
    hash = "sha256-+vXjTD3FY+FMlDpvsOkhwycDbvhnIY0SOcHKOYc+StM=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1";
  };

  "com.lihaoyi_upickle-core_3-4.4.3" = fetchMaven {
    name = "com.lihaoyi_upickle-core_3-4.4.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-core_3/4.4.3/upickle-core_3-4.4.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-core_3/4.4.3/upickle-core_3-4.4.3.pom"
    ];
    hash = "sha256-gwugHELfm/5wJRmZ0AHrjOjBJLka2iH8UNVf9GyxCJ8=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_3/4.4.3";
  };

  "com.lihaoyi_upickle-implicits-named-tuples_3-4.4.3" = fetchMaven {
    name = "com.lihaoyi_upickle-implicits-named-tuples_3-4.4.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-implicits-named-tuples_3/4.4.3/upickle-implicits-named-tuples_3-4.4.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-implicits-named-tuples_3/4.4.3/upickle-implicits-named-tuples_3-4.4.3.pom"
    ];
    hash = "sha256-ooBV0Qmnkd1Rg1aQGNvim4OFodYMxTnvbYAhXfIXbek=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits-named-tuples_3/4.4.3";
  };

  "com.lihaoyi_upickle-implicits_3-4.4.3" = fetchMaven {
    name = "com.lihaoyi_upickle-implicits_3-4.4.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_3/4.4.3/upickle-implicits_3-4.4.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_3/4.4.3/upickle-implicits_3-4.4.3.pom"
    ];
    hash = "sha256-O8GanXWyJFAxqOEdcxpSB1qOZMmvS2UGBzKOsBdsjVM=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_3/4.4.3";
  };

  "com.lihaoyi_upickle_3-4.4.3" = fetchMaven {
    name = "com.lihaoyi_upickle_3-4.4.3";
    urls = [
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle_3/4.4.3/upickle_3-4.4.3.jar"
      "https://repo1.maven.org/maven2/com/lihaoyi/upickle_3/4.4.3/upickle_3-4.4.3.pom"
    ];
    hash = "sha256-31xWbcJJ3t1Rfcsh1ET61oG1wzVId4BR1EkMb8BmAw4=";
    installPath = "https/repo1.maven.org/maven2/com/lihaoyi/upickle_3/4.4.3";
  };

  "com.lmax_disruptor-3.4.2" = fetchMaven {
    name = "com.lmax_disruptor-3.4.2";
    urls = [
      "https://repo1.maven.org/maven2/com/lmax/disruptor/3.4.2/disruptor-3.4.2.jar"
      "https://repo1.maven.org/maven2/com/lmax/disruptor/3.4.2/disruptor-3.4.2.pom"
    ];
    hash = "sha256-nbZsn6zL8HaJOrkMiWwvCuHQumcNQYA8e6QrAjXKKKg=";
    installPath = "https/repo1.maven.org/maven2/com/lmax/disruptor/3.4.2";
  };

  "com.lumidion_sonatype-central-client-core_3-0.6.0" = fetchMaven {
    name = "com.lumidion_sonatype-central-client-core_3-0.6.0";
    urls = [
      "https://repo1.maven.org/maven2/com/lumidion/sonatype-central-client-core_3/0.6.0/sonatype-central-client-core_3-0.6.0.jar"
      "https://repo1.maven.org/maven2/com/lumidion/sonatype-central-client-core_3/0.6.0/sonatype-central-client-core_3-0.6.0.pom"
    ];
    hash = "sha256-YEkjIQPfrhNzTOyy9wivwhFF/IRA8hBAHAkx+dt2Feg=";
    installPath = "https/repo1.maven.org/maven2/com/lumidion/sonatype-central-client-core_3/0.6.0";
  };

  "com.lumidion_sonatype-central-client-requests_3-0.6.0" = fetchMaven {
    name = "com.lumidion_sonatype-central-client-requests_3-0.6.0";
    urls = [
      "https://repo1.maven.org/maven2/com/lumidion/sonatype-central-client-requests_3/0.6.0/sonatype-central-client-requests_3-0.6.0.jar"
      "https://repo1.maven.org/maven2/com/lumidion/sonatype-central-client-requests_3/0.6.0/sonatype-central-client-requests_3-0.6.0.pom"
    ];
    hash = "sha256-725YZk7xMwtpthJ/lf57xTXkEn+0njn3BdTaoV98nNI=";
    installPath = "https/repo1.maven.org/maven2/com/lumidion/sonatype-central-client-requests_3/0.6.0";
  };

  "com.lumidion_sonatype-central-client-upickle_3-0.6.0" = fetchMaven {
    name = "com.lumidion_sonatype-central-client-upickle_3-0.6.0";
    urls = [
      "https://repo1.maven.org/maven2/com/lumidion/sonatype-central-client-upickle_3/0.6.0/sonatype-central-client-upickle_3-0.6.0.jar"
      "https://repo1.maven.org/maven2/com/lumidion/sonatype-central-client-upickle_3/0.6.0/sonatype-central-client-upickle_3-0.6.0.pom"
    ];
    hash = "sha256-26m05j/d1yXlIpECzcGNyDS4mZE5i9CkbGTmWYEmN3Y=";
    installPath = "https/repo1.maven.org/maven2/com/lumidion/sonatype-central-client-upickle_3/0.6.0";
  };

  "com.swoval_file-tree-views-2.1.12" = fetchMaven {
    name = "com.swoval_file-tree-views-2.1.12";
    urls = [
      "https://repo1.maven.org/maven2/com/swoval/file-tree-views/2.1.12/file-tree-views-2.1.12.jar"
      "https://repo1.maven.org/maven2/com/swoval/file-tree-views/2.1.12/file-tree-views-2.1.12.pom"
    ];
    hash = "sha256-QhJJFQt5LS2THa8AyPLrj0suht4eCiAEl2sf7QsZU3I=";
    installPath = "https/repo1.maven.org/maven2/com/swoval/file-tree-views/2.1.12";
  };

  "com.typesafe_config-1.4.2" = fetchMaven {
    name = "com.typesafe_config-1.4.2";
    urls = [
      "https://repo1.maven.org/maven2/com/typesafe/config/1.4.2/config-1.4.2.jar"
      "https://repo1.maven.org/maven2/com/typesafe/config/1.4.2/config-1.4.2.pom"
    ];
    hash = "sha256-/QL0m74bPa8POZggcdv1QzcamwCljtaJ2A4yMVKCrKI=";
    installPath = "https/repo1.maven.org/maven2/com/typesafe/config/1.4.2";
  };

  "io.get-coursier_cache-util-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_cache-util-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/cache-util/2.1.25-M26/cache-util-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/cache-util/2.1.25-M26/cache-util-2.1.25-M26.pom"
    ];
    hash = "sha256-CYXNfnCCkaxvQSB6PajPLPunxBPbsv0akPbajv+dHVQ=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/cache-util/2.1.25-M26";
  };

  "io.get-coursier_coursier-archive-cache_2.13-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-archive-cache_2.13-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-archive-cache_2.13/2.1.25-M26/coursier-archive-cache_2.13-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-archive-cache_2.13/2.1.25-M26/coursier-archive-cache_2.13-2.1.25-M26.pom"
    ];
    hash = "sha256-TFHJwlnxkf9ZTklejOQywn6ZHXHEmMPKn1IMvAwxSUo=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-archive-cache_2.13/2.1.25-M26";
  };

  "io.get-coursier_coursier-cache_2.13-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-cache_2.13-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-cache_2.13/2.1.25-M26/coursier-cache_2.13-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-cache_2.13/2.1.25-M26/coursier-cache_2.13-2.1.25-M26.pom"
    ];
    hash = "sha256-8cjzTmXCPxbfLwHSVqWh5mFM1gsMgBU0a4cauonAwno=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-cache_2.13/2.1.25-M26";
  };

  "io.get-coursier_coursier-core_2.13-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-core_2.13-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-core_2.13/2.1.25-M26/coursier-core_2.13-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-core_2.13/2.1.25-M26/coursier-core_2.13-2.1.25-M26.pom"
    ];
    hash = "sha256-YllMX//WwQVhNNh5+DxzgI6AXeME8MWHTyyFGidEnnE=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-core_2.13/2.1.25-M26";
  };

  "io.get-coursier_coursier-env_2.13-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-env_2.13-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-env_2.13/2.1.25-M26/coursier-env_2.13-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-env_2.13/2.1.25-M26/coursier-env_2.13-2.1.25-M26.pom"
    ];
    hash = "sha256-Lw9U4BSUBffqQLey1u7ygMrTNw4PII3OE+YiIdrpX2Q=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-env_2.13/2.1.25-M26";
  };

  "io.get-coursier_coursier-exec-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-exec-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-exec/2.1.25-M26/coursier-exec-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-exec/2.1.25-M26/coursier-exec-2.1.25-M26.pom"
    ];
    hash = "sha256-A+JoZldkcF+wnd6EG7+CEpde3j1noduWGNlh4Q4aDJQ=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-exec/2.1.25-M26";
  };

  "io.get-coursier_coursier-jvm_2.13-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-jvm_2.13-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-jvm_2.13/2.1.25-M26/coursier-jvm_2.13-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-jvm_2.13/2.1.25-M26/coursier-jvm_2.13-2.1.25-M26.pom"
    ];
    hash = "sha256-KJNMnjtZiKC07TPZz6IxQuafJJsLOqqDeH98XSr8Wsk=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-jvm_2.13/2.1.25-M26";
  };

  "io.get-coursier_coursier-paths-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-paths-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-paths/2.1.25-M26/coursier-paths-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-paths/2.1.25-M26/coursier-paths-2.1.25-M26.pom"
    ];
    hash = "sha256-IIJNkNqnIBP2GmitAKSEtNOjp20ckR3+OOWmrrhjhtI=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-paths/2.1.25-M26";
  };

  "io.get-coursier_coursier-proxy-setup-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-proxy-setup-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-proxy-setup/2.1.25-M26/coursier-proxy-setup-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-proxy-setup/2.1.25-M26/coursier-proxy-setup-2.1.25-M26.pom"
    ];
    hash = "sha256-AOtPtJorpz7buKGpdhlVn31bwXE63Z8B20DsWZEM6DE=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-proxy-setup/2.1.25-M26";
  };

  "io.get-coursier_coursier-util_2.13-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier-util_2.13-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-util_2.13/2.1.25-M26/coursier-util_2.13-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier-util_2.13/2.1.25-M26/coursier-util_2.13-2.1.25-M26.pom"
    ];
    hash = "sha256-PwpKasWCpk9cij5lKEy+MFMQPkJOQc+Yn4TWQ0wvuko=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier-util_2.13/2.1.25-M26";
  };

  "io.get-coursier_coursier_2.13-2.1.25-M26" = fetchMaven {
    name = "io.get-coursier_coursier_2.13-2.1.25-M26";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/coursier_2.13/2.1.25-M26/coursier_2.13-2.1.25-M26.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/coursier_2.13/2.1.25-M26/coursier_2.13-2.1.25-M26.pom"
    ];
    hash = "sha256-S47g5/DyJcFoC7OeLpEUIDXQz8DnhjiZ85T/VcxJD7I=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/coursier_2.13/2.1.25-M26";
  };

  "io.get-coursier_dependency_2.13-0.3.2" = fetchMaven {
    name = "io.get-coursier_dependency_2.13-0.3.2";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/dependency_2.13/0.3.2/dependency_2.13-0.3.2.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/dependency_2.13/0.3.2/dependency_2.13-0.3.2.pom"
    ];
    hash = "sha256-kLCTLMEFNrY74GFqcr7Vw/qEbR7CPpskXrpzbbH0gMg=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/dependency_2.13/0.3.2";
  };

  "io.get-coursier_interface-1.0.28" = fetchMaven {
    name = "io.get-coursier_interface-1.0.28";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/interface/1.0.28/interface-1.0.28.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/interface/1.0.28/interface-1.0.28.pom"
    ];
    hash = "sha256-ilqO9pRagNeDDD9UlIXzkEXhBZEJQNLyGU/FzBhqgaY=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/interface/1.0.28";
  };

  "io.get-coursier_versions_2.13-0.5.1" = fetchMaven {
    name = "io.get-coursier_versions_2.13-0.5.1";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.1/versions_2.13-0.5.1.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.1/versions_2.13-0.5.1.pom"
    ];
    hash = "sha256-1ryxcGeeUu18sLY4gL2cDVfOkh59oRPmNnIA0N2G1/Y=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.1";
  };

  "io.get-coursier_versions_2.13-0.5.3" = fetchMaven {
    name = "io.get-coursier_versions_2.13-0.5.3";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.3/versions_2.13-0.5.3.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.3/versions_2.13-0.5.3.pom"
    ];
    hash = "sha256-qtQk3ZGPj3O5palH8sQm9UWj1Qe0SFZtBkDZPVTNCkE=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/versions_2.13/0.5.3";
  };

  "io.grpc_grpc-bom-1.81.0" = fetchMaven {
    name = "io.grpc_grpc-bom-1.81.0";
    urls = [ "https://repo1.maven.org/maven2/io/grpc/grpc-bom/1.81.0/grpc-bom-1.81.0.pom" ];
    hash = "sha256-89mPNdihqUysImwrc4iQ1YzdQJpFtgNCokqtc8E3hsM=";
    installPath = "https/repo1.maven.org/maven2/io/grpc/grpc-bom/1.81.0";
  };

  "io.netty_netty-bom-4.2.13.Final" = fetchMaven {
    name = "io.netty_netty-bom-4.2.13.Final";
    urls = [
      "https://repo1.maven.org/maven2/io/netty/netty-bom/4.2.13.Final/netty-bom-4.2.13.Final.pom"
    ];
    hash = "sha256-6edAezMWDdbvjBtIWWicTQZnrKTlGfdZxX+GMLeGHqo=";
    installPath = "https/repo1.maven.org/maven2/io/netty/netty-bom/4.2.13.Final";
  };

  "jakarta.platform_jakarta.jakartaee-bom-9.1.0" = fetchMaven {
    name = "jakarta.platform_jakarta.jakartaee-bom-9.1.0";
    urls = [
      "https://repo1.maven.org/maven2/jakarta/platform/jakarta.jakartaee-bom/9.1.0/jakarta.jakartaee-bom-9.1.0.pom"
    ];
    hash = "sha256-kstGe15Yw9oF6LQ3Vovx1PcCUfQtNaEM7T8E5Upp1gg=";
    installPath = "https/repo1.maven.org/maven2/jakarta/platform/jakarta.jakartaee-bom/9.1.0";
  };

  "jakarta.platform_jakartaee-api-parent-9.1.0" = fetchMaven {
    name = "jakarta.platform_jakartaee-api-parent-9.1.0";
    urls = [
      "https://repo1.maven.org/maven2/jakarta/platform/jakartaee-api-parent/9.1.0/jakartaee-api-parent-9.1.0.pom"
    ];
    hash = "sha256-FrD7N30UkkRSQtD3+FPOC1fH2qrNnJw6UZQ/hNFXWrA=";
    installPath = "https/repo1.maven.org/maven2/jakarta/platform/jakartaee-api-parent/9.1.0";
  };

  "javax.inject_javax.inject-1" = fetchMaven {
    name = "javax.inject_javax.inject-1";
    urls = [
      "https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar"
      "https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.pom"
    ];
    hash = "sha256-CZm6Lb7D5az8nprqBvjNerGQjB0xPaY56/RvKwSZIxE=";
    installPath = "https/repo1.maven.org/maven2/javax/inject/javax.inject/1";
  };

  "net.java_jvnet-parent-5" = fetchMaven {
    name = "net.java_jvnet-parent-5";
    urls = [ "https://repo1.maven.org/maven2/net/java/jvnet-parent/5/jvnet-parent-5.pom" ];
    hash = "sha256-L6zzG1WwyalibW8K8CLiK/d7uu2l/5xIfDQOGILEpYQ=";
    installPath = "https/repo1.maven.org/maven2/net/java/jvnet-parent/5";
  };

  "net.openhft_affinity-3.23.2" = fetchMaven {
    name = "net.openhft_affinity-3.23.2";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/affinity/3.23.2/affinity-3.23.2.jar"
      "https://repo1.maven.org/maven2/net/openhft/affinity/3.23.2/affinity-3.23.2.pom"
    ];
    hash = "sha256-Grc4ct6xUlE7dbXIma0pv0HkpRCXwjJGaEBB35VAMNw=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/affinity/3.23.2";
  };

  "net.openhft_chronicle-bom-2.23.126" = fetchMaven {
    name = "net.openhft_chronicle-bom-2.23.126";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/chronicle-bom/2.23.126/chronicle-bom-2.23.126.pom"
    ];
    hash = "sha256-EBNqqfwqevwOEMzgLyI5N6gpgmrbF1nM0NQFQ8YBQ2Q=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/chronicle-bom/2.23.126";
  };

  "net.openhft_java-parent-pom-1.1.28" = fetchMaven {
    name = "net.openhft_java-parent-pom-1.1.28";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/java-parent-pom/1.1.28/java-parent-pom-1.1.28.pom"
    ];
    hash = "sha256-d7bOKP/hHJElmDQtIbblYDHRc8LCpqkt5Zl8aHp7l88=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/java-parent-pom/1.1.28";
  };

  "net.openhft_java-parent-pom-1.1.33" = fetchMaven {
    name = "net.openhft_java-parent-pom-1.1.33";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/java-parent-pom/1.1.33/java-parent-pom-1.1.33.pom"
    ];
    hash = "sha256-YsYF8dYFNEDeNOH+Ww7uFs070grUxCRO5NSj8sEE2f0=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/java-parent-pom/1.1.33";
  };

  "net.openhft_root-parent-pom-1.2.12" = fetchMaven {
    name = "net.openhft_root-parent-pom-1.2.12";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.12/root-parent-pom-1.2.12.pom"
    ];
    hash = "sha256-D/M1qN+njmMZWqS5h27fl83Q+zWgIFjaYQkCpD2Oy/M=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.12";
  };

  "net.openhft_root-parent-pom-1.2.15" = fetchMaven {
    name = "net.openhft_root-parent-pom-1.2.15";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.15/root-parent-pom-1.2.15.pom"
    ];
    hash = "sha256-cmDU2g1y5yONbuHmL2kpVb8AGRkOUeEOO5pvg6PKDEU=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.15";
  };

  "net.openhft_root-parent-pom-1.2.21" = fetchMaven {
    name = "net.openhft_root-parent-pom-1.2.21";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.21/root-parent-pom-1.2.21.pom"
    ];
    hash = "sha256-hbdjuQf1sgXSjJ4LfwTNQ3CGDPTDMaU2EItsCn8WADE=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.21";
  };

  "net.openhft_root-parent-pom-1.2.3" = fetchMaven {
    name = "net.openhft_root-parent-pom-1.2.3";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.3/root-parent-pom-1.2.3.pom"
    ];
    hash = "sha256-bEgME+Z0nC70gOqw/IPraVb4dn90gr/jb0gbWOqXYVc=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/root-parent-pom/1.2.3";
  };

  "net.openhft_third-party-bom-3.22.4" = fetchMaven {
    name = "net.openhft_third-party-bom-3.22.4";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/third-party-bom/3.22.4/third-party-bom-3.22.4.pom"
    ];
    hash = "sha256-vgGlHPzyxxWTG5MNTdwryjjJealx17SIO2DWKpCUcJU=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/third-party-bom/3.22.4";
  };

  "net.openhft_zero-allocation-hashing-0.16" = fetchMaven {
    name = "net.openhft_zero-allocation-hashing-0.16";
    urls = [
      "https://repo1.maven.org/maven2/net/openhft/zero-allocation-hashing/0.16/zero-allocation-hashing-0.16.jar"
      "https://repo1.maven.org/maven2/net/openhft/zero-allocation-hashing/0.16/zero-allocation-hashing-0.16.pom"
    ];
    hash = "sha256-QkNOGkyP/OFWM+pv40hqR+ii4GBAcv0bbIrpG66YDMo=";
    installPath = "https/repo1.maven.org/maven2/net/openhft/zero-allocation-hashing/0.16";
  };

  "org.apache_apache-19" = fetchMaven {
    name = "org.apache_apache-19";
    urls = [ "https://repo1.maven.org/maven2/org/apache/apache/19/apache-19.pom" ];
    hash = "sha256-zhBKa7d1483sjfmn+XnLUQgYZltXXBPJayIZ44PcKHo=";
    installPath = "https/repo1.maven.org/maven2/org/apache/apache/19";
  };

  "org.apache_apache-23" = fetchMaven {
    name = "org.apache_apache-23";
    urls = [ "https://repo1.maven.org/maven2/org/apache/apache/23/apache-23.pom" ];
    hash = "sha256-se+GoyCLRNTik1Rjwr8lNwZ6obSZFX00JZqJpThu4fo=";
    installPath = "https/repo1.maven.org/maven2/org/apache/apache/23";
  };

  "org.apache_apache-31" = fetchMaven {
    name = "org.apache_apache-31";
    urls = [ "https://repo1.maven.org/maven2/org/apache/apache/31/apache-31.pom" ];
    hash = "sha256-Evktp+xRZ2C/VvG0UDTcFRSEvvSJINCtIe0Rom2159s=";
    installPath = "https/repo1.maven.org/maven2/org/apache/apache/31";
  };

  "org.apache_apache-35" = fetchMaven {
    name = "org.apache_apache-35";
    urls = [ "https://repo1.maven.org/maven2/org/apache/apache/35/apache-35.pom" ];
    hash = "sha256-Xi9qlMJKcB7Oc/RDG74Xmum5LLz6PVSIREBESM2qPbQ=";
    installPath = "https/repo1.maven.org/maven2/org/apache/apache/35";
  };

  "org.apache_apache-37" = fetchMaven {
    name = "org.apache_apache-37";
    urls = [ "https://repo1.maven.org/maven2/org/apache/apache/37/apache-37.pom" ];
    hash = "sha256-wDM+O9XgcqB9AJJREj1BQ1v38F2Ni+R/h39fCutYBIM=";
    installPath = "https/repo1.maven.org/maven2/org/apache/apache/37";
  };

  "org.apache_apache-38" = fetchMaven {
    name = "org.apache_apache-38";
    urls = [ "https://repo1.maven.org/maven2/org/apache/apache/38/apache-38.pom" ];
    hash = "sha256-4DeBOnAYQuBWwg3Kuo27/rNwl1fgcBjpyKhvM/DMp3U=";
    installPath = "https/repo1.maven.org/maven2/org/apache/apache/38";
  };

  "org.apache_apache-6" = fetchMaven {
    name = "org.apache_apache-6";
    urls = [ "https://repo1.maven.org/maven2/org/apache/apache/6/apache-6.pom" ];
    hash = "sha256-A7aDRlGjS4P3/QlZmvMRdVHhP4yqTFL4wZbRnp1lJ9U=";
    installPath = "https/repo1.maven.org/maven2/org/apache/apache/6";
  };

  "org.fusesource_fusesource-pom-1.12" = fetchMaven {
    name = "org.fusesource_fusesource-pom-1.12";
    urls = [
      "https://repo1.maven.org/maven2/org/fusesource/fusesource-pom/1.12/fusesource-pom-1.12.pom"
    ];
    hash = "sha256-NUD5PZ1FYYOq8yumvT5i29Vxd2ZCI6PXImXfLe4mE30=";
    installPath = "https/repo1.maven.org/maven2/org/fusesource/fusesource-pom/1.12";
  };

  "org.jline_jline-3.22.0" = fetchMaven {
    name = "org.jline_jline-3.22.0";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline/3.22.0/jline-3.22.0.jar"
      "https://repo1.maven.org/maven2/org/jline/jline/3.22.0/jline-3.22.0.pom"
    ];
    hash = "sha256-I0ovz3Ra27RXAszepdlSnNz+M7u/+NyhBq2ZffnrU8k=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline/3.22.0";
  };

  "org.jline_jline-3.30.13" = fetchMaven {
    name = "org.jline_jline-3.30.13";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline/3.30.13/jline-3.30.13.jar"
      "https://repo1.maven.org/maven2/org/jline/jline/3.30.13/jline-3.30.13.pom"
    ];
    hash = "sha256-GWUtlEyrMxzrw2wySGTegJE/Y7/bPc696skFYxZVrT4=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline/3.30.13";
  };

  "org.jline_jline-native-3.27.1" = fetchMaven {
    name = "org.jline_jline-native-3.27.1";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline-native/3.27.1/jline-native-3.27.1.jar"
      "https://repo1.maven.org/maven2/org/jline/jline-native/3.27.1/jline-native-3.27.1.pom"
    ];
    hash = "sha256-XyhCZMcwu/OXdQ8BTM+qGgjGzMano5DJoghn1+/yr+Q=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-native/3.27.1";
  };

  "org.jline_jline-native-3.29.0" = fetchMaven {
    name = "org.jline_jline-native-3.29.0";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline-native/3.29.0/jline-native-3.29.0.jar"
      "https://repo1.maven.org/maven2/org/jline/jline-native/3.29.0/jline-native-3.29.0.pom"
    ];
    hash = "sha256-B4uPEOoZQdIyvNzjJBxzr+9m6E0Q95p2l/0iyYpz62Y=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-native/3.29.0";
  };

  "org.jline_jline-parent-3.22.0" = fetchMaven {
    name = "org.jline_jline-parent-3.22.0";
    urls = [ "https://repo1.maven.org/maven2/org/jline/jline-parent/3.22.0/jline-parent-3.22.0.pom" ];
    hash = "sha256-onEcBbRLFP9zt0OMtf6/SNhzQNZDxFbosPRVdINwbyU=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-parent/3.22.0";
  };

  "org.jline_jline-parent-3.27.1" = fetchMaven {
    name = "org.jline_jline-parent-3.27.1";
    urls = [ "https://repo1.maven.org/maven2/org/jline/jline-parent/3.27.1/jline-parent-3.27.1.pom" ];
    hash = "sha256-Oa5DgBvf5JwZH68PDIyNkEQtm7IL04ujoeniH6GZas8=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-parent/3.27.1";
  };

  "org.jline_jline-parent-3.29.0" = fetchMaven {
    name = "org.jline_jline-parent-3.29.0";
    urls = [ "https://repo1.maven.org/maven2/org/jline/jline-parent/3.29.0/jline-parent-3.29.0.pom" ];
    hash = "sha256-oxKMIwjIJO0c7pcRwCh1deR9MT5oIjEQD5xiDZzCLNg=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-parent/3.29.0";
  };

  "org.jline_jline-reader-3.29.0" = fetchMaven {
    name = "org.jline_jline-reader-3.29.0";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline-reader/3.29.0/jline-reader-3.29.0.jar"
      "https://repo1.maven.org/maven2/org/jline/jline-reader/3.29.0/jline-reader-3.29.0.pom"
    ];
    hash = "sha256-29VGA1VapJEewqh+CohsyXSpXkH7It/GwcAK0Z4igbo=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-reader/3.29.0";
  };

  "org.jline_jline-terminal-3.27.1" = fetchMaven {
    name = "org.jline_jline-terminal-3.27.1";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline-terminal/3.27.1/jline-terminal-3.27.1.jar"
      "https://repo1.maven.org/maven2/org/jline/jline-terminal/3.27.1/jline-terminal-3.27.1.pom"
    ];
    hash = "sha256-WV77BAEncauTljUBrlYi9v3GxDDeskqQpHHD9Fdbqjw=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-terminal/3.27.1";
  };

  "org.jline_jline-terminal-3.29.0" = fetchMaven {
    name = "org.jline_jline-terminal-3.29.0";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline-terminal/3.29.0/jline-terminal-3.29.0.jar"
      "https://repo1.maven.org/maven2/org/jline/jline-terminal/3.29.0/jline-terminal-3.29.0.pom"
    ];
    hash = "sha256-VSLgLannbVTHJdbWjmHtvPlbqRHgN67iVGQhd6GdBrI=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-terminal/3.29.0";
  };

  "org.jline_jline-terminal-jni-3.27.1" = fetchMaven {
    name = "org.jline_jline-terminal-jni-3.27.1";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline-terminal-jni/3.27.1/jline-terminal-jni-3.27.1.jar"
      "https://repo1.maven.org/maven2/org/jline/jline-terminal-jni/3.27.1/jline-terminal-jni-3.27.1.pom"
    ];
    hash = "sha256-AWKC7imb/rnF39PAo3bVIW430zPkyj9WozKGkPlTTBE=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-terminal-jni/3.27.1";
  };

  "org.jline_jline-terminal-jni-3.29.0" = fetchMaven {
    name = "org.jline_jline-terminal-jni-3.29.0";
    urls = [
      "https://repo1.maven.org/maven2/org/jline/jline-terminal-jni/3.29.0/jline-terminal-jni-3.29.0.jar"
      "https://repo1.maven.org/maven2/org/jline/jline-terminal-jni/3.29.0/jline-terminal-jni-3.29.0.pom"
    ];
    hash = "sha256-P5vIZblvy8nL21syHeJMNJEip/JVsVkislqKbh6ffyA=";
    installPath = "https/repo1.maven.org/maven2/org/jline/jline-terminal-jni/3.29.0";
  };

  "org.junit_junit-bom-5.10.1" = fetchMaven {
    name = "org.junit_junit-bom-5.10.1";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.10.1/junit-bom-5.10.1.pom" ];
    hash = "sha256-j6Bq0SVdCeMwCv3U2bFfmInjBSY9NYedGadR/5PskK4=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.10.1";
  };

  "org.junit_junit-bom-5.13.1" = fetchMaven {
    name = "org.junit_junit-bom-5.13.1";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.13.1/junit-bom-5.13.1.pom" ];
    hash = "sha256-y0fYl6j3V74Ioxxiq2/0Riiw4VDt7XG6YR/Ekd7wKDg=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.13.1";
  };

  "org.junit_junit-bom-5.13.4" = fetchMaven {
    name = "org.junit_junit-bom-5.13.4";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.13.4/junit-bom-5.13.4.pom" ];
    hash = "sha256-uMvXRj2IJjctssr3Twwzn/xTriNqj8Wl3QeIeCzgHwE=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.13.4";
  };

  "org.junit_junit-bom-5.14.1" = fetchMaven {
    name = "org.junit_junit-bom-5.14.1";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.14.1/junit-bom-5.14.1.pom" ];
    hash = "sha256-ibTJ12dg4sPqAVXOsrj5A+q1mJWiri2zTi7wu4uTsA0=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.14.1";
  };

  "org.junit_junit-bom-5.14.2" = fetchMaven {
    name = "org.junit_junit-bom-5.14.2";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.14.2/junit-bom-5.14.2.pom" ];
    hash = "sha256-DT2QOj+abniTqGo5bwp+9wBoMADOPzAOo0+v7UYiMyk=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.14.2";
  };

  "org.junit_junit-bom-5.14.3" = fetchMaven {
    name = "org.junit_junit-bom-5.14.3";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.14.3/junit-bom-5.14.3.pom" ];
    hash = "sha256-tKa5YS1mEjYvCpsJrEagvpzjX08HWJLpSlJFlxXK34Q=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.14.3";
  };

  "org.junit_junit-bom-5.14.4" = fetchMaven {
    name = "org.junit_junit-bom-5.14.4";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.14.4/junit-bom-5.14.4.pom" ];
    hash = "sha256-zXB2lHDnKo96wEUcxRC72Bixw+Fffum+q9YBAB9NdR4=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.14.4";
  };

  "org.junit_junit-bom-5.7.2" = fetchMaven {
    name = "org.junit_junit-bom-5.7.2";
    urls = [ "https://repo1.maven.org/maven2/org/junit/junit-bom/5.7.2/junit-bom-5.7.2.pom" ];
    hash = "sha256-DMi4lde3azy7AnQb+be8FJHb0c/7VWSTyrUVOOsW4Aw=";
    installPath = "https/repo1.maven.org/maven2/org/junit/junit-bom/5.7.2";
  };

  "org.mockito_mockito-bom-4.11.0" = fetchMaven {
    name = "org.mockito_mockito-bom-4.11.0";
    urls = [ "https://repo1.maven.org/maven2/org/mockito/mockito-bom/4.11.0/mockito-bom-4.11.0.pom" ];
    hash = "sha256-jtuaGRrHXNkevtfBAzk3OA+n5RNtrDQ0MQSqSRxUIfc=";
    installPath = "https/repo1.maven.org/maven2/org/mockito/mockito-bom/4.11.0";
  };

  "org.ow2_ow2-1.5.1" = fetchMaven {
    name = "org.ow2_ow2-1.5.1";
    urls = [ "https://repo1.maven.org/maven2/org/ow2/ow2/1.5.1/ow2-1.5.1.pom" ];
    hash = "sha256-4F8xYVbQg2PG/GhDEdcvENureaBF1yT/hSdLimkz5ks=";
    installPath = "https/repo1.maven.org/maven2/org/ow2/ow2/1.5.1";
  };

  "org.pcap4j_pcap4j-1.8.2" = fetchMaven {
    name = "org.pcap4j_pcap4j-1.8.2";
    urls = [ "https://repo1.maven.org/maven2/org/pcap4j/pcap4j/1.8.2/pcap4j-1.8.2.pom" ];
    hash = "sha256-qT2iDb0Vq7snWLt3JmKsK5nk11rdM1iCewszZRoJeFo=";
    installPath = "https/repo1.maven.org/maven2/org/pcap4j/pcap4j/1.8.2";
  };

  "org.pcap4j_pcap4j-core-1.8.2" = fetchMaven {
    name = "org.pcap4j_pcap4j-core-1.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/pcap4j/pcap4j-core/1.8.2/pcap4j-core-1.8.2.jar"
      "https://repo1.maven.org/maven2/org/pcap4j/pcap4j-core/1.8.2/pcap4j-core-1.8.2.pom"
    ];
    hash = "sha256-toGwtU2fsT5fuyXNigkZ6DlDFeKpyZ7Sa+WtqmbBXS0=";
    installPath = "https/repo1.maven.org/maven2/org/pcap4j/pcap4j-core/1.8.2";
  };

  "org.pcap4j_pcap4j-packetfactory-static-1.8.2" = fetchMaven {
    name = "org.pcap4j_pcap4j-packetfactory-static-1.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/pcap4j/pcap4j-packetfactory-static/1.8.2/pcap4j-packetfactory-static-1.8.2.jar"
      "https://repo1.maven.org/maven2/org/pcap4j/pcap4j-packetfactory-static/1.8.2/pcap4j-packetfactory-static-1.8.2.pom"
    ];
    hash = "sha256-venBLYJ4noj9bfPFOkRFfR8ShZZfFUCs4OTkrsHJbLg=";
    installPath = "https/repo1.maven.org/maven2/org/pcap4j/pcap4j-packetfactory-static/1.8.2";
  };

  "org.scala-lang_scala-compiler-2.13.12" = fetchMaven {
    name = "org.scala-lang_scala-compiler-2.13.12";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.13.12/scala-compiler-2.13.12.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.13.12/scala-compiler-2.13.12.pom"
    ];
    hash = "sha256-cVcD6CK1r2M07sg3/MvclRAvtoCKusp2lJFS5Bw/CaU=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.13.12";
  };

  "org.scala-lang_scala-library-2.13.12" = fetchMaven {
    name = "org.scala-lang_scala-library-2.13.12";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.12/scala-library-2.13.12.pom"
    ];
    hash = "sha256-lXKrUcaYvYFyltW8AxZb1apsFCr5H/5I8oF8/QWDOKQ=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.12";
  };

  "org.scala-lang_scala-library-2.13.16" = fetchMaven {
    name = "org.scala-lang_scala-library-2.13.16";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.16/scala-library-2.13.16.pom"
    ];
    hash = "sha256-+ygUUESGmVq9UtUPN/d5dZq4kQqZgFEBpnT2H17NUkg=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.16";
  };

  "org.scala-lang_scala-library-2.13.18" = fetchMaven {
    name = "org.scala-lang_scala-library-2.13.18";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.pom"
    ];
    hash = "sha256-yvrsVgwMXWIDzG/kaiPRkYZQfLg7y/ViH2HRUcF8IgE=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18";
  };

  "org.scala-lang_scala-library-3.8.2" = fetchMaven {
    name = "org.scala-lang_scala-library-3.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala-library/3.8.2/scala-library-3.8.2.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala-library/3.8.2/scala-library-3.8.2.pom"
    ];
    hash = "sha256-Snkwgwio81/tE0qxNG1IzZx8ITh1g3xcGdquh+tKPx0=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala-library/3.8.2";
  };

  "org.scala-lang_scala-reflect-2.13.12" = fetchMaven {
    name = "org.scala-lang_scala-reflect-2.13.12";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.12/scala-reflect-2.13.12.pom"
    ];
    hash = "sha256-876jILtSkA9ukYfoR7hmf9IHypGGe0DoTxyiYlVVtRU=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.12";
  };

  "org.scala-lang_scala-reflect-2.13.18" = fetchMaven {
    name = "org.scala-lang_scala-reflect-2.13.18";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.pom"
    ];
    hash = "sha256-NUxw12IP7v+wF4LMxX3rfxlC+G6BTHcrBDrRZlHaWZU=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18";
  };

  "org.scala-lang_scala3-compiler_3-3.8.2" = fetchMaven {
    name = "org.scala-lang_scala3-compiler_3-3.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.8.2/scala3-compiler_3-3.8.2.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.8.2/scala3-compiler_3-3.8.2.pom"
    ];
    hash = "sha256-ZI3TfDBsGwisp7vyH5mpiWkBuBTlLZm3cMzUkQHxoLA=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/3.8.2";
  };

  "org.scala-lang_scala3-interfaces-3.8.2" = fetchMaven {
    name = "org.scala-lang_scala3-interfaces-3.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-interfaces/3.8.2/scala3-interfaces-3.8.2.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-interfaces/3.8.2/scala3-interfaces-3.8.2.pom"
    ];
    hash = "sha256-Sk3PsrJPCAa3zEsoeZ/0SE6skOL/ubKlg/uHW9sjpp4=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-interfaces/3.8.2";
  };

  "org.scala-lang_scala3-library_3-3.1.1" = fetchMaven {
    name = "org.scala-lang_scala3-library_3-3.1.1";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.1.1/scala3-library_3-3.1.1.pom"
    ];
    hash = "sha256-zX4l0BFytYHxZ+somhahXIMj1XB+wzyeR/MqOe5F+YE=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.1.1";
  };

  "org.scala-lang_scala3-library_3-3.3.3" = fetchMaven {
    name = "org.scala-lang_scala3-library_3-3.3.3";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.3.3/scala3-library_3-3.3.3.pom"
    ];
    hash = "sha256-i3C7/n+22pAbQ2xIxloGNiyrrKAsYI5z4xJ0lpNet98=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.3.3";
  };

  "org.scala-lang_scala3-library_3-3.7.4" = fetchMaven {
    name = "org.scala-lang_scala3-library_3-3.7.4";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.7.4/scala3-library_3-3.7.4.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.7.4/scala3-library_3-3.7.4.pom"
    ];
    hash = "sha256-n96MbSjNHeFV9QaEinPQhEZyRvFuYIAU0o9iSSlkmyA=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.7.4";
  };

  "org.scala-lang_scala3-library_3-3.8.2" = fetchMaven {
    name = "org.scala-lang_scala3-library_3-3.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.8.2/scala3-library_3-3.8.2.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.8.2/scala3-library_3-3.8.2.pom"
    ];
    hash = "sha256-hYJ9W6basrEvjTHpTGQExfRlJn04b7n4oq/i4ttogu4=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.8.2";
  };

  "org.scala-lang_scala3-repl_3-3.8.2" = fetchMaven {
    name = "org.scala-lang_scala3-repl_3-3.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-repl_3/3.8.2/scala3-repl_3-3.8.2.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-repl_3/3.8.2/scala3-repl_3-3.8.2.pom"
    ];
    hash = "sha256-vRyGDlOl1uIrBdG6NhCoBL/2VbxR7ELZQkrYEAb6bxE=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-repl_3/3.8.2";
  };

  "org.scala-lang_scala3-sbt-bridge-3.8.2" = fetchMaven {
    name = "org.scala-lang_scala3-sbt-bridge-3.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-sbt-bridge/3.8.2/scala3-sbt-bridge-3.8.2.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/scala3-sbt-bridge/3.8.2/scala3-sbt-bridge-3.8.2.pom"
    ];
    hash = "sha256-hYY8a2xw2mKbGH13gYKzSdtz9PM2hOvWMI2wVBzY++I=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/scala3-sbt-bridge/3.8.2";
  };

  "org.scala-lang_tasty-core_3-3.8.2" = fetchMaven {
    name = "org.scala-lang_tasty-core_3-3.8.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/tasty-core_3/3.8.2/tasty-core_3-3.8.2.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/tasty-core_3/3.8.2/tasty-core_3-3.8.2.pom"
    ];
    hash = "sha256-Hs0Z1VzlKjcL/senvHac9nzbOrb4+c0zP0QBhTTpMUM=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/tasty-core_3/3.8.2";
  };

  "org.scala-sbt_compiler-interface-1.10.7" = fetchMaven {
    name = "org.scala-sbt_compiler-interface-1.10.7";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/compiler-interface/1.10.7/compiler-interface-1.10.7.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/compiler-interface/1.10.7/compiler-interface-1.10.7.pom"
    ];
    hash = "sha256-nFVs4vEVTEPSiGce3C77TTjvffSU+SMrn9KgV9xGVP0=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/compiler-interface/1.10.7";
  };

  "org.scala-sbt_compiler-interface-1.9.5" = fetchMaven {
    name = "org.scala-sbt_compiler-interface-1.9.5";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/compiler-interface/1.9.5/compiler-interface-1.9.5.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/compiler-interface/1.9.5/compiler-interface-1.9.5.pom"
    ];
    hash = "sha256-/kx55BDpsnMpIqSGTHMg+zwfn4/8Ezvl/Lv3z+ClpnI=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/compiler-interface/1.9.5";
  };

  "org.scala-sbt_compiler-interface-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_compiler-interface-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/compiler-interface/2.0.0-M14/compiler-interface-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/compiler-interface/2.0.0-M14/compiler-interface-2.0.0-M14.pom"
    ];
    hash = "sha256-0hgJIgzhEGFQbn5kY1r7GXUjRUryUhvVXBUCAhZ9hxE=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/compiler-interface/2.0.0-M14";
  };

  "org.scala-sbt_io_3-1.10.5" = fetchMaven {
    name = "org.scala-sbt_io_3-1.10.5";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/io_3/1.10.5/io_3-1.10.5.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/io_3/1.10.5/io_3-1.10.5.pom"
    ];
    hash = "sha256-NlI2nqJd/cCVmbk+Qgv5EQ0sF+Vb3+r/ueQbUt43PPQ=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/io_3/1.10.5";
  };

  "org.scala-sbt_launcher-interface-1.5.2" = fetchMaven {
    name = "org.scala-sbt_launcher-interface-1.5.2";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/launcher-interface/1.5.2/launcher-interface-1.5.2.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/launcher-interface/1.5.2/launcher-interface-1.5.2.pom"
    ];
    hash = "sha256-6MKDhiypKx/Blnx11u6U5M+7JRobVIux55QiLAoNeyg=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/launcher-interface/1.5.2";
  };

  "org.scala-sbt_sbinary_3-0.5.1" = fetchMaven {
    name = "org.scala-sbt_sbinary_3-0.5.1";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/sbinary_3/0.5.1/sbinary_3-0.5.1.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/sbinary_3/0.5.1/sbinary_3-0.5.1.pom"
    ];
    hash = "sha256-tvZ+cEHn/1t9DEE5Q2RepIFyc1wMMpldIiXJxqDhMU8=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/sbinary_3/0.5.1";
  };

  "org.scala-sbt_test-interface-1.0" = fetchMaven {
    name = "org.scala-sbt_test-interface-1.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/test-interface/1.0/test-interface-1.0.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/test-interface/1.0/test-interface-1.0.pom"
    ];
    hash = "sha256-Cc5Q+4mULLHRdw+7Wjx6spCLbKrckXHeNYjIibw4LWw=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/test-interface/1.0";
  };

  "org.scala-sbt_util-control_3-2.0.0-RC8" = fetchMaven {
    name = "org.scala-sbt_util-control_3-2.0.0-RC8";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/util-control_3/2.0.0-RC8/util-control_3-2.0.0-RC8.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/util-control_3/2.0.0-RC8/util-control_3-2.0.0-RC8.pom"
    ];
    hash = "sha256-VUduUBpbhnbb8DCEXtRYKwd2iWT++ZqBy1Hnu3ulQC8=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/util-control_3/2.0.0-RC8";
  };

  "org.scala-sbt_util-core_3-2.0.0-RC8" = fetchMaven {
    name = "org.scala-sbt_util-core_3-2.0.0-RC8";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/util-core_3/2.0.0-RC8/util-core_3-2.0.0-RC8.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/util-core_3/2.0.0-RC8/util-core_3-2.0.0-RC8.pom"
    ];
    hash = "sha256-q5wa9sNGvZ8iYHBEAIf2MXWpthmFmZsUj++M30H6Kxc=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/util-core_3/2.0.0-RC8";
  };

  "org.scala-sbt_util-interface-1.10.7" = fetchMaven {
    name = "org.scala-sbt_util-interface-1.10.7";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/util-interface/1.10.7/util-interface-1.10.7.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/util-interface/1.10.7/util-interface-1.10.7.pom"
    ];
    hash = "sha256-cIOD5+vCDptOP6jwds5yG+23h2H54npBzGu3jrCQlvQ=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/util-interface/1.10.7";
  };

  "org.scala-sbt_util-interface-1.9.4" = fetchMaven {
    name = "org.scala-sbt_util-interface-1.9.4";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/util-interface/1.9.4/util-interface-1.9.4.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/util-interface/1.9.4/util-interface-1.9.4.pom"
    ];
    hash = "sha256-tljMmr/UKrmc5bPiEaEd962zf5zS1iavRbjSWZg+jxE=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/util-interface/1.9.4";
  };

  "org.scala-sbt_util-interface-2.0.0-RC8" = fetchMaven {
    name = "org.scala-sbt_util-interface-2.0.0-RC8";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/util-interface/2.0.0-RC8/util-interface-2.0.0-RC8.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/util-interface/2.0.0-RC8/util-interface-2.0.0-RC8.pom"
    ];
    hash = "sha256-S2NnUCakCYMY3bcOtZOSB+yU9bbtmYGZx5rOtwWWN98=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/util-interface/2.0.0-RC8";
  };

  "org.scala-sbt_util-logging_3-2.0.0-RC8" = fetchMaven {
    name = "org.scala-sbt_util-logging_3-2.0.0-RC8";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/util-logging_3/2.0.0-RC8/util-logging_3-2.0.0-RC8.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/util-logging_3/2.0.0-RC8/util-logging_3-2.0.0-RC8.pom"
    ];
    hash = "sha256-Ob/I3T/oMKIuzBd3cNYkGBZscLDGMfq3nYenCEu36Ts=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/util-logging_3/2.0.0-RC8";
  };

  "org.scala-sbt_util-relation_3-2.0.0-RC8" = fetchMaven {
    name = "org.scala-sbt_util-relation_3-2.0.0-RC8";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/util-relation_3/2.0.0-RC8/util-relation_3-2.0.0-RC8.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/util-relation_3/2.0.0-RC8/util-relation_3-2.0.0-RC8.pom"
    ];
    hash = "sha256-D+5pyCusW1hQh46iYEnsSxi/v6/cylGt4mSE225uQww=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/util-relation_3/2.0.0-RC8";
  };

  "org.scala-sbt_zinc-apiinfo_3-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_zinc-apiinfo_3-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-apiinfo_3/2.0.0-M14/zinc-apiinfo_3-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-apiinfo_3/2.0.0-M14/zinc-apiinfo_3-2.0.0-M14.pom"
    ];
    hash = "sha256-H4cbGGax/GXvJ9hk3dvASHODGJASsiWNlSjL1fIRj8o=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/zinc-apiinfo_3/2.0.0-M14";
  };

  "org.scala-sbt_zinc-classfile_3-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_zinc-classfile_3-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-classfile_3/2.0.0-M14/zinc-classfile_3-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-classfile_3/2.0.0-M14/zinc-classfile_3-2.0.0-M14.pom"
    ];
    hash = "sha256-EOQIVkZWoU926oZ4Rc1cb5klJqAwshIFRzrjBFyDNW8=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/zinc-classfile_3/2.0.0-M14";
  };

  "org.scala-sbt_zinc-classpath_3-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_zinc-classpath_3-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-classpath_3/2.0.0-M14/zinc-classpath_3-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-classpath_3/2.0.0-M14/zinc-classpath_3-2.0.0-M14.pom"
    ];
    hash = "sha256-zPsB5csia0voP5cJuuyheM9s0jj/p3Z8JO8B8nRhnp0=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/zinc-classpath_3/2.0.0-M14";
  };

  "org.scala-sbt_zinc-compile-core_3-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_zinc-compile-core_3-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-compile-core_3/2.0.0-M14/zinc-compile-core_3-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-compile-core_3/2.0.0-M14/zinc-compile-core_3-2.0.0-M14.pom"
    ];
    hash = "sha256-Aq4SRwMs1ydEV75m1JuDNPYsI9f2G/82MHygCk6XD8A=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/zinc-compile-core_3/2.0.0-M14";
  };

  "org.scala-sbt_zinc-core_3-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_zinc-core_3-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-core_3/2.0.0-M14/zinc-core_3-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-core_3/2.0.0-M14/zinc-core_3-2.0.0-M14.pom"
    ];
    hash = "sha256-AkTy4nVUA5wwRAGrJnSMiRLUBYKViDbr6Vc/Se6bWqs=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/zinc-core_3/2.0.0-M14";
  };

  "org.scala-sbt_zinc-persist_3-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_zinc-persist_3-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-persist_3/2.0.0-M14/zinc-persist_3-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc-persist_3/2.0.0-M14/zinc-persist_3-2.0.0-M14.pom"
    ];
    hash = "sha256-qF9Q7I7WsQOLTvNsn/C8799IkWGhamQVDoaN8e9YLHM=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/zinc-persist_3/2.0.0-M14";
  };

  "org.scala-sbt_zinc_3-2.0.0-M14" = fetchMaven {
    name = "org.scala-sbt_zinc_3-2.0.0-M14";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc_3/2.0.0-M14/zinc_3-2.0.0-M14.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/zinc_3/2.0.0-M14/zinc_3-2.0.0-M14.pom"
    ];
    hash = "sha256-DQWDyGSuFT2THbZVo1QfOpO+mqee21ip71wbqVmD8uM=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/zinc_3/2.0.0-M14";
  };

  "org.scalactic_scalactic_2.13-3.2.14" = fetchMaven {
    name = "org.scalactic_scalactic_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalactic/scalactic_2.13/3.2.14/scalactic_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalactic/scalactic_2.13/3.2.14/scalactic_2.13-3.2.14.pom"
    ];
    hash = "sha256-3XwgDBVYJoATQ3PIwZAyW7Xco0dPiEA/7D2tTHhRz7A=";
    installPath = "https/repo1.maven.org/maven2/org/scalactic/scalactic_2.13/3.2.14";
  };

  "org.scalatest_scalatest-compatible-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-compatible-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-compatible/3.2.14/scalatest-compatible-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-compatible/3.2.14/scalatest-compatible-3.2.14.pom"
    ];
    hash = "sha256-3AV+znFIQmsPaNUFVJzBfFWdwzNTh2Az7dxOsBVCKYI=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-compatible/3.2.14";
  };

  "org.scalatest_scalatest-core_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-core_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-core_2.13/3.2.14/scalatest-core_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-core_2.13/3.2.14/scalatest-core_2.13-3.2.14.pom"
    ];
    hash = "sha256-aNNLUamHiml0bpSYuiGLLU0iYYJqq7XWAJAXHZwObvU=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-core_2.13/3.2.14";
  };

  "org.scalatest_scalatest-diagrams_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-diagrams_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-diagrams_2.13/3.2.14/scalatest-diagrams_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-diagrams_2.13/3.2.14/scalatest-diagrams_2.13-3.2.14.pom"
    ];
    hash = "sha256-98kru4R4p06AxJykA+oA8ECOn6AvYLPo2mz1rS2x41c=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-diagrams_2.13/3.2.14";
  };

  "org.scalatest_scalatest-featurespec_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-featurespec_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-featurespec_2.13/3.2.14/scalatest-featurespec_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-featurespec_2.13/3.2.14/scalatest-featurespec_2.13-3.2.14.pom"
    ];
    hash = "sha256-0YiOQ++JTrjrn642nZ+pIAw6LhaG1KvL1Swv6ioyF4E=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-featurespec_2.13/3.2.14";
  };

  "org.scalatest_scalatest-flatspec_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-flatspec_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-flatspec_2.13/3.2.14/scalatest-flatspec_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-flatspec_2.13/3.2.14/scalatest-flatspec_2.13-3.2.14.pom"
    ];
    hash = "sha256-x7x7rSsGeIx0r5WO+IyPk7nCwMnrX4Rb5NliS6P30ks=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-flatspec_2.13/3.2.14";
  };

  "org.scalatest_scalatest-freespec_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-freespec_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-freespec_2.13/3.2.14/scalatest-freespec_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-freespec_2.13/3.2.14/scalatest-freespec_2.13-3.2.14.pom"
    ];
    hash = "sha256-4tmGWRrnlgJcpNpxhc0/MPbx+HpXQ1NAPfaO1DnCScU=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-freespec_2.13/3.2.14";
  };

  "org.scalatest_scalatest-funspec_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-funspec_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-funspec_2.13/3.2.14/scalatest-funspec_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-funspec_2.13/3.2.14/scalatest-funspec_2.13-3.2.14.pom"
    ];
    hash = "sha256-KqQxHjvC++WZ4h2xOwlB63Sec9vi2NrnGZAyZV+y+zw=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-funspec_2.13/3.2.14";
  };

  "org.scalatest_scalatest-funsuite_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-funsuite_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-funsuite_2.13/3.2.14/scalatest-funsuite_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-funsuite_2.13/3.2.14/scalatest-funsuite_2.13-3.2.14.pom"
    ];
    hash = "sha256-UxS8MRu8txcf5HK2kV1SMUhVnzriVeIKmVQrTZl8gjg=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-funsuite_2.13/3.2.14";
  };

  "org.scalatest_scalatest-matchers-core_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-matchers-core_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-matchers-core_2.13/3.2.14/scalatest-matchers-core_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-matchers-core_2.13/3.2.14/scalatest-matchers-core_2.13-3.2.14.pom"
    ];
    hash = "sha256-1wDWDT6BOF2b1fcFcsD13c7onqLxnBqMwWZ69M6V248=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-matchers-core_2.13/3.2.14";
  };

  "org.scalatest_scalatest-mustmatchers_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-mustmatchers_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-mustmatchers_2.13/3.2.14/scalatest-mustmatchers_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-mustmatchers_2.13/3.2.14/scalatest-mustmatchers_2.13-3.2.14.pom"
    ];
    hash = "sha256-z6AI1IztjUFdufRIMUff9HUyIeKYSYBgYskIKb2KFNk=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-mustmatchers_2.13/3.2.14";
  };

  "org.scalatest_scalatest-propspec_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-propspec_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-propspec_2.13/3.2.14/scalatest-propspec_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-propspec_2.13/3.2.14/scalatest-propspec_2.13-3.2.14.pom"
    ];
    hash = "sha256-YV8/0mMtj5UmXX4fI3pcTSbfCoAnhIO8Ql9P/EiK36o=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-propspec_2.13/3.2.14";
  };

  "org.scalatest_scalatest-refspec_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-refspec_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-refspec_2.13/3.2.14/scalatest-refspec_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-refspec_2.13/3.2.14/scalatest-refspec_2.13-3.2.14.pom"
    ];
    hash = "sha256-Ena4WKCos6eMP2n6MO9kKEXV+EDCypN8jG2MsYK+cL8=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-refspec_2.13/3.2.14";
  };

  "org.scalatest_scalatest-shouldmatchers_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-shouldmatchers_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-shouldmatchers_2.13/3.2.14/scalatest-shouldmatchers_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-shouldmatchers_2.13/3.2.14/scalatest-shouldmatchers_2.13-3.2.14.pom"
    ];
    hash = "sha256-ORkVg5ypdtsZdI04bnAGaLnXxrj3Z+Z2sBtPmoR+oCU=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-shouldmatchers_2.13/3.2.14";
  };

  "org.scalatest_scalatest-wordspec_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest-wordspec_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-wordspec_2.13/3.2.14/scalatest-wordspec_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest-wordspec_2.13/3.2.14/scalatest-wordspec_2.13-3.2.14.pom"
    ];
    hash = "sha256-VWVZuWBO10KDGRDhDThfhXF09Q45LkG3TOGBa5aUDAE=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest-wordspec_2.13/3.2.14";
  };

  "org.scalatest_scalatest_2.13-3.2.14" = fetchMaven {
    name = "org.scalatest_scalatest_2.13-3.2.14";
    urls = [
      "https://repo1.maven.org/maven2/org/scalatest/scalatest_2.13/3.2.14/scalatest_2.13-3.2.14.jar"
      "https://repo1.maven.org/maven2/org/scalatest/scalatest_2.13/3.2.14/scalatest_2.13-3.2.14.pom"
    ];
    hash = "sha256-NhML5OeIY8I9CODhcmvqJ3cNrsXI/1EB50w2233lKHM=";
    installPath = "https/repo1.maven.org/maven2/org/scalatest/scalatest_2.13/3.2.14";
  };

  "org.slf4j_slf4j-api-1.7.26" = fetchMaven {
    name = "org.slf4j_slf4j-api-1.7.26";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.26/slf4j-api-1.7.26.pom" ];
    hash = "sha256-uCW6PnnDeMIjQnPIYLiTWsviGAmMxWhHzGyCzotn/Y0=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.26";
  };

  "org.slf4j_slf4j-api-1.7.36" = fetchMaven {
    name = "org.slf4j_slf4j-api-1.7.36";
    urls = [
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.pom"
    ];
    hash = "sha256-Y5+xtmk/NH4v8ol1MqMr+2spKmRMVkcTL6QS1ko2EGM=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36";
  };

  "org.slf4j_slf4j-api-2.0.17" = fetchMaven {
    name = "org.slf4j_slf4j-api-2.0.17";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.pom" ];
    hash = "sha256-k3R2w4HAwzuaDhSmdJqQZsaQc//qKvR4KG7kLk5tLa8=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.17";
  };

  "org.slf4j_slf4j-api-2.0.18" = fetchMaven {
    name = "org.slf4j_slf4j-api-2.0.18";
    urls = [
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar"
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.pom"
    ];
    hash = "sha256-jfchEMdO1prQyx8Ft+2JgtT63b0RpgNNNvEoIRALWjo=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.18";
  };

  "org.slf4j_slf4j-api-2.0.5" = fetchMaven {
    name = "org.slf4j_slf4j-api-2.0.5";
    urls = [
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.5/slf4j-api-2.0.5.jar"
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.5/slf4j-api-2.0.5.pom"
    ];
    hash = "sha256-Bsm/hXNdMH4NupK9QR50i3UJhg6YC8l1KvJqj2oghXQ=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.5";
  };

  "org.slf4j_slf4j-bom-2.0.17" = fetchMaven {
    name = "org.slf4j_slf4j-bom-2.0.17";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-bom/2.0.17/slf4j-bom-2.0.17.pom" ];
    hash = "sha256-qzVo4Yw93XWPRmfJurfoPZ/b9JSCgRngTQmCG6cRwMA=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-bom/2.0.17";
  };

  "org.slf4j_slf4j-bom-2.0.18" = fetchMaven {
    name = "org.slf4j_slf4j-bom-2.0.18";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-bom/2.0.18/slf4j-bom-2.0.18.pom" ];
    hash = "sha256-VSfeks0/LsQDcRtK7HjSpAevOca6DoMsXyy6EMh8l0g=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-bom/2.0.18";
  };

  "org.slf4j_slf4j-parent-1.7.26" = fetchMaven {
    name = "org.slf4j_slf4j-parent-1.7.26";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-parent/1.7.26/slf4j-parent-1.7.26.pom" ];
    hash = "sha256-N2fy8uiQFAtusQO9ONhgfmT+vmSuygopuMrhW14eYLc=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-parent/1.7.26";
  };

  "org.slf4j_slf4j-parent-1.7.36" = fetchMaven {
    name = "org.slf4j_slf4j-parent-1.7.36";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-parent/1.7.36/slf4j-parent-1.7.36.pom" ];
    hash = "sha256-XOPBamOj/h7sQV4eY3tVJqwkhSPdS1EAqfeZruNTLGM=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-parent/1.7.36";
  };

  "org.slf4j_slf4j-parent-2.0.17" = fetchMaven {
    name = "org.slf4j_slf4j-parent-2.0.17";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-parent/2.0.17/slf4j-parent-2.0.17.pom" ];
    hash = "sha256-H/5UPMMiEV8gCId3abw3znMuG9wWYSMevo6t1zUGACw=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-parent/2.0.17";
  };

  "org.slf4j_slf4j-parent-2.0.18" = fetchMaven {
    name = "org.slf4j_slf4j-parent-2.0.18";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-parent/2.0.18/slf4j-parent-2.0.18.pom" ];
    hash = "sha256-ATvCTCYnoZGeyQbN3MTgdyjU8dL3Y28DM5k3clL/b2w=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-parent/2.0.18";
  };

  "org.slf4j_slf4j-parent-2.0.5" = fetchMaven {
    name = "org.slf4j_slf4j-parent-2.0.5";
    urls = [ "https://repo1.maven.org/maven2/org/slf4j/slf4j-parent/2.0.5/slf4j-parent-2.0.5.pom" ];
    hash = "sha256-Q/WUnplYPsdMayiunoptnMKI5En/EyVLZOWyLbNQHr4=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-parent/2.0.5";
  };

  "org.slf4j_slf4j-simple-2.0.5" = fetchMaven {
    name = "org.slf4j_slf4j-simple-2.0.5";
    urls = [
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.5/slf4j-simple-2.0.5.jar"
      "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.5/slf4j-simple-2.0.5.pom"
    ];
    hash = "sha256-iG2aqlORX9OKDRrm++8pkY2zPUhP6rchYWS/CkSU6P8=";
    installPath = "https/repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.5";
  };

  "org.snakeyaml_snakeyaml-engine-3.0.1" = fetchMaven {
    name = "org.snakeyaml_snakeyaml-engine-3.0.1";
    urls = [
      "https://repo1.maven.org/maven2/org/snakeyaml/snakeyaml-engine/3.0.1/snakeyaml-engine-3.0.1.jar"
      "https://repo1.maven.org/maven2/org/snakeyaml/snakeyaml-engine/3.0.1/snakeyaml-engine-3.0.1.pom"
    ];
    hash = "sha256-7iAdeB8CZ2r3BEAfH+j7Qfk6Dk9X/ThHDOaJrAS9gXY=";
    installPath = "https/repo1.maven.org/maven2/org/snakeyaml/snakeyaml-engine/3.0.1";
  };

  "org.springframework_spring-framework-bom-5.3.39" = fetchMaven {
    name = "org.springframework_spring-framework-bom-5.3.39";
    urls = [
      "https://repo1.maven.org/maven2/org/springframework/spring-framework-bom/5.3.39/spring-framework-bom-5.3.39.pom"
    ];
    hash = "sha256-V+sR9AvokPz2NrvEFCxdLHl3jrW2o9dP3gisCDAUUDA=";
    installPath = "https/repo1.maven.org/maven2/org/springframework/spring-framework-bom/5.3.39";
  };

  "org.testcontainers_testcontainers-bom-2.0.5" = fetchMaven {
    name = "org.testcontainers_testcontainers-bom-2.0.5";
    urls = [
      "https://repo1.maven.org/maven2/org/testcontainers/testcontainers-bom/2.0.5/testcontainers-bom-2.0.5.pom"
    ];
    hash = "sha256-OPYj5b7iUTykyPnZZLsFCzqgKMgS4+GPl6TBcYtayaM=";
    installPath = "https/repo1.maven.org/maven2/org/testcontainers/testcontainers-bom/2.0.5";
  };

  "org.tukaani_xz-1.12" = fetchMaven {
    name = "org.tukaani_xz-1.12";
    urls = [
      "https://repo1.maven.org/maven2/org/tukaani/xz/1.12/xz-1.12.jar"
      "https://repo1.maven.org/maven2/org/tukaani/xz/1.12/xz-1.12.pom"
    ];
    hash = "sha256-Q6VfWiy4aA2O7ikTKRbpfOYQvTuid+i1PMEsrrbbMBQ=";
    installPath = "https/repo1.maven.org/maven2/org/tukaani/xz/1.12";
  };

  "org.virtuslab_using_directives-1.1.4" = fetchMaven {
    name = "org.virtuslab_using_directives-1.1.4";
    urls = [
      "https://repo1.maven.org/maven2/org/virtuslab/using_directives/1.1.4/using_directives-1.1.4.jar"
      "https://repo1.maven.org/maven2/org/virtuslab/using_directives/1.1.4/using_directives-1.1.4.pom"
    ];
    hash = "sha256-pnvGXmfkY+Ab7VI/5wL15RkxdE6LZ9hjfBL/UMVejUI=";
    installPath = "https/repo1.maven.org/maven2/org/virtuslab/using_directives/1.1.4";
  };

  "ch.qos.logback_logback-classic-1.5.37" = fetchMaven {
    name = "ch.qos.logback_logback-classic-1.5.37";
    urls = [
      "https://repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.5.37/logback-classic-1.5.37.jar"
      "https://repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.5.37/logback-classic-1.5.37.pom"
    ];
    hash = "sha256-WZE9Az6bRcfBhkgLqLDmuYROGYnfA1XIRq0aTN/1tuQ=";
    installPath = "https/repo1.maven.org/maven2/ch/qos/logback/logback-classic/1.5.37";
  };

  "ch.qos.logback_logback-core-1.5.37" = fetchMaven {
    name = "ch.qos.logback_logback-core-1.5.37";
    urls = [
      "https://repo1.maven.org/maven2/ch/qos/logback/logback-core/1.5.37/logback-core-1.5.37.jar"
      "https://repo1.maven.org/maven2/ch/qos/logback/logback-core/1.5.37/logback-core-1.5.37.pom"
    ];
    hash = "sha256-VhepNF5YAU9GzIfvvBU7YLrO1eiVaWb/45uYbWmUjy8=";
    installPath = "https/repo1.maven.org/maven2/ch/qos/logback/logback-core/1.5.37";
  };

  "ch.qos.logback_logback-parent-1.5.37" = fetchMaven {
    name = "ch.qos.logback_logback-parent-1.5.37";
    urls = [
      "https://repo1.maven.org/maven2/ch/qos/logback/logback-parent/1.5.37/logback-parent-1.5.37.pom"
    ];
    hash = "sha256-bEeztFaNSAHh1cM9C7fjC71xbYNS07FSdCR5l00g2DU=";
    installPath = "https/repo1.maven.org/maven2/ch/qos/logback/logback-parent/1.5.37";
  };

  "com.eed3si9n.jarjar_jarjar-1.16.0" = fetchMaven {
    name = "com.eed3si9n.jarjar_jarjar-1.16.0";
    urls = [
      "https://repo1.maven.org/maven2/com/eed3si9n/jarjar/jarjar/1.16.0/jarjar-1.16.0.jar"
      "https://repo1.maven.org/maven2/com/eed3si9n/jarjar/jarjar/1.16.0/jarjar-1.16.0.pom"
    ];
    hash = "sha256-7yeu2GEZWT35zIdhx12PASb7z1iRTbfLqInMmcWgOnY=";
    installPath = "https/repo1.maven.org/maven2/com/eed3si9n/jarjar/jarjar/1.16.0";
  };

  "com.eed3si9n.jarjarabrams_jarjar-abrams-core_3-1.16.0" = fetchMaven {
    name = "com.eed3si9n.jarjarabrams_jarjar-abrams-core_3-1.16.0";
    urls = [
      "https://repo1.maven.org/maven2/com/eed3si9n/jarjarabrams/jarjar-abrams-core_3/1.16.0/jarjar-abrams-core_3-1.16.0.jar"
      "https://repo1.maven.org/maven2/com/eed3si9n/jarjarabrams/jarjar-abrams-core_3/1.16.0/jarjar-abrams-core_3-1.16.0.pom"
    ];
    hash = "sha256-W5DKe0MH8VvL8ijEhNThoGF+XDs2C2AL1vYh9VdWf2I=";
    installPath = "https/repo1.maven.org/maven2/com/eed3si9n/jarjarabrams/jarjar-abrams-core_3/1.16.0";
  };

  "com.fasterxml.jackson_jackson-bom-2.19.2" = fetchMaven {
    name = "com.fasterxml.jackson_jackson-bom-2.19.2";
    urls = [
      "https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-bom/2.19.2/jackson-bom-2.19.2.pom"
    ];
    hash = "sha256-XrLBhVEzqBqa3dXh8GHjdvDM/aMAKB0HgdkGLl1Zi3g=";
    installPath = "https/repo1.maven.org/maven2/com/fasterxml/jackson/jackson-bom/2.19.2";
  };

  "com.fasterxml.jackson_jackson-bom-2.21.3" = fetchMaven {
    name = "com.fasterxml.jackson_jackson-bom-2.21.3";
    urls = [
      "https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-bom/2.21.3/jackson-bom-2.21.3.pom"
    ];
    hash = "sha256-Ebl8VZETMBxvuIA+IdkE3dMvTax6rKC4SB6DIQEtSs8=";
    installPath = "https/repo1.maven.org/maven2/com/fasterxml/jackson/jackson-bom/2.21.3";
  };

  "com.fasterxml.jackson_jackson-parent-2.19.3" = fetchMaven {
    name = "com.fasterxml.jackson_jackson-parent-2.19.3";
    urls = [
      "https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-parent/2.19.3/jackson-parent-2.19.3.pom"
    ];
    hash = "sha256-GB3zF0z0V1AtR3lbqQEBXSTp5AcLgqKAm6+//qyIO6E=";
    installPath = "https/repo1.maven.org/maven2/com/fasterxml/jackson/jackson-parent/2.19.3";
  };

  "com.fasterxml.jackson_jackson-parent-2.21" = fetchMaven {
    name = "com.fasterxml.jackson_jackson-parent-2.21";
    urls = [
      "https://repo1.maven.org/maven2/com/fasterxml/jackson/jackson-parent/2.21/jackson-parent-2.21.pom"
    ];
    hash = "sha256-CxE5EjSDvbsAaVIKZxboll5nTSh/SFyFf3r1NZriDo4=";
    installPath = "https/repo1.maven.org/maven2/com/fasterxml/jackson/jackson-parent/2.21";
  };

  "com.github.javaparser_javaparser-core-3.28.1" = fetchMaven {
    name = "com.github.javaparser_javaparser-core-3.28.1";
    urls = [
      "https://repo1.maven.org/maven2/com/github/javaparser/javaparser-core/3.28.1/javaparser-core-3.28.1.jar"
      "https://repo1.maven.org/maven2/com/github/javaparser/javaparser-core/3.28.1/javaparser-core-3.28.1.pom"
    ];
    hash = "sha256-sS9JVIPHbNWNWqbtICPXmURoPOsHIKy6q8ZbGvC4PMs=";
    installPath = "https/repo1.maven.org/maven2/com/github/javaparser/javaparser-core/3.28.1";
  };

  "com.github.javaparser_javaparser-parent-3.28.1" = fetchMaven {
    name = "com.github.javaparser_javaparser-parent-3.28.1";
    urls = [
      "https://repo1.maven.org/maven2/com/github/javaparser/javaparser-parent/3.28.1/javaparser-parent-3.28.1.pom"
    ];
    hash = "sha256-RGf/dxvAvNesSOXnoQ3nlvAAqwih2IqglGh+gNRhHNE=";
    installPath = "https/repo1.maven.org/maven2/com/github/javaparser/javaparser-parent/3.28.1";
  };

  "com.github.luben_zstd-jni-1.5.7-11" = fetchMaven {
    name = "com.github.luben_zstd-jni-1.5.7-11";
    urls = [
      "https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-11/zstd-jni-1.5.7-11.jar"
      "https://repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-11/zstd-jni-1.5.7-11.pom"
    ];
    hash = "sha256-bdboqHHvtUOZv81BKCjs4FlheXHnW2FQdXtvGiG8iuQ=";
    installPath = "https/repo1.maven.org/maven2/com/github/luben/zstd-jni/1.5.7-11";
  };

  "com.github.oshi_oshi-core-6.4.0" = fetchMaven {
    name = "com.github.oshi_oshi-core-6.4.0";
    urls = [
      "https://repo1.maven.org/maven2/com/github/oshi/oshi-core/6.4.0/oshi-core-6.4.0.jar"
      "https://repo1.maven.org/maven2/com/github/oshi/oshi-core/6.4.0/oshi-core-6.4.0.pom"
    ];
    hash = "sha256-4danhdLyGSpoowVXLA/4hMNQ0dLsM5gk3//HjLpypVs=";
    installPath = "https/repo1.maven.org/maven2/com/github/oshi/oshi-core/6.4.0";
  };

  "com.github.oshi_oshi-parent-6.4.0" = fetchMaven {
    name = "com.github.oshi_oshi-parent-6.4.0";
    urls = [ "https://repo1.maven.org/maven2/com/github/oshi/oshi-parent/6.4.0/oshi-parent-6.4.0.pom" ];
    hash = "sha256-OJvQP6p0HwQvDk0J+O56Muuu2vuk9yoJiXHLi5VnNVk=";
    installPath = "https/repo1.maven.org/maven2/com/github/oshi/oshi-parent/6.4.0";
  };

  "com.github.scopt_scopt_2.13-4.1.0" = fetchMaven {
    name = "com.github.scopt_scopt_2.13-4.1.0";
    urls = [
      "https://repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar"
      "https://repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.pom"
    ];
    hash = "sha256-8vlB7LBM6HNfmGOrsljlfCJ0SbMMpqR2Kmo9QWAKzJ8=";
    installPath = "https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0";
  };

  "io.get-coursier.jniutils_windows-jni-utils-0.3.4" = fetchMaven {
    name = "io.get-coursier.jniutils_windows-jni-utils-0.3.4";
    urls = [
      "https://repo1.maven.org/maven2/io/get-coursier/jniutils/windows-jni-utils/0.3.4/windows-jni-utils-0.3.4.jar"
      "https://repo1.maven.org/maven2/io/get-coursier/jniutils/windows-jni-utils/0.3.4/windows-jni-utils-0.3.4.pom"
    ];
    hash = "sha256-dToivzB8XSIZqJhujR16A9ECzWfbaJt7cgRZg6rO8EM=";
    installPath = "https/repo1.maven.org/maven2/io/get-coursier/jniutils/windows-jni-utils/0.3.4";
  };

  "io.github.alexarchambault_concurrent-reference-hash-map-1.1.0" = fetchMaven {
    name = "io.github.alexarchambault_concurrent-reference-hash-map-1.1.0";
    urls = [
      "https://repo1.maven.org/maven2/io/github/alexarchambault/concurrent-reference-hash-map/1.1.0/concurrent-reference-hash-map-1.1.0.jar"
      "https://repo1.maven.org/maven2/io/github/alexarchambault/concurrent-reference-hash-map/1.1.0/concurrent-reference-hash-map-1.1.0.pom"
    ];
    hash = "sha256-949g3dbXxz773bZlkiK2Xh3XiY5Ofc+1k6i8LM6s+yI=";
    installPath = "https/repo1.maven.org/maven2/io/github/alexarchambault/concurrent-reference-hash-map/1.1.0";
  };

  "io.github.alexarchambault_is-terminal-0.1.2" = fetchMaven {
    name = "io.github.alexarchambault_is-terminal-0.1.2";
    urls = [
      "https://repo1.maven.org/maven2/io/github/alexarchambault/is-terminal/0.1.2/is-terminal-0.1.2.jar"
      "https://repo1.maven.org/maven2/io/github/alexarchambault/is-terminal/0.1.2/is-terminal-0.1.2.pom"
    ];
    hash = "sha256-j9aW4Y/zyD4aYu2XykzfEpdGUXideUCkVTFSvtzlH48=";
    installPath = "https/repo1.maven.org/maven2/io/github/alexarchambault/is-terminal/0.1.2";
  };

  "io.github.classgraph_classgraph-4.8.184" = fetchMaven {
    name = "io.github.classgraph_classgraph-4.8.184";
    urls = [
      "https://repo1.maven.org/maven2/io/github/classgraph/classgraph/4.8.184/classgraph-4.8.184.jar"
      "https://repo1.maven.org/maven2/io/github/classgraph/classgraph/4.8.184/classgraph-4.8.184.pom"
    ];
    hash = "sha256-TexK9sAgGTT4cAEZYYyi1pq/J2XPQFHMlvzzRSs1Eok=";
    installPath = "https/repo1.maven.org/maven2/io/github/classgraph/classgraph/4.8.184";
  };

  "io.github.java-diff-utils_java-diff-utils-4.12" = fetchMaven {
    name = "io.github.java-diff-utils_java-diff-utils-4.12";
    urls = [
      "https://repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils/4.12/java-diff-utils-4.12.jar"
      "https://repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils/4.12/java-diff-utils-4.12.pom"
    ];
    hash = "sha256-SMNRfv+BvfxjgwFH0fHU16fd1bDn/QMrPQN8Eyb6deA=";
    installPath = "https/repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils/4.12";
  };

  "io.github.java-diff-utils_java-diff-utils-parent-4.12" = fetchMaven {
    name = "io.github.java-diff-utils_java-diff-utils-parent-4.12";
    urls = [
      "https://repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils-parent/4.12/java-diff-utils-parent-4.12.pom"
    ];
    hash = "sha256-l9MekOAkDQrHpgMMLkbZQJtiaSmyE7h0XneiHciAFOI=";
    installPath = "https/repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils-parent/4.12";
  };

  "io.github.zhaokunhu_ipxactscalacases_2.13-0.0.3" = fetchMaven {
    name = "io.github.zhaokunhu_ipxactscalacases_2.13-0.0.3";
    urls = [
      "https://repo1.maven.org/maven2/io/github/zhaokunhu/ipxactscalacases_2.13/0.0.3/ipxactscalacases_2.13-0.0.3.jar"
      "https://repo1.maven.org/maven2/io/github/zhaokunhu/ipxactscalacases_2.13/0.0.3/ipxactscalacases_2.13-0.0.3.pom"
    ];
    hash = "sha256-hIRg4pUiGP8dzrLO5juCAfK5IUWplfrWMh3K49vVsJ8=";
    installPath = "https/repo1.maven.org/maven2/io/github/zhaokunhu/ipxactscalacases_2.13/0.0.3";
  };

  "javax.xml.bind_jaxb-api-2.3.0" = fetchMaven {
    name = "javax.xml.bind_jaxb-api-2.3.0";
    urls = [
      "https://repo1.maven.org/maven2/javax/xml/bind/jaxb-api/2.3.0/jaxb-api-2.3.0.jar"
      "https://repo1.maven.org/maven2/javax/xml/bind/jaxb-api/2.3.0/jaxb-api-2.3.0.pom"
    ];
    hash = "sha256-yyg4/neCg40NXaeA9/VrR1kxtdDlDzWfP7qVmPNxGRY=";
    installPath = "https/repo1.maven.org/maven2/javax/xml/bind/jaxb-api/2.3.0";
  };

  "javax.xml.bind_jaxb-api-parent-2.3.0" = fetchMaven {
    name = "javax.xml.bind_jaxb-api-parent-2.3.0";
    urls = [
      "https://repo1.maven.org/maven2/javax/xml/bind/jaxb-api-parent/2.3.0/jaxb-api-parent-2.3.0.pom"
    ];
    hash = "sha256-eOxiyzlUmLsA2Dpg2BcTCtDzgzkMsFAgj6vMinzpB0k=";
    installPath = "https/repo1.maven.org/maven2/javax/xml/bind/jaxb-api-parent/2.3.0";
  };

  "org.apache.commons_commons-compress-1.28.0" = fetchMaven {
    name = "org.apache.commons_commons-compress-1.28.0";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar"
      "https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.pom"
    ];
    hash = "sha256-dT70h6cIwxdIJVO9eFn4/P1q2530bl+046ZjQ3EVGgU=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-compress/1.28.0";
  };

  "org.apache.commons_commons-lang3-3.18.0" = fetchMaven {
    name = "org.apache.commons_commons-lang3-3.18.0";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.18.0/commons-lang3-3.18.0.jar"
      "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.18.0/commons-lang3-3.18.0.pom"
    ];
    hash = "sha256-IzLzlGs2SlGRKZ+baXEo/jh3JY2oTXa5wdIl2KlBy2E=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.18.0";
  };

  "org.apache.commons_commons-lang3-3.8.1" = fetchMaven {
    name = "org.apache.commons_commons-lang3-3.8.1";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.8.1/commons-lang3-3.8.1.pom"
    ];
    hash = "sha256-sRwL9YM4DOzOxwPnBOgJyanP0m39AKrpy4hbtdM12q0=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.8.1";
  };

  "org.apache.commons_commons-parent-47" = fetchMaven {
    name = "org.apache.commons_commons-parent-47";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-parent/47/commons-parent-47.pom"
    ];
    hash = "sha256-3nKXz/Cqz3ed8sPyeJUIYW5uQ/1nCy8N5gPATIkI9DQ=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-parent/47";
  };

  "org.apache.commons_commons-parent-52" = fetchMaven {
    name = "org.apache.commons_commons-parent-52";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-parent/52/commons-parent-52.pom"
    ];
    hash = "sha256-dqVIUNPKTtXecIQpdWWBzjqj723nzq5Qns010Sihuls=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-parent/52";
  };

  "org.apache.commons_commons-parent-65" = fetchMaven {
    name = "org.apache.commons_commons-parent-65";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-parent/65/commons-parent-65.pom"
    ];
    hash = "sha256-j6/Ow03HZq3FUSjuGHhKKwJ7JMRYLIy4cV7sE2IgJVA=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-parent/65";
  };

  "org.apache.commons_commons-parent-85" = fetchMaven {
    name = "org.apache.commons_commons-parent-85";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-parent/85/commons-parent-85.pom"
    ];
    hash = "sha256-Xj15VDFcZhPE5qzqmellS3KSQJENr1wgjG5fOKbbyJA=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-parent/85";
  };

  "org.apache.commons_commons-parent-98" = fetchMaven {
    name = "org.apache.commons_commons-parent-98";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/commons/commons-parent/98/commons-parent-98.pom"
    ];
    hash = "sha256-rSXLbEhu+tbFsHjf3AEcqAzBs5EgqtaM87b+FaZRSds=";
    installPath = "https/repo1.maven.org/maven2/org/apache/commons/commons-parent/98";
  };

  "org.apache.cxf_cxf-4.0.11" = fetchMaven {
    name = "org.apache.cxf_cxf-4.0.11";
    urls = [ "https://repo1.maven.org/maven2/org/apache/cxf/cxf/4.0.11/cxf-4.0.11.pom" ];
    hash = "sha256-86Mg7KQjUOZM6uyFPB2EbIepkhigiP8FCoHFApqptKE=";
    installPath = "https/repo1.maven.org/maven2/org/apache/cxf/cxf/4.0.11";
  };

  "org.apache.cxf_cxf-bom-4.0.11" = fetchMaven {
    name = "org.apache.cxf_cxf-bom-4.0.11";
    urls = [ "https://repo1.maven.org/maven2/org/apache/cxf/cxf-bom/4.0.11/cxf-bom-4.0.11.pom" ];
    hash = "sha256-mVu4p3TiMhbJ4NETzP2f0YYjChcZ+3vQxBMoFbXfsGg=";
    installPath = "https/repo1.maven.org/maven2/org/apache/cxf/cxf-bom/4.0.11";
  };

  "org.apache.groovy_groovy-bom-4.0.27" = fetchMaven {
    name = "org.apache.groovy_groovy-bom-4.0.27";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/groovy/groovy-bom/4.0.27/groovy-bom-4.0.27.pom"
    ];
    hash = "sha256-LpFKTYYoMwe70YPF1kycfpUSmm2Q+G+KtWRPny2CupQ=";
    installPath = "https/repo1.maven.org/maven2/org/apache/groovy/groovy-bom/4.0.27";
  };

  "org.apache.tika_tika-core-3.3.1" = fetchMaven {
    name = "org.apache.tika_tika-core-3.3.1";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/tika/tika-core/3.3.1/tika-core-3.3.1.jar"
      "https://repo1.maven.org/maven2/org/apache/tika/tika-core/3.3.1/tika-core-3.3.1.pom"
    ];
    hash = "sha256-yIdila2XKGdsdMosqU70DZVKLgo58D9YnnJXCksTyis=";
    installPath = "https/repo1.maven.org/maven2/org/apache/tika/tika-core/3.3.1";
  };

  "org.apache.tika_tika-parent-3.3.1" = fetchMaven {
    name = "org.apache.tika_tika-parent-3.3.1";
    urls = [ "https://repo1.maven.org/maven2/org/apache/tika/tika-parent/3.3.1/tika-parent-3.3.1.pom" ];
    hash = "sha256-iBftKSZxQgwHusHC55TtjTd8UmAmZDbLQ4IaQpyUJLc=";
    installPath = "https/repo1.maven.org/maven2/org/apache/tika/tika-parent/3.3.1";
  };

  "org.apache.xbean_xbean-3.7" = fetchMaven {
    name = "org.apache.xbean_xbean-3.7";
    urls = [ "https://repo1.maven.org/maven2/org/apache/xbean/xbean/3.7/xbean-3.7.pom" ];
    hash = "sha256-7moEcdxl+B1i7xstWBlWabSFr9QLszuciySggKYvpAE=";
    installPath = "https/repo1.maven.org/maven2/org/apache/xbean/xbean/3.7";
  };

  "org.apache.xbean_xbean-reflect-3.7" = fetchMaven {
    name = "org.apache.xbean_xbean-reflect-3.7";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/xbean/xbean-reflect/3.7/xbean-reflect-3.7.jar"
      "https://repo1.maven.org/maven2/org/apache/xbean/xbean-reflect/3.7/xbean-reflect-3.7.pom"
    ];
    hash = "sha256-Zp97nk/YwipUj92NnhjU5tKNXgUmPWh2zWic2FoS434=";
    installPath = "https/repo1.maven.org/maven2/org/apache/xbean/xbean-reflect/3.7";
  };

  "org.codehaus.plexus_plexus-24" = fetchMaven {
    name = "org.codehaus.plexus_plexus-24";
    urls = [ "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus/24/plexus-24.pom" ];
    hash = "sha256-qVSGrS5AJ+rMvOXbPXAuoFtJ13okp6qudUjdd5dsNMI=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus/24";
  };

  "org.codehaus.plexus_plexus-25" = fetchMaven {
    name = "org.codehaus.plexus_plexus-25";
    urls = [ "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus/25/plexus-25.pom" ];
    hash = "sha256-nMrTeBDzzD5BwU5eWOkTSkaIB0wPbBXXL6sFMycSZm4=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus/25";
  };

  "org.codehaus.plexus_plexus-5.1" = fetchMaven {
    name = "org.codehaus.plexus_plexus-5.1";
    urls = [ "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus/5.1/plexus-5.1.pom" ];
    hash = "sha256-ywTicwjHcL7BzKPO3XzXpc9pE0M0j7Khcop85G3XqDI=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus/5.1";
  };

  "org.codehaus.plexus_plexus-6.5" = fetchMaven {
    name = "org.codehaus.plexus_plexus-6.5";
    urls = [ "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus/6.5/plexus-6.5.pom" ];
    hash = "sha256-6Hhmat92ApFn7ze2iYyOusDxXMYp98v1GNqAvKypKSQ=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus/6.5";
  };

  "org.codehaus.plexus_plexus-archiver-4.12.0" = fetchMaven {
    name = "org.codehaus.plexus_plexus-archiver-4.12.0";
    urls = [
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-archiver/4.12.0/plexus-archiver-4.12.0.jar"
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-archiver/4.12.0/plexus-archiver-4.12.0.pom"
    ];
    hash = "sha256-ZbLTVRhQS2uhONiCr6aeFieWnIEoRMfJ2ekMUvnd4hs=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-archiver/4.12.0";
  };

  "org.codehaus.plexus_plexus-classworlds-2.6.0" = fetchMaven {
    name = "org.codehaus.plexus_plexus-classworlds-2.6.0";
    urls = [
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-classworlds/2.6.0/plexus-classworlds-2.6.0.jar"
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-classworlds/2.6.0/plexus-classworlds-2.6.0.pom"
    ];
    hash = "sha256-vh7/TKxdcZVxXljM5MLGppoP0Bc28QyI/WsrPc6XSEA=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-classworlds/2.6.0";
  };

  "org.codehaus.plexus_plexus-container-default-2.1.1" = fetchMaven {
    name = "org.codehaus.plexus_plexus-container-default-2.1.1";
    urls = [
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-container-default/2.1.1/plexus-container-default-2.1.1.jar"
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-container-default/2.1.1/plexus-container-default-2.1.1.pom"
    ];
    hash = "sha256-E0Dt5DQRVlxg8fddMJZpvhU5cfNwB9MJTi/GJ1PVt3A=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-container-default/2.1.1";
  };

  "org.codehaus.plexus_plexus-containers-2.1.1" = fetchMaven {
    name = "org.codehaus.plexus_plexus-containers-2.1.1";
    urls = [
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-containers/2.1.1/plexus-containers-2.1.1.pom"
    ];
    hash = "sha256-LR5FBjo4qAjwjKpHajTnuUBN7cLKbeTJRtYYc8q4FNw=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-containers/2.1.1";
  };

  "org.codehaus.plexus_plexus-io-3.6.0" = fetchMaven {
    name = "org.codehaus.plexus_plexus-io-3.6.0";
    urls = [
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-io/3.6.0/plexus-io-3.6.0.jar"
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-io/3.6.0/plexus-io-3.6.0.pom"
    ];
    hash = "sha256-Ngo8Uh6W3IVcNxiuF3frTFpTPl5S+8+EHFoyHt8CmIg=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-io/3.6.0";
  };

  "org.codehaus.plexus_plexus-utils-4.0.3" = fetchMaven {
    name = "org.codehaus.plexus_plexus-utils-4.0.3";
    urls = [
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/4.0.3/plexus-utils-4.0.3.jar"
      "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/4.0.3/plexus-utils-4.0.3.pom"
    ];
    hash = "sha256-M4oRiUyz5G1YtshYWZ3kIs4QvLeVeuK8h/ENvGq43fA=";
    installPath = "https/repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/4.0.3";
  };

  "org.eclipse.ee4j_project-1.0.7" = fetchMaven {
    name = "org.eclipse.ee4j_project-1.0.7";
    urls = [ "https://repo1.maven.org/maven2/org/eclipse/ee4j/project/1.0.7/project-1.0.7.pom" ];
    hash = "sha256-1HxZiJ0aeo1n8AWjwGKEoPwVFP9kndMBye7xwgYEal8=";
    installPath = "https/repo1.maven.org/maven2/org/eclipse/ee4j/project/1.0.7";
  };

  "org.eclipse.jetty_jetty-bom-11.0.26" = fetchMaven {
    name = "org.eclipse.jetty_jetty-bom-11.0.26";
    urls = [
      "https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-bom/11.0.26/jetty-bom-11.0.26.pom"
    ];
    hash = "sha256-eY2KApjnU+y4Gup33Oe/aFgwOyzNaUnuncQV88ZVbr8=";
    installPath = "https/repo1.maven.org/maven2/org/eclipse/jetty/jetty-bom/11.0.26";
  };

  "org.fusesource.jansi_jansi-2.4.1" = fetchMaven {
    name = "org.fusesource.jansi_jansi-2.4.1";
    urls = [
      "https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/2.4.1/jansi-2.4.1.jar"
      "https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/2.4.1/jansi-2.4.1.pom"
    ];
    hash = "sha256-M9G+H9TA5eB6NwlBmDP0ghxZzjbvLimPXNRZHyxJXac=";
    installPath = "https/repo1.maven.org/maven2/org/fusesource/jansi/jansi/2.4.1";
  };

  "org.ow2.asm_asm-9.10.1" = fetchMaven {
    name = "org.ow2.asm_asm-9.10.1";
    urls = [
      "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar"
      "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.pom"
    ];
    hash = "sha256-uZFleQph2yFXtHKMNg2vZpEUR0O2unQ+FXn8QHef1JM=";
    installPath = "https/repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1";
  };

  "org.ow2.asm_asm-9.8" = fetchMaven {
    name = "org.ow2.asm_asm-9.8";
    urls = [
      "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar"
      "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.pom"
    ];
    hash = "sha256-+veD/6/fvI/ohZYhYhoChm0qeS7TaclJO9qnsSkBUxY=";
    installPath = "https/repo1.maven.org/maven2/org/ow2/asm/asm/9.8";
  };

  "org.ow2.asm_asm-analysis-9.10.1" = fetchMaven {
    name = "org.ow2.asm_asm-analysis-9.10.1";
    urls = [
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar"
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.pom"
    ];
    hash = "sha256-4Tnad7EZCG3sv33YlFxHdU0HhgfKXhveIbERqXCL1hY=";
    installPath = "https/repo1.maven.org/maven2/org/ow2/asm/asm-analysis/9.10.1";
  };

  "org.ow2.asm_asm-commons-9.8" = fetchMaven {
    name = "org.ow2.asm_asm-commons-9.8";
    urls = [
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar"
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.pom"
    ];
    hash = "sha256-wsQ21wHx134MlpbT+REdvnECHkoXEGLw26aybgUqk1c=";
    installPath = "https/repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.8";
  };

  "org.ow2.asm_asm-tree-9.10.1" = fetchMaven {
    name = "org.ow2.asm_asm-tree-9.10.1";
    urls = [
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar"
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.pom"
    ];
    hash = "sha256-7B7lA6LdCvvar/SW6dMiO4m/HMPINt757DqWOt1IIaU=";
    installPath = "https/repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.10.1";
  };

  "org.ow2.asm_asm-tree-9.8" = fetchMaven {
    name = "org.ow2.asm_asm-tree-9.8";
    urls = [
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar"
      "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.pom"
    ];
    hash = "sha256-ZxdFTSgXy5f+gdS/FvxW+0oyf+5+RFUm3hv7G0akkQk=";
    installPath = "https/repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.8";
  };

  "org.scala-lang.modules_scala-asm-9.9.0-scala-1" = fetchMaven {
    name = "org.scala-lang.modules_scala-asm-9.9.0-scala-1";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.pom"
    ];
    hash = "sha256-0zHgDkd1xWwpw896w+ayT2x7L4YmtTgA3NcObdySv3c=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-asm/9.9.0-scala-1";
  };

  "org.scala-lang.modules_scala-collection-compat_2.13-2.13.0" = fetchMaven {
    name = "org.scala-lang.modules_scala-collection-compat_2.13-2.13.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.13.0/scala-collection-compat_2.13-2.13.0.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.13.0/scala-collection-compat_2.13-2.13.0.pom"
    ];
    hash = "sha256-aQ+I3JuE8U5GIdb4SlHbZWdPu4E/qRIoZSGMMP3g5GE=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.13.0";
  };

  "org.scala-lang.modules_scala-collection-compat_2.13-2.8.1" = fetchMaven {
    name = "org.scala-lang.modules_scala-collection-compat_2.13-2.8.1";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.8.1/scala-collection-compat_2.13-2.8.1.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.8.1/scala-collection-compat_2.13-2.8.1.pom"
    ];
    hash = "sha256-VLdlQAHq2fj8I+70dC8TEa8NeFITtzgI/K7iEK+BcXg=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.8.1";
  };

  "org.scala-lang.modules_scala-collection-compat_3-2.12.0" = fetchMaven {
    name = "org.scala-lang.modules_scala-collection-compat_3-2.12.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_3/2.12.0/scala-collection-compat_3-2.12.0.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_3/2.12.0/scala-collection-compat_3-2.12.0.pom"
    ];
    hash = "sha256-ne2PoJ4ge4ygNIDFAkpo++XaJNsiGE7gqtT7HbG4gVs=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_3/2.12.0";
  };

  "org.scala-lang.modules_scala-parallel-collections_3-1.2.0" = fetchMaven {
    name = "org.scala-lang.modules_scala-parallel-collections_3-1.2.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parallel-collections_3/1.2.0/scala-parallel-collections_3-1.2.0.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parallel-collections_3/1.2.0/scala-parallel-collections_3-1.2.0.pom"
    ];
    hash = "sha256-v1k+cav2Bl/xAhvOy6AxlyMjbcLH1wI2/2Cd/M6uFyY=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-parallel-collections_3/1.2.0";
  };

  "org.scala-lang.modules_scala-parser-combinators_2.13-2.1.1" = fetchMaven {
    name = "org.scala-lang.modules_scala-parser-combinators_2.13-2.1.1";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parser-combinators_2.13/2.1.1/scala-parser-combinators_2.13-2.1.1.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parser-combinators_2.13/2.1.1/scala-parser-combinators_2.13-2.1.1.pom"
    ];
    hash = "sha256-AxdeQjvcl6mzY2zoXIwbTr9abt6UbsrcgSyss7WXPy4=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-parser-combinators_2.13/2.1.1";
  };

  "org.scala-lang.modules_scala-parser-combinators_3-2.1.0" = fetchMaven {
    name = "org.scala-lang.modules_scala-parser-combinators_3-2.1.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parser-combinators_3/2.1.0/scala-parser-combinators_3-2.1.0.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-parser-combinators_3/2.1.0/scala-parser-combinators_3-2.1.0.pom"
    ];
    hash = "sha256-hsgwr5S9JNBoRdLgmEGzyBbA3i2uFyBPqUDQJEtMmsg=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-parser-combinators_3/2.1.0";
  };

  "org.scala-lang.modules_scala-xml_2.13-2.1.0" = fetchMaven {
    name = "org.scala-lang.modules_scala-xml_2.13-2.1.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.1.0/scala-xml_2.13-2.1.0.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.1.0/scala-xml_2.13-2.1.0.pom"
    ];
    hash = "sha256-CYRcBgRmprVNrgpZ5OB8pJN00UwmQnc6vNftX4Z4EqE=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.1.0";
  };

  "org.scala-lang.modules_scala-xml_2.13-2.4.0" = fetchMaven {
    name = "org.scala-lang.modules_scala-xml_2.13-2.4.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.4.0/scala-xml_2.13-2.4.0.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.4.0/scala-xml_2.13-2.4.0.pom"
    ];
    hash = "sha256-e5pQSejMXF2nSlmD8wBFRkxcRN+8nEHW/89qN0Je0dY=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.4.0";
  };

  "org.scala-lang.modules_scala-xml_3-2.0.1" = fetchMaven {
    name = "org.scala-lang.modules_scala-xml_3-2.0.1";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.0.1/scala-xml_3-2.0.1.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.0.1/scala-xml_3-2.0.1.pom"
    ];
    hash = "sha256-OFAf/c4/dKnDP+IEpmXFg6Thbt0voLEge9R6SDZrQIc=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.0.1";
  };

  "org.scala-lang.modules_scala-xml_3-2.4.0" = fetchMaven {
    name = "org.scala-lang.modules_scala-xml_3-2.4.0";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.4.0/scala-xml_3-2.4.0.jar"
      "https://repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.4.0/scala-xml_3-2.4.0.pom"
    ];
    hash = "sha256-+7nNhpZLvDvMGTITT2eg3S4G87M2HHyqb/AvmNet/l0=";
    installPath = "https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_3/2.4.0";
  };

  "org.scala-sbt.jline_jline-2.14.7-sbt-9a88bc413e2b34a4580c001c654d1a7f4f65bf18" = fetchMaven {
    name = "org.scala-sbt.jline_jline-2.14.7-sbt-9a88bc413e2b34a4580c001c654d1a7f4f65bf18";
    urls = [
      "https://repo1.maven.org/maven2/org/scala-sbt/jline/jline/2.14.7-sbt-9a88bc413e2b34a4580c001c654d1a7f4f65bf18/jline-2.14.7-sbt-9a88bc413e2b34a4580c001c654d1a7f4f65bf18.jar"
      "https://repo1.maven.org/maven2/org/scala-sbt/jline/jline/2.14.7-sbt-9a88bc413e2b34a4580c001c654d1a7f4f65bf18/jline-2.14.7-sbt-9a88bc413e2b34a4580c001c654d1a7f4f65bf18.pom"
    ];
    hash = "sha256-1Nq7/UMXSlaZ7iwR1WMryltAmS8/fRCK6u93cm+1uh4=";
    installPath = "https/repo1.maven.org/maven2/org/scala-sbt/jline/jline/2.14.7-sbt-9a88bc413e2b34a4580c001c654d1a7f4f65bf18";
  };

  "org.sonatype.oss_oss-parent-7" = fetchMaven {
    name = "org.sonatype.oss_oss-parent-7";
    urls = [ "https://repo1.maven.org/maven2/org/sonatype/oss/oss-parent/7/oss-parent-7.pom" ];
    hash = "sha256-HDM4YUA2cNuWnhH7wHWZfxzLMdIr2AT36B3zuJFrXbE=";
    installPath = "https/repo1.maven.org/maven2/org/sonatype/oss/oss-parent/7";
  };

  "org.sonatype.oss_oss-parent-9" = fetchMaven {
    name = "org.sonatype.oss_oss-parent-9";
    urls = [ "https://repo1.maven.org/maven2/org/sonatype/oss/oss-parent/9/oss-parent-9.pom" ];
    hash = "sha256-kJ3QfnDTAvamYaHQowpAKW1gPDFDXbiP2lNPzNllIWY=";
    installPath = "https/repo1.maven.org/maven2/org/sonatype/oss/oss-parent/9";
  };

  "org.virtuslab.scala-cli_config_3-1.14.0" = fetchMaven {
    name = "org.virtuslab.scala-cli_config_3-1.14.0";
    urls = [
      "https://repo1.maven.org/maven2/org/virtuslab/scala-cli/config_3/1.14.0/config_3-1.14.0.jar"
      "https://repo1.maven.org/maven2/org/virtuslab/scala-cli/config_3/1.14.0/config_3-1.14.0.pom"
    ];
    hash = "sha256-Um/x562TwSYKWRQ3ySUv5pvSzOsEzSNEt0RZEupLf3k=";
    installPath = "https/repo1.maven.org/maven2/org/virtuslab/scala-cli/config_3/1.14.0";
  };

  "org.virtuslab.scala-cli_specification-level_3-1.14.0" = fetchMaven {
    name = "org.virtuslab.scala-cli_specification-level_3-1.14.0";
    urls = [
      "https://repo1.maven.org/maven2/org/virtuslab/scala-cli/specification-level_3/1.14.0/specification-level_3-1.14.0.jar"
      "https://repo1.maven.org/maven2/org/virtuslab/scala-cli/specification-level_3/1.14.0/specification-level_3-1.14.0.pom"
    ];
    hash = "sha256-26hUX18+m4vZErjxl/auCbq/v7/XeYINXmrNagCg/Hc=";
    installPath = "https/repo1.maven.org/maven2/org/virtuslab/scala-cli/specification-level_3/1.14.0";
  };

  "software.amazon.awssdk_aws-sdk-java-pom-2.44.9" = fetchMaven {
    name = "software.amazon.awssdk_aws-sdk-java-pom-2.44.9";
    urls = [
      "https://repo1.maven.org/maven2/software/amazon/awssdk/aws-sdk-java-pom/2.44.9/aws-sdk-java-pom-2.44.9.pom"
    ];
    hash = "sha256-TiVakzQOmHW9QrWNfJpV7fxxVhF++87G7gXTjrjYf+Y=";
    installPath = "https/repo1.maven.org/maven2/software/amazon/awssdk/aws-sdk-java-pom/2.44.9";
  };

  "software.amazon.awssdk_bom-2.44.9" = fetchMaven {
    name = "software.amazon.awssdk_bom-2.44.9";
    urls = [ "https://repo1.maven.org/maven2/software/amazon/awssdk/bom/2.44.9/bom-2.44.9.pom" ];
    hash = "sha256-WcvB8hP9a9bgv0vU3Uy8Eta9uYPqUDRQGagc5GuknxY=";
    installPath = "https/repo1.maven.org/maven2/software/amazon/awssdk/bom/2.44.9";
  };

  "com.github.plokhotnyuk.jsoniter-scala_jsoniter-scala-core_2.13-2.13.5" = fetchMaven {
    name = "com.github.plokhotnyuk.jsoniter-scala_jsoniter-scala-core_2.13-2.13.5";
    urls = [
      "https://repo1.maven.org/maven2/com/github/plokhotnyuk/jsoniter-scala/jsoniter-scala-core_2.13/2.13.5/jsoniter-scala-core_2.13-2.13.5.jar"
      "https://repo1.maven.org/maven2/com/github/plokhotnyuk/jsoniter-scala/jsoniter-scala-core_2.13/2.13.5/jsoniter-scala-core_2.13-2.13.5.pom"
    ];
    hash = "sha256-uQ7ULWW7il8C1f07v2grRCOzgxDH31UlmtAuL9m/VE8=";
    installPath = "https/repo1.maven.org/maven2/com/github/plokhotnyuk/jsoniter-scala/jsoniter-scala-core_2.13/2.13.5";
  };

  "io.github.alexarchambault.native-terminal_native-terminal-no-ffm-0.0.9.1" = fetchMaven {
    name = "io.github.alexarchambault.native-terminal_native-terminal-no-ffm-0.0.9.1";
    urls = [
      "https://repo1.maven.org/maven2/io/github/alexarchambault/native-terminal/native-terminal-no-ffm/0.0.9.1/native-terminal-no-ffm-0.0.9.1.jar"
      "https://repo1.maven.org/maven2/io/github/alexarchambault/native-terminal/native-terminal-no-ffm/0.0.9.1/native-terminal-no-ffm-0.0.9.1.pom"
    ];
    hash = "sha256-fHtvFUaVlrgdz+S3mPlxXjA4mpSBWC+hjW3U7h5NFo0=";
    installPath = "https/repo1.maven.org/maven2/io/github/alexarchambault/native-terminal/native-terminal-no-ffm/0.0.9.1";
  };

  "io.github.alexarchambault.windows-ansi_windows-ansi-0.0.6" = fetchMaven {
    name = "io.github.alexarchambault.windows-ansi_windows-ansi-0.0.6";
    urls = [
      "https://repo1.maven.org/maven2/io/github/alexarchambault/windows-ansi/windows-ansi/0.0.6/windows-ansi-0.0.6.jar"
      "https://repo1.maven.org/maven2/io/github/alexarchambault/windows-ansi/windows-ansi/0.0.6/windows-ansi-0.0.6.pom"
    ];
    hash = "sha256-TGUrDCPYFiXV5b2If3u4KviH3JxZttMOKL1HUHqIWRo=";
    installPath = "https/repo1.maven.org/maven2/io/github/alexarchambault/windows-ansi/windows-ansi/0.0.6";
  };

  "net.java.dev.jna_jna-5.12.1" = fetchMaven {
    name = "net.java.dev.jna_jna-5.12.1";
    urls = [
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.12.1/jna-5.12.1.jar"
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.12.1/jna-5.12.1.pom"
    ];
    hash = "sha256-xyspXesCQvsXEo4NmmKY17wiNZM6cvMTOzaH1bVi/p4=";
    installPath = "https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.12.1";
  };

  "net.java.dev.jna_jna-5.13.0" = fetchMaven {
    name = "net.java.dev.jna_jna-5.13.0";
    urls = [
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar"
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.pom"
    ];
    hash = "sha256-LP1W3fVxMEP6po1dlkAseu3pSeSnobemZJaxKivwqDs=";
    installPath = "https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0";
  };

  "net.java.dev.jna_jna-5.15.0" = fetchMaven {
    name = "net.java.dev.jna_jna-5.15.0";
    urls = [ "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.15.0/jna-5.15.0.pom" ];
    hash = "sha256-DSCkx29i2QxUeyOUpCbdGWiYeB2r7RLsgBNFolj2cUg=";
    installPath = "https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.15.0";
  };

  "net.java.dev.jna_jna-5.19.1" = fetchMaven {
    name = "net.java.dev.jna_jna-5.19.1";
    urls = [
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.19.1/jna-5.19.1.jar"
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.19.1/jna-5.19.1.pom"
    ];
    hash = "sha256-QCdOQ9cY++5Ici/Ga3rBUPK7X+FYDL6hF48mFDt2FxI=";
    installPath = "https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.19.1";
  };

  "net.java.dev.jna_jna-5.3.1" = fetchMaven {
    name = "net.java.dev.jna_jna-5.3.1";
    urls = [ "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.3.1/jna-5.3.1.pom" ];
    hash = "sha256-rnFC1CJ6hbIMk0SS1HBeQmEzBVR/o6nncPgwgzorBC4=";
    installPath = "https/repo1.maven.org/maven2/net/java/dev/jna/jna/5.3.1";
  };

  "net.java.dev.jna_jna-platform-5.12.1" = fetchMaven {
    name = "net.java.dev.jna_jna-platform-5.12.1";
    urls = [
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.12.1/jna-platform-5.12.1.jar"
      "https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.12.1/jna-platform-5.12.1.pom"
    ];
    hash = "sha256-PrSz4TnoJiUH0lDlj9r7j2X/knvoRyENebVyj2thu64=";
    installPath = "https/repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.12.1";
  };

  "org.apache.geronimo.genesis_genesis-2.0" = fetchMaven {
    name = "org.apache.geronimo.genesis_genesis-2.0";
    urls = [ "https://repo1.maven.org/maven2/org/apache/geronimo/genesis/genesis/2.0/genesis-2.0.pom" ];
    hash = "sha256-lcX5R64+07kRLqpdfkay87hJI6ykVn/wUXs142Elips=";
    installPath = "https/repo1.maven.org/maven2/org/apache/geronimo/genesis/genesis/2.0";
  };

  "org.apache.geronimo.genesis_genesis-default-flava-2.0" = fetchMaven {
    name = "org.apache.geronimo.genesis_genesis-default-flava-2.0";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/geronimo/genesis/genesis-default-flava/2.0/genesis-default-flava-2.0.pom"
    ];
    hash = "sha256-jkGo9ePZSnxqcIOQIuAz1ZTPNjjx2vc01oxtt6EJuUk=";
    installPath = "https/repo1.maven.org/maven2/org/apache/geronimo/genesis/genesis-default-flava/2.0";
  };

  "org.apache.geronimo.genesis_genesis-java5-flava-2.0" = fetchMaven {
    name = "org.apache.geronimo.genesis_genesis-java5-flava-2.0";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/geronimo/genesis/genesis-java5-flava/2.0/genesis-java5-flava-2.0.pom"
    ];
    hash = "sha256-CTKaQ0fTVeVBnQrWm4TCcbTONXm/N6bPXPGXx0hToLQ=";
    installPath = "https/repo1.maven.org/maven2/org/apache/geronimo/genesis/genesis-java5-flava/2.0";
  };

  "org.apache.logging.log4j_log4j-2.26.0" = fetchMaven {
    name = "org.apache.logging.log4j_log4j-2.26.0";
    urls = [ "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j/2.26.0/log4j-2.26.0.pom" ];
    hash = "sha256-Qa1v36wmjFdOMgc8FJPYvjB2zaIy0jTFpSeAzyvrsZQ=";
    installPath = "https/repo1.maven.org/maven2/org/apache/logging/log4j/log4j/2.26.0";
  };

  "org.apache.logging.log4j_log4j-api-2.26.0" = fetchMaven {
    name = "org.apache.logging.log4j_log4j-api-2.26.0";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.jar"
      "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.pom"
    ];
    hash = "sha256-MYNRKR3N3TCjdJn2mL9JejOXaj7HfBUJEtKNe/bBn+w=";
    installPath = "https/repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.26.0";
  };

  "org.apache.logging.log4j_log4j-bom-2.26.0" = fetchMaven {
    name = "org.apache.logging.log4j_log4j-bom-2.26.0";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-bom/2.26.0/log4j-bom-2.26.0.pom"
    ];
    hash = "sha256-TkG3GThRUtmWA+SxPIO/Olw3aGona5995tYIDFpFVuI=";
    installPath = "https/repo1.maven.org/maven2/org/apache/logging/log4j/log4j-bom/2.26.0";
  };

  "org.apache.logging.log4j_log4j-core-2.26.0" = fetchMaven {
    name = "org.apache.logging.log4j_log4j-core-2.26.0";
    urls = [
      "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.26.0/log4j-core-2.26.0.jar"
      "https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.26.0/log4j-core-2.26.0.pom"
    ];
    hash = "sha256-9D2aIgWXv5lwtKXvqBhPBNr/HDSECODNHuq/rBH35Xk=";
    installPath = "https/repo1.maven.org/maven2/org/apache/logging/log4j/log4j-core/2.26.0";
  };

}
