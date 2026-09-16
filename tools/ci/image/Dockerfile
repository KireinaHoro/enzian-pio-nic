# CI requests 16 GiB for Kaniko to snapshot the populated store (see .gitlab-ci.yml).
# The build plan contains evaluated derivations and source closures, never credentials.
FROM nixos/nix:2.31.3@sha256:d49538facc7757890eccfa5f57869ad18c823c24903e3b8ac6ca8a301d84a9b6

ARG LAUBERHORN_REV
LABEL org.opencontainers.image.title="Lauberhorn Nix CI" \
      ch.ethz.enzian.lauberhorn.revision="${LAUBERHORN_REV}"

# Docker supplies isolation; nested Nix sandbox namespaces are unavailable here.
RUN printf '%s\n' 'experimental-features = nix-command flakes' \
    'sandbox = false' 'cores = 8' 'max-jobs = 1' \
    'keep-derivations = true' 'keep-outputs = true' >> /etc/nix/nix.conf
COPY lauberhorn-inputs /opt/lauberhorn-inputs
COPY prewarm.sh /usr/local/bin/prewarm-lauberhorn
RUN bash /usr/local/bin/prewarm-lauberhorn
