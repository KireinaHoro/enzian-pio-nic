# Complete deployment products; flake.nix only exports this inventory.
{
  bootFiles,
  eciVivadoInputs,
  applications,
  deployment,
  memcachedTools,
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
  memcachedImage = deployment.mkApplicationImage {
    name = "memcached";
    applications = {
      inherit (applications) memcached;
      memcached-tools = memcachedTools;
    };
  };
}
