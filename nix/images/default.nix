# Complete deployment products; flake.nix only exports this inventory.
{
  bootFiles,
  eciVivadoInputs,
  applications,
  deployment,
  memcachedTools,
  memcachedTcp,
  transportValidation,
  transportPeerTools,
  trafficGeneratorImage,
  echoCampaignImage,
  mlx5BenchmarkImage,
}:
{
  inherit bootFiles eciVivadoInputs trafficGeneratorImage echoCampaignImage mlx5BenchmarkImage;
  microbenchmarksImage = deployment.mkApplicationImage {
    name = "microbenchmarks";
    applications = { inherit (applications) microbenchmarks recovery-test nested-validation; };
  };
  dandelionImage = deployment.mkApplicationImage {
    name = "dandelion";
    applications = {
      inherit (applications) dandelion;
      dandelion-tests = applications.dandelion.interactiveTests.smoke;
    };
  };
  tcpValidationImage = deployment.mkApplicationImage {
    name = "tcp-validation";
    extraContents = transportPeerTools;
    applications = {
      inherit (applications) microbenchmarks nested-validation;
      memcached = memcachedTcp;
      transport-validation = transportValidation;
      memcached-tools = memcachedTools;
    };
  };
  memcachedImage = deployment.mkApplicationImage {
    name = "memcached";
    applications = {
      inherit (applications) memcached;
      memcached-tools = memcachedTools;
    };
  };
}
